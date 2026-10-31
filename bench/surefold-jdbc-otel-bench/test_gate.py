"""Synthetic and mocked checks; never starts Docker, JDBC or a benchmark workload."""
import json
from pathlib import Path
import random
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import gate


class BootstrapTests(unittest.TestCase):
    def test_ten_identical_pairs_pass_both_thresholds(self):
        result = gate.bootstrap(pairs())
        self.assertTrue(result["pass"])
        self.assertEqual(result["throughput_loss_upper95"], 0)
        self.assertEqual(result["p95_increase_upper95"], 0)

    def test_three_percent_throughput_loss_fails(self):
        result = gate.bootstrap(pairs(throughput=97))
        self.assertFalse(result["pass"])
        self.assertAlmostEqual(result["throughput_loss_upper95"], 3)
        self.assertEqual(result["p95_increase_upper95"], 0)

    def test_three_percent_p95_increase_fails(self):
        result = gate.bootstrap(pairs(p95=103))
        self.assertFalse(result["pass"])
        self.assertEqual(result["throughput_loss_upper95"], 0)
        self.assertAlmostEqual(result["p95_increase_upper95"], 3)

    def test_zero_mean_with_wide_uncertainty_does_not_pass(self):
        observations = [pair(throughput=100 - delta, p95=100 + delta) for delta in [-20, 20] * 5]
        result = gate.bootstrap(observations)
        self.assertAlmostEqual(result["throughput_loss_mean"], 0)
        self.assertAlmostEqual(result["p95_increase_mean"], 0)
        self.assertGreater(result["throughput_loss_upper95"], 2)
        self.assertGreater(result["p95_increase_upper95"], 2)
        self.assertFalse(result["pass"])

    def test_fewer_than_ten_pairs_cannot_pass(self):
        for count in (1, 9):
            with self.subTest(count=count):
                result = gate.bootstrap(pairs(count=count))
                self.assertEqual(result["pairs"], count)
                self.assertFalse(result["pass"])

    def test_bootstrap_uses_the_fixed_seed_and_is_repeatable(self):
        observations = [pair(throughput=98 + index, p95=95 + index) for index in range(10)]
        with mock.patch.object(gate.random, "Random", wraps=random.Random) as seeded:
            first = gate.bootstrap(observations)
            second = gate.bootstrap(observations)
        self.assertEqual(first, second)
        self.assertEqual(seeded.call_args_list, [mock.call(266), mock.call(266)])


class QualificationTests(unittest.TestCase):
    def test_wrong_os_rejects_before_any_docker_or_database_process(self):
        with mock.patch.object(gate.platform, "system", return_value="Darwin"), \
                mock.patch.object(gate, "command") as command, \
                mock.patch.object(gate.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "Linux amd64"):
                gate.qualify(SimpleNamespace())
        command.assert_not_called()
        process.assert_not_called()


class AttemptRetentionTests(unittest.TestCase):
    def test_failed_workload_keeps_metadata_and_partial_output_and_aborts(self):
        self.check_attempt("failure", "failed", RuntimeError)

    def test_timed_out_workload_keeps_metadata_and_partial_output_and_aborts(self):
        self.check_attempt("timeout", "timeout", RuntimeError)

    def test_interruption_keeps_the_already_persisted_incomplete_attempt(self):
        self.check_attempt("interrupt", "incomplete", KeyboardInterrupt)

    def test_successful_child_with_wrong_mode_is_retained_as_failed(self):
        self.check_attempt("invalid-mode", "failed", ValueError)

    def test_successful_child_with_infinite_metric_is_retained_as_failed(self):
        self.check_attempt("invalid-metric", "failed", ValueError)

    def test_failed_reset_is_retained_without_launching_the_workload(self):
        self.check_attempt("reset-failure", "failed", RuntimeError)

    def check_attempt(self, outcome, expected_status, expected_failure):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "attempt"
            args = options(destination)
            name = "disabled-00-original"
            attempt_file = destination / f"{name}.attempt.json"
            observed_launches = []
            manifest = {"container_id": "qualified-container", "postgres_host_port": 54320}
            identity = {"database": args.database, "version": "170006", "system_identifier": "266"}

            def command(argv, **kwargs):
                if argv[:2] == ["docker", "exec"]:
                    return json.dumps(identity)
                if argv[:2] == ["docker", "inspect"]:
                    return manifest["container_id"]
                self.fail(f"unexpected external command: {argv}")

            def process(argv, **kwargs):
                record = json.loads(attempt_file.read_text())
                self.assertEqual(record["status"], "incomplete")
                self.assertEqual(kwargs["timeout"], 180)
                self.assertFalse(kwargs.get("capture_output", False))
                self.assertTrue(kwargs["stdout"].writable())
                self.assertTrue(kwargs["stderr"].writable())
                observed_launches.append(argv[0])
                if argv[0] == "docker":
                    self.assertEqual(record["phase"], "reset")
                    kwargs["stdout"].write("reset output\n")
                    kwargs["stderr"].write("reset diagnostic\n")
                    return subprocess.CompletedProcess(argv, 4 if outcome == "reset-failure" else 0)
                self.assertEqual(argv[0], "taskset")
                self.assertEqual(record["phase"], "workload")
                self.assertEqual(record["command"], argv)
                environment = kwargs["env"]
                self.assertEqual(environment["SUREFOLD_BENCH_JDBC_URL"],
                                 "jdbc:postgresql://127.0.0.1:54320/bench%2Fisolated%3Fname")
                self.assertEqual(environment["SUREFOLD_BENCH_EXPECTED_SYSTEM_IDENTIFIER"], "266")
                self.assertEqual(environment["PGUSER"], args.database_user)
                kwargs["stdout"].write("partial workload output\n")
                kwargs["stderr"].write("partial workload diagnostic\n")
                if outcome == "timeout":
                    raise subprocess.TimeoutExpired(argv, 180)
                if outcome == "interrupt":
                    raise KeyboardInterrupt()
                if outcome in ("invalid-mode", "invalid-metric"):
                    value = dict(valid_result(), **({"mode": "recording"} if outcome == "invalid-mode"
                                                   else {"throughput": float("inf")}))
                    Path(argv[-1]).write_text(json.dumps(value))
                    return subprocess.CompletedProcess(argv, 0)
                return subprocess.CompletedProcess(argv, 17)

            with mock.patch.object(gate, "qualify", return_value=manifest), \
                    mock.patch.object(gate, "command", side_effect=command), \
                    mock.patch.object(gate.subprocess, "run", side_effect=process), \
                    mock.patch.dict(gate.os.environ, {"SUREFOLD_BENCH_JDBC_URL": "jdbc:postgresql://wrong/wrong"}):
                with self.assertRaises(expected_failure):
                    gate.run(args)

            record = json.loads(attempt_file.read_text())
            self.assertEqual(record["status"], expected_status)
            self.assertIn("finished", record)
            self.assertEqual(len(list(destination.glob("*.attempt.json"))), 1)
            self.assertFalse((destination / "summary.json").exists())
            self.assertTrue((destination / "host.json").exists())
            self.assertTrue((destination / "database.json").exists())
            self.assertEqual((destination / f"{name}.reset.stdout").read_text(), "reset output\n")
            self.assertEqual((destination / f"{name}.reset.stderr").read_text(), "reset diagnostic\n")
            if outcome == "reset-failure":
                self.assertEqual(observed_launches, ["docker"])
                self.assertEqual(record["phase"], "reset")
                self.assertEqual(record["reset_exit_code"], 4)
                self.assertFalse((destination / f"{name}.stdout").exists())
            else:
                self.assertEqual(observed_launches, ["docker", "taskset"])
                self.assertEqual(record["phase"], "workload")
                self.assertEqual((destination / f"{name}.stdout").read_text(), "partial workload output\n")
                self.assertEqual((destination / f"{name}.stderr").read_text(), "partial workload diagnostic\n")
                if outcome == "failure":
                    self.assertEqual(record["exit_code"], 17)


def pair(throughput=100, p95=100):
    return ({"throughput": 100, "p95_nanos": 100}, {"throughput": throughput, "p95_nanos": p95})


def pairs(throughput=100, p95=100, count=10):
    return [pair(throughput, p95) for _ in range(count)]


def options(destination):
    return SimpleNamespace(output=str(destination), binary="/not-executed/installed-benchmark",
                           client_cpus="0-3", postgres_cpus="4-7", container="qualified",
                           database="bench/isolated?name", database_user="postgres",
                           image_digest="/not-read/postgres-image.txt", idle_host_attested=True)



class InvalidResultsTests(unittest.TestCase):
    def test_infinite_metrics_never_pass(self):
        with self.assertRaises(ValueError):
            gate.bootstrap(pairs(throughput=float("inf")))

    def test_wrong_mode_is_rejected(self):
        with self.assertRaises(ValueError):
            gate.validate_result(valid_result(), "recording")

    def test_invalid_protocol_results_are_rejected(self):
        invalid = {"throughput": float("inf"), "p95_nanos": 0, "elapsed_nanos": 1,
                   "flush_success": False, "exported": 1, "operations": True}
        for key, value in invalid.items():
            with self.subTest(field=key), self.assertRaises(ValueError):
                gate.validate_result(dict(valid_result(), **{key: value}), "original")

    def test_database_connection_strings_are_rejected_before_processes(self):
        for database in ("postgresql://remote/db", "postgres://remote/db", "host=remote dbname=db", ""):
            with self.subTest(database=database), \
                    mock.patch.object(gate.platform, "system", return_value="Linux"), \
                    mock.patch.object(gate.platform, "machine", return_value="x86_64"), \
                    mock.patch.dict(gate.os.environ, {}, clear=True), \
                    mock.patch.object(gate, "command") as command:
                with self.assertRaisesRegex(ValueError, "database name"):
                    gate.qualify(SimpleNamespace(database=database))
                command.assert_not_called()


def valid_result():
    return {"mode": "original", "operations": 6000, "elapsed_nanos": 60_000_000_000,
            "throughput": 100, "p50_nanos": 50, "p95_nanos": 100, "p99_nanos": 150,
            "process_cpu_nanos": 1000, "allocation_bytes_approx": 1000,
            "exported": 0, "expected_spans": 9000, "flush_success": True}

class ValidResultsTests(unittest.TestCase):
    def test_original_and_recording_results_are_accepted(self):
        value = valid_result()
        self.assertEqual(gate.validate_result(value, "original"), value)
        recording = dict(value, mode="recording", exported=value["expected_spans"])
        self.assertEqual(gate.validate_result(recording, "recording"), recording)


if __name__ == "__main__":
    unittest.main()
