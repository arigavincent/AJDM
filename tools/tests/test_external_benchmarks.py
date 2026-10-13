import csv
import importlib.util
import tempfile
import subprocess
import sys
import json
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("external_benchmarks", Path(__file__).parents[1] / "summarize_external_benchmarks.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ExternalBenchmarkTests(unittest.TestCase):
    def sample(self, mode, sequence, seconds):
        return {"run_id": f"run-{sequence}", "captured_at_utc": "2026-10-02T10:00:00Z", "host_id": "windows-test",
                "network": "4g-test", "source_id": "fixed-fixture", "comparison_id": "adaptive-idm", "cache_policy": "warm", "sequence": str(sequence),
                "mode": mode, "tool_version": "test-1.0", "bytes": "100", "sha256": "a" * 64, "seconds": str(seconds),
                "received_bytes": "110" if mode != "idm" else "", "process_cpu_seconds": "", "peak_rss_bytes": "", "verified": "true"}

    def write(self, rows):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "samples.csv"
        with path.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=MODULE.COLUMNS)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def test_verified_matched_runs_produce_correct_ratios_without_inventing_metrics(self):
        rows = [self.sample("adaptive", 1, 2), self.sample("idm", 2, 4), self.sample("idm", 3, 6), self.sample("adaptive", 4, 4)]
        groups = MODULE.summarize(MODULE.read_samples(self.write(rows)), minimum=2)
        self.assertEqual(1, len(groups))
        by_mode = groups[0]["modes"]
        self.assertEqual(3, by_mode["adaptive"]["median_seconds"])
        self.assertEqual(5, by_mode["idm"]["median_seconds"])
        self.assertAlmostEqual(5 / 3, by_mode["adaptive"]["idm_time_ratio"])
        self.assertIsNone(by_mode["idm"]["median_extra_received_bytes"])
        self.assertIsNone(by_mode["adaptive"]["median_process_cpu_seconds"])

    def test_unverified_wrong_hash_or_size_never_enters_a_comparison(self):
        for field, value in [("verified", "false"), ("sha256", "bad"), ("bytes", "0"), ("seconds", "NaN"),
                             ("seconds", "0"), ("received_bytes", "99")]:
            row = self.sample("adaptive", 1, 2)
            row[field] = value
            with self.subTest(field=field, value=value):
                with self.assertRaises(ValueError):
                    MODULE.read_samples(self.write([row]))
        rows = [self.sample("adaptive", 1, 2), self.sample("idm", 2, 4)]
        rows[1]["sha256"] = "b" * 64
        with self.assertRaises(ValueError):
            MODULE.summarize(MODULE.read_samples(self.write(rows)), minimum=1)

    def test_context_differences_insufficient_samples_and_duplicate_order_are_rejected(self):
        rows = [self.sample("adaptive", 1, 2), self.sample("idm", 2, 4)]
        with self.assertRaises(ValueError):
            MODULE.summarize(MODULE.read_samples(self.write(rows)))
        rows[1]["host_id"] = "other-host"
        with self.assertRaises(ValueError):
            MODULE.summarize(MODULE.read_samples(self.write(rows)), minimum=1)
        rows[1]["host_id"] = rows[0]["host_id"]
        rows[1]["sequence"] = rows[0]["sequence"]
        with self.assertRaises(ValueError):
            MODULE.summarize(MODULE.read_samples(self.write(rows)), minimum=1)

    def test_biased_pairs_changed_versions_and_unbounded_numbers_are_rejected(self):
        rows = [self.sample("adaptive", 1, 2), self.sample("idm", 2, 4), self.sample("adaptive", 3, 3), self.sample("idm", 4, 5)]
        with self.assertRaises(ValueError):
            MODULE.summarize(MODULE.read_samples(self.write(rows)), minimum=2)
        rows[2]["sequence"], rows[3]["sequence"] = "4", "3"
        rows[2]["tool_version"] = "changed-version"
        with self.assertRaises(ValueError):
            MODULE.summarize(MODULE.read_samples(self.write(rows)), minimum=2)
        row = self.sample("adaptive", 1, 2)
        row["bytes"] = "1" + "0" * 100
        with self.assertRaises(ValueError):
            MODULE.read_samples(self.write([row]))

    def test_command_imports_twenty_operator_reported_samples_and_preserves_output(self):
        rows = []
        for pair in range(10):
            modes = ["adaptive", "idm"] if pair % 2 == 0 else ["idm", "adaptive"]
            for index, mode in enumerate(modes):
                rows.append(self.sample(mode, pair * 2 + index + 1, 2 if mode == "adaptive" else 4))
        source = self.write(rows)
        output = source.parent / "report"
        command = [sys.executable, str(Path(MODULE.__file__)), str(source), "--output", str(output)]
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        summary = json.loads((output / "summary.json").read_text())
        self.assertEqual(10, summary["comparisons"][0]["modes"]["adaptive"]["samples"])
        self.assertIsNone(summary["comparisons"][0]["modes"]["idm"]["median_peak_rss_bytes"])
        before = (output / "report.md").read_text()
        self.assertNotEqual(0, subprocess.run(command, capture_output=True, check=False).returncode)
        self.assertEqual(before, (output / "report.md").read_text())


if __name__ == "__main__":
    unittest.main()
