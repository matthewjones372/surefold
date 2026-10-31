#!/usr/bin/env python3
"""Run the approved 0266 gate. Uses Python's standard library and installed CLIs."""
import argparse
import datetime
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import subprocess
import sys
from urllib.parse import quote


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=180, **kwargs).stdout.strip()


def cpus(text):
    found = set()
    for part in text.split(","):
        bounds = part.split("-")
        found.update(range(int(bounds[0]), int(bounds[-1]) + 1))
    return found


def qualify(args):
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("gate requires Linux amd64")
    if not args.database or "=" in args.database or args.database.lower().startswith(("postgresql://", "postgres://")):
        raise ValueError("provide a database name, not a connection string")
    if any(os.environ.get(key) for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS")):
        raise ValueError("remove externally injected JVM options before benchmarking")
    docker_host = os.environ.get("DOCKER_HOST")
    endpoint = json.loads(command(["docker", "context", "inspect"]))[0]["Endpoints"]["docker"]["Host"]
    if not endpoint.startswith("unix://") or (docker_host and not docker_host.startswith("unix://")):
        raise ValueError("gate requires a local Docker Unix socket; remote contexts/DOCKER_HOST are rejected")
    clients, postgres = cpus(args.client_cpus), cpus(args.postgres_cpus)
    if len(clients) != 4 or len(postgres) != 4 or clients & postgres:
        raise ValueError("provide four separate CPUs each for client and Postgres")
    online = cpus(Path("/sys/devices/system/cpu/online").read_text().strip())
    topology = {}
    for cpu in online:
        base = Path(f"/sys/devices/system/cpu/cpu{cpu}/topology")
        package = (base / "physical_package_id").read_text().strip()
        core = (base / "core_id").read_text().strip()
        if cpu in clients | postgres:
            siblings = cpus((base / "thread_siblings_list").read_text().strip())
            if len(siblings & online) != 1:
                raise ValueError(f"disable SMT on benchmark core containing CPU {cpu}")
            topology[str(cpu)] = [package, core]
    if len({tuple(t) for t in topology.values()}) != 8:
        raise ValueError("eight distinct physical cores required")
    memory = int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines() if line.startswith("MemTotal:"))) * 1024
    if memory < 16 * 1024**3:
        raise ValueError("16 GiB physical RAM required")
    if not clients <= os.sched_getaffinity(0):
        raise ValueError("client CPUs unavailable to this process")
    pinned = Path(args.image_digest).read_text().strip()
    if "@sha256:" not in pinned or len(pinned.rsplit("sha256:", 1)[1]) != 64:
        raise ValueError("image file must contain an immutable repository@sha256:digest")
    container = json.loads(command(["docker", "inspect", args.container]))[0]
    if not container["State"]["Running"] or container["Config"]["Image"] != pinned:
        raise ValueError("running Postgres must use exactly the committed immutable image")
    network = container["HostConfig"]["NetworkMode"]
    if network == "host" or network.startswith("container:"):
        raise ValueError("Postgres requires its own container network namespace")
    if cpus(container["HostConfig"]["CpusetCpus"]) != postgres:
        raise ValueError("Postgres container CPU pinning differs from requested CPUs")
    config = container["HostConfig"]
    nano, quota, period = config["NanoCpus"], config["CpuQuota"], config["CpuPeriod"] or 100000
    if (nano and nano < 4_000_000_000) or (quota > 0 and quota < 4 * period):
        raise ValueError("Postgres CPU quota throttles below four cores")
    bindings = container["NetworkSettings"]["Ports"].get("5432/tcp") or []
    if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
        raise ValueError("publish exactly one PostgreSQL 5432/tcp mapping on 127.0.0.1")
    host_port = int(bindings[0]["HostPort"])
    membership = Path("/proc/self/cgroup").read_text()
    client_limits = {}
    for line in membership.splitlines():
        _, controllers, group = line.split(":", 2)
        if controllers and "cpu" not in controllers.split(","):
            continue
        base = Path("/sys/fs/cgroup") / ("cpu" if controllers else "") / group.lstrip("/")
        for field in ("cpu.max", "cpu.cfs_quota_us", "cpu.cfs_period_us"):
            path = base / field
            client_limits[str(path)] = path.read_text().strip() if os.access(path, os.R_OK) else None
    values = {Path(path).name: value for path, value in client_limits.items() if value is not None}
    v1_quota = f"{values.get('cpu.cfs_quota_us', '-1')} {values.get('cpu.cfs_period_us', '1')}"
    quota, period = values.get("cpu.max", v1_quota).split()
    if quota not in ("max", "-1") and int(quota) < 4 * int(period):
        raise ValueError("readable client CPU quota throttles below four cores")
    image = json.loads(command(["docker", "image", "inspect", pinned]))[0]
    if image["Architecture"] != "amd64":
        raise ValueError("Postgres image must be amd64")
    command(["git", "ls-files", "--error-unmatch", args.image_digest])
    command(["git", "diff", "--exit-code", "HEAD", "--", args.image_digest])
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    version = subprocess.run([java, "-version"], check=True, capture_output=True, text=True, timeout=180).stderr
    if not re.search(r'version "25(?:[.+-]|")', version):
        raise ValueError("JDK25 required before database reset")
    if not args.idle_host_attested:
        raise ValueError("attest no other load and four client cores unrestricted by ancestor quotas")
    return {"platform": platform.platform(), "python": platform.python_version(), "cpuinfo": Path("/proc/cpuinfo").read_text(),
            "memory_bytes": memory, "topology": topology, "java": version,
            "docker_endpoint": docker_host or endpoint, "postgres_host_port": host_port,
            "client_cgroup_membership": membership, "client_cgroup_files": client_limits,
            "client_ancestor_quota_attested": True,
            "container_id": container["Id"], "container_image": pinned,
            "image_id": image["Id"], "image_arch": image["Architecture"],
            "container_limits": {key: config[key] for key in (
                "CpusetCpus", "NanoCpus", "CpuQuota", "CpuPeriod", "Memory", "MemorySwap",
                "MemoryReservation", "PidsLimit", "ShmSize")}, "idle_host_attested": True,
            "client_cpus": args.client_cpus, "postgres_cpus": args.postgres_cpus,
            "git_commit": command(["git", "rev-parse", "HEAD"])}


def validate_metrics(value):
    if not isinstance(value, dict) or any(
        type(value.get(key)) not in (int, float) or not math.isfinite(value[key]) or value[key] <= 0
        for key in ("throughput", "p95_nanos")
    ):
        raise ValueError("Invalid benchmark metrics")


def validate_result(value, mode):
    validate_metrics(value)
    positive = ("operations", "elapsed_nanos", "p50_nanos", "p95_nanos", "p99_nanos",
                "process_cpu_nanos", "allocation_bytes_approx", "expected_spans")
    if any(type(value.get(key)) is not int or not 0 < value[key] < 2**63 for key in positive):
        raise ValueError("Invalid benchmark counters")
    exported = value.get("exported")
    if value.get("mode") != mode or value.get("flush_success") is not True or type(exported) is not int:
        raise ValueError("Invalid benchmark mode or flush result")
    if value["elapsed_nanos"] < 60_000_000_000 or not value["p50_nanos"] <= value["p95_nanos"] <= value["p99_nanos"]:
        raise ValueError("Invalid benchmark measurement interval or percentiles")
    if not math.isclose(value["throughput"], value["operations"] * 1e9 / value["elapsed_nanos"], rel_tol=1e-12):
        raise ValueError("Invalid benchmark throughput")
    if value["expected_spans"] <= value["operations"] or exported != (value["expected_spans"] if mode == "recording" else 0):
        raise ValueError("Invalid benchmark delivery counts")
    return value


def bootstrap(pairs):
    for pair in pairs:
        for value in pair:
            validate_metrics(value)
    rng = random.Random(266)
    losses = [100 * (1 - variant["throughput"] / baseline["throughput"]) for baseline, variant in pairs]
    latencies = [100 * (variant["p95_nanos"] / baseline["p95_nanos"] - 1) for baseline, variant in pairs]
    samples = [[], []]
    for _ in range(10_000):
        indices = [rng.randrange(len(pairs)) for _ in pairs]
        for values, target in zip((losses, latencies), samples):
            target.append(sum(values[i] for i in indices) / len(indices))
    bounds = [sorted(values)[math.ceil(.95 * len(values)) - 1] for values in samples]
    return {"pairs": len(pairs), "throughput_loss_percent": losses, "p95_increase_percent": latencies,
            "throughput_loss_mean": sum(losses) / len(losses), "p95_increase_mean": sum(latencies) / len(latencies),
            "throughput_loss_upper95": bounds[0], "p95_increase_upper95": bounds[1],
            "pass": len(pairs) == 10 and all(math.isfinite(value) and value <= 2 for value in bounds)}


def run(args):
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=False)
    manifest = qualify(args)
    (destination / "host.json").write_text(json.dumps(manifest, indent=2) + "\n")
    reset = """CREATE TABLE IF NOT EXISTS bench_rows(id BIGINT PRIMARY KEY, value BIGINT NOT NULL);
TRUNCATE bench_rows;
INSERT INTO bench_rows SELECT id, 0 FROM generate_series(1, 1000000) id;
ANALYZE bench_rows;
SELECT count(*), min(value), max(value) FROM bench_rows;"""
    env = dict(os.environ)
    env["SUREFOLD_BENCH_JDBC_URL"] = (
        f"jdbc:postgresql://127.0.0.1:{manifest['postgres_host_port']}/{quote(args.database, safe='')}"
    )
    env["PGUSER"] = args.database_user
    psql = ["docker", "exec", "-i", "-e", "PGPASSWORD", args.container, "psql", "-X", "-v", "ON_ERROR_STOP=1",
            "-h", "127.0.0.1", "-p", "5432", "-U", args.database_user, "-d", args.database]
    identity_sql = """SELECT json_build_object('database', current_database(),
        'version', current_setting('server_version_num'), 'system_identifier', system_identifier::text)
        FROM pg_control_system()"""
    identity = json.loads(command(psql + ["-At", "-c", identity_sql]))
    if not 170000 <= int(identity["version"]) < 180000:
        raise ValueError("PostgreSQL 17 required")
    env["SUREFOLD_BENCH_EXPECTED_DATABASE"] = identity["database"]
    env["SUREFOLD_BENCH_EXPECTED_SYSTEM_IDENTIFIER"] = identity["system_identifier"]
    (destination / "database.json").write_text(json.dumps(identity, indent=2) + "\n")
    all_pairs = {}
    for mode in ("disabled", "noop", "sampled-out", "recording"):
        pairs = []
        for pair in range(10):
            results = {}
            for actual in (("original", mode) if pair % 2 == 0 else (mode, "original")):
                name = f"{mode}-{pair:02d}-{actual}"
                result_file = (destination / f"{name}.json").resolve()
                argv = ["taskset", "-c", args.client_cpus, str(Path(args.binary).resolve()), actual, str(result_file)]
                record = {"command": argv, "started": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          "status": "incomplete", "phase": "reset", "timeout_seconds": 180}
                record_file = destination / f"{name}.attempt.json"
                record_file.write_text(json.dumps(record, indent=2) + "\n")
                try:
                    current_id = command(["docker", "inspect", "--format", "{{.Id}}", args.container])
                    if current_id != manifest["container_id"]:
                        raise ValueError("qualified container was replaced; attempt retained")
                    with (destination / f"{name}.reset.stdout").open("w") as out, \
                            (destination / f"{name}.reset.stderr").open("w") as err:
                        reset_run = subprocess.run(psql, input=reset, text=True, stdout=out, stderr=err, timeout=180)
                    record["reset_exit_code"] = reset_run.returncode
                    if reset_run.returncode:
                        record["status"] = "failed"
                        raise RuntimeError(f"reset failed for {name}; attempt retained")
                    record["phase"] = "workload"
                    record_file.write_text(json.dumps(record, indent=2) + "\n")
                    with (destination / f"{name}.stdout").open("w") as out, \
                            (destination / f"{name}.stderr").open("w") as err:
                        attempt = subprocess.run(argv, text=True, env=env, stdout=out, stderr=err, timeout=180)
                    record.update(exit_code=attempt.returncode, status="completed" if not attempt.returncode else "failed")
                    if attempt.returncode:
                        raise RuntimeError(f"run failed for {name}; attempt retained, no gate pass")
                    results[actual] = validate_result(json.loads(result_file.read_text()), actual)
                except subprocess.TimeoutExpired as failure:
                    record["status"] = "timeout"
                    raise RuntimeError(f"{name} timed out; partial output retained, no gate pass") from failure
                except Exception:
                    record["status"] = "failed"
                    raise
                finally:
                    record["finished"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                    record_file.write_text(json.dumps(record, indent=2) + "\n")
            pairs.append((results["original"], results[mode]))
        all_pairs[mode] = bootstrap(pairs)
        (destination / "summary.json").write_text(json.dumps(all_pairs, indent=2) + "\n")
    passed = all(result["pass"] for result in all_pairs.values())
    print(json.dumps({"qualified": True, "pass": passed, "results": all_pairs}, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("binary", "output", "client-cpus", "postgres-cpus", "container", "database", "image-digest"):
        parser.add_argument("--" + option, required=True)
    parser.add_argument("--database-user", default=os.environ.get("PGUSER", "postgres"))
    parser.add_argument("--idle-host-attested", action="store_true",
                        help="attest idle dedicated host and unrestricted four-core client allocation")
    options = parser.parse_args()
    try:
        sys.exit(run(options))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        parser.exit(2, str(failure) + "\n")
