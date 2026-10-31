# JDBC tracing performance gate

This unpublished tool implements spec 0266. A functional smoke or diagnostic run is
not a performance pass. Qualified measurements are still pending an owner-provided machine.

Use a dedicated Linux amd64 host with at least eight physical cores and 16 GiB RAM,
JDK 25, Python 3 and local Docker. Assign four distinct physical cores to the client
and four others to Postgres, disable SMT on those cores, and keep the host idle.
Check CPU numbering with `lscpu -e=CPU,CORE,SOCKET,ONLINE`; replace the example sets below.
The attestation also confirms ancestor cgroups do not restrict the four client cores.

Use a disposable database: the runner truncates `bench_rows` before every run.
The committed image pin must be unchanged in Git before measurements. From the repo root:

```sh
./gradlew :surefold-jdbc-otel-bench:installDist
python3 -m unittest discover -s bench/surefold-jdbc-otel-bench -p test_gate.py -v
image=$(cat bench/surefold-jdbc-otel-bench/postgres-image.txt)
docker run --detach --rm --name surefold-jdbc-0266 --cpuset-cpus=4-7 \
  --publish 127.0.0.1:54326:5432 --env POSTGRES_DB=surefold_bench_0266 \
  --env POSTGRES_HOST_AUTH_METHOD=trust "$image"
docker exec surefold-jdbc-0266 pg_isready -U postgres -d surefold_bench_0266
```

Wait for readiness, then run the gate. The container has only benchmark data and its
published port is bound to loopback. No password is needed for this disposable setup;
other authentication uses `PGPASSWORD`, never a password in command arguments or the JDBC URL.

```sh
python3 bench/surefold-jdbc-otel-bench/gate.py \
  --binary bench/surefold-jdbc-otel-bench/build/install/surefold-jdbc-otel-bench/bin/surefold-jdbc-otel-bench \
  --output "bench/surefold-jdbc-otel-bench/build/gate-$(date -u +%Y%m%dT%H%M%SZ)" \
  --client-cpus 0-3 --postgres-cpus 4-7 --container surefold-jdbc-0266 \
  --database surefold_bench_0266 --database-user postgres \
  --image-digest bench/surefold-jdbc-otel-bench/postgres-image.txt --idle-host-attested
```

The runner forces the qualified container's local endpoint, checks its identity,
and verifies database/cluster identity in JDBC before warm-up. It runs ten alternating
baseline/variant pairs for disabled, no-op, sampled-out and recording modes: 80 fresh
JVMs, each with 30 seconds warm-up and 60 seconds measurement. Allow over two hours.
Only a zero exit with all four modes passing both one-sided 95% upper bounds at <=2%
is a pass. Inconclusive, failed, invalid and incomplete results do not pass.

Retain and publish every output directory, including failed/interrupted attempts:
host/database metadata, per-run attempt records, streamed stdout/stderr, raw results
and `summary.json`. CPU is process CPU time; allocations are the JDK's approximate
all-thread heap-allocation counter during measurement. No span list is retained.

Facade, repeated/distinct SQL and batch diagnostics are separate from this gate:
`./gradlew :surefold-jdbc-otel:test --tests '*DiagnosticsSuite' -Psurefold.jdbc.diagnostics=true`.
After preserving evidence, remove only this disposable container with
`docker rm --force --volumes surefold-jdbc-0266`.
