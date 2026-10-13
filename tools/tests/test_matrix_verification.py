import csv
import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("matrix_verifier", Path(__file__).parents[1] / "verify_performance_matrix.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MatrixVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "report.md").write_text("Status: complete.")
        self.write_csv("profiles.csv", [{"scenario": "tiny", "bytes": 10, "dropped_response_ordinals": ""}])
        data = bytes((index * 31 + index // 17) & 255 for index in range(10))
        digest = hashlib.sha256(data).hexdigest()
        self.samples = []
        for order, mode in enumerate(["single", "fixed", "adaptive"], 1):
            name = f"tiny-1-{mode}"
            (self.root / (name + ".bin")).write_bytes(data)
            self.write_csv(name + "-requests.csv", [{"ordinal": 1, "start": 0, "end_exclusive": 10, "offered_bytes": 10, "intentional_drop": "false"}])
            self.write_csv(name + "-telemetry.csv", [{"elapsed_ms": 1, "state": "COMPLETED", "received_bytes": 10, "written_bytes": 10,
                                                     "durable_bytes": 10, "heap_bytes": 100, "rss_bytes": -1, "queued_write_bytes": 0}])
            self.samples.append({"scenario": "tiny", "trial": 1, "order": order, "mode": mode, "bytes": 10, "seconds": .001,
                                 "sha256": digest, "received_bytes": 10, "extra_received_bytes": 0, "payload_requests": 1,
                                 "offered_payload_bytes": 10, "dropped_bodies": 0, "telemetry_samples": 1, "sampled_heap_peak_bytes": 100,
                                 "sampled_rss_peak_bytes": -1, "sampled_queue_peak_bytes": 0, "sampled_durability_lag_peak_bytes": 0})
        self.write_csv("samples.csv", self.samples)
        warm = bytes((index * 31 + index // 17) & 255 for index in range(512 * 1024 + 17))
        for mode in ["single", "fixed", "adaptive"]:
            (self.root / f"warmup-0-{mode}.bin").write_bytes(warm)

    def write_csv(self, name, rows):
        with (self.root / name).open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)

    def test_correct_outputs_and_ledgers_are_independently_verified(self):
        summary = MODULE.verify(self.root)
        self.assertEqual(3, summary["verified_measured_outputs"])
        self.assertEqual(3, summary["verified_warmups"])
        self.assertEqual(0, summary["all_extra_received_bytes"])

    def test_corrupted_output_or_metric_disagreement_never_passes(self):
        (self.root / "tiny-1-single.bin").write_bytes(b"x" * 10)
        with self.assertRaises(ValueError):
            MODULE.verify(self.root)
        data = bytes((index * 31 + index // 17) & 255 for index in range(10))
        (self.root / "tiny-1-single.bin").write_bytes(data)
        self.samples[0]["sampled_queue_peak_bytes"] = 1
        self.write_csv("samples.csv", self.samples)
        with self.assertRaises(ValueError):
            MODULE.verify(self.root)


if __name__ == "__main__":
    unittest.main()
