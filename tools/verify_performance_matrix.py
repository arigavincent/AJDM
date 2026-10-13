"""Independently audit matrix artifacts and write a portable verification summary."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path


def rows(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(65536):
            value.update(data)
    return value.hexdigest()


def verify(directory):
    directory = Path(directory)
    profiles = {row["scenario"]: row for row in rows(directory / "profiles.csv")}
    samples = rows(directory / "samples.csv")
    if "Status: complete." not in (directory / "report.md").read_text() or not samples:
        raise ValueError("Matrix is incomplete")
    expected = {}
    groups = {}
    verified = []
    for row in samples:
        name = f"{row['scenario']}-{row['trial']}-{row['mode']}"
        if row["scenario"] not in profiles or row["mode"] not in ("single", "fixed", "adaptive"):
            raise ValueError("Unknown measured profile or mode")
        if not math.isfinite(float(row["seconds"])) or float(row["seconds"]) <= 0:
            raise ValueError("Invalid measured completion time")
        size = int(row["bytes"])
        if size != int(profiles[row["scenario"]]["bytes"]):
            raise ValueError("Profile/file size disagreement")
        if size not in expected:
            expected[size] = hashlib.sha256(bytes((index * 31 + index // 17) & 255 for index in range(size))).hexdigest()
        target = directory / (name + ".bin")
        actual = digest(target)
        if target.stat().st_size != size or actual != row["sha256"] or actual != expected[size]:
            raise ValueError("Independent file hash verification failed")
        if int(row["received_bytes"]) < size or int(row["extra_received_bytes"]) != int(row["received_bytes"]) - size:
            raise ValueError("Received byte accounting disagreement")
        requests = rows(directory / (name + "-requests.csv"))
        if {int(item["ordinal"]) for item in requests} != set(range(1, int(row["payload_requests"]) + 1)):
            raise ValueError("Payload request ledger is incomplete")
        if sum(int(item["offered_bytes"]) for item in requests) != int(row["offered_payload_bytes"]):
            raise ValueError("Offered byte accounting disagreement")
        if sum(item["intentional_drop"] == "true" for item in requests) != int(row["dropped_bodies"]):
            raise ValueError("Drop counter disagreement")
        configured_drops = {int(value) for value in profiles[row["scenario"]]["dropped_response_ordinals"].split(";") if value}
        observed_drops = {int(item["ordinal"]) for item in requests if item["intentional_drop"] == "true"}
        if observed_drops != configured_drops.intersection(range(1, int(row["payload_requests"]) + 1)):
            raise ValueError("Drop pattern does not match profile")
        intervals = []
        for request in requests:
            start, end, offered = (int(request[field]) for field in ("start", "end_exclusive", "offered_bytes"))
            if not 0 <= start < end <= size or not 0 <= offered <= end - start:
                raise ValueError("Request boundaries outside representation")
            if offered:
                intervals.append((start, start + offered))
        end = 0
        for start, to in sorted(intervals):
            if start > end:
                raise ValueError("Server request ledger contains a gap")
            end = max(end, to)
        if end != size:
            raise ValueError("Request ledger does not cover the output")
        telemetry = rows(directory / (name + "-telemetry.csv"))
        if len(telemetry) != int(row["telemetry_samples"]) or telemetry[-1]["state"] != "COMPLETED":
            raise ValueError("Telemetry does not settle at completion")
        for field, column in [("heap_bytes", "sampled_heap_peak_bytes"), ("rss_bytes", "sampled_rss_peak_bytes"),
                              ("queued_write_bytes", "sampled_queue_peak_bytes")]:
            if max(int(item[field]) for item in telemetry) != int(row[column]):
                raise ValueError("Sampled peak disagrees with telemetry")
        if max(max(0, int(item["written_bytes"]) - int(item["durable_bytes"])) for item in telemetry) != int(row["sampled_durability_lag_peak_bytes"]):
            raise ValueError("Durability-lag peak disagrees with telemetry")
        if any(int(item["queued_write_bytes"]) < 0 or int(item["queued_write_bytes"]) > 16384 * 8 for item in telemetry):
            raise ValueError("Disk queue exceeded configured buffer bound")
        for field in ("elapsed_ms", "received_bytes"):
            values = [int(item[field]) for item in telemetry]
            if values != sorted(values):
                raise ValueError("Telemetry elapsed/received counter regressed")
        last = telemetry[-1]
        if int(last["written_bytes"]) != size or int(last["durable_bytes"]) != size or int(last["received_bytes"]) != int(row["received_bytes"]):
            raise ValueError("Final telemetry/file accounting disagreement")
        key = (row["scenario"], row["mode"])
        group = groups.setdefault(key, [])
        if any(item["trial"] == row["trial"] for item in group):
            raise ValueError("Duplicate mode/trial")
        group.append(row)
        verified.append({"file": target.name, "bytes": size, "sha256": actual, "extra_received_bytes": int(row["extra_received_bytes"])})
    rounds = {len(group) for group in groups.values()}
    if len(rounds) != 1 or len(groups) != len(profiles) * 3:
        raise ValueError("Matrix is missing a scenario or mode")
    permutations = [("single", "fixed", "adaptive"), ("fixed", "adaptive", "single"), ("adaptive", "single", "fixed"),
                    ("adaptive", "fixed", "single"), ("fixed", "single", "adaptive"), ("single", "adaptive", "fixed")]
    for name in profiles:
        for trial in range(1, next(iter(rounds)) + 1):
            run = [row for row in samples if row["scenario"] == name and int(row["trial"]) == trial]
            if len(run) != 3 or {int(row["order"]) for row in run} != {1, 2, 3}:
                raise ValueError("Mode ordering ledger is incomplete")
            if tuple(row["mode"] for row in sorted(run, key=lambda item: int(item["order"]))) != permutations[(trial - 1) % 6]:
                raise ValueError("Mode order does not follow the balanced schedule")
    warmups = sorted(directory.glob("warmup-0-*.bin"))
    warmup_size = 512 * 1024 + 17
    warmup_hash = hashlib.sha256(bytes((index * 31 + index // 17) & 255 for index in range(warmup_size))).hexdigest()
    if len(warmups) != 3 or any(file.stat().st_size != warmup_size or digest(file) != warmup_hash for file in warmups):
        raise ValueError("Warmup output verification failed")
    return {"verified_measured_outputs": len(verified), "verified_warmups": len(warmups), "rounds_per_mode_per_scenario": next(iter(rounds)),
            "total_measured_bytes": sum(item["bytes"] for item in verified), "all_extra_received_bytes": sum(item["extra_received_bytes"] for item in verified),
            "checks": ["independent deterministic source hash and output rehash", "file/profile size", "received/offered bytes",
                       "request/drop ledger and coverage", "final telemetry and observed peaks", "disk buffer bound", "mode/round completeness"],
            "outputs": verified}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        summary = verify(args.directory)
        (args.directory / "verification.json").write_text(json.dumps(summary, indent=2) + "\n")
        (args.directory / "verification.md").write_text(
            f"# Independent matrix verification\n\nVerified {summary['verified_measured_outputs']} measured outputs and three warmup files. "
            f"Measured bytes: {summary['total_measured_bytes']:,}. Extra received payload bytes: {summary['all_extra_received_bytes']:,}.\n\n"
            + "\n".join("- " + check for check in summary["checks"]) + "\n\nFull file/hash evidence is retained in [verification.json](verification.json).\n")
        print(f"Verified {summary['verified_measured_outputs']} measured outputs and {summary['verified_warmups']} warmups")
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
