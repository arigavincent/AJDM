"""Merge verified same-context external timing samples; never operate another downloader."""
import argparse
import csv
import json
import math
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

COLUMNS = ["run_id", "captured_at_utc", "host_id", "network", "source_id", "comparison_id", "cache_policy", "sequence", "mode",
           "tool_version", "bytes", "sha256", "seconds", "received_bytes", "process_cpu_seconds", "peak_rss_bytes", "verified"]
CONTEXT = ("host_id", "network", "source_id", "comparison_id", "cache_policy")


def _number(value, field, integer=False, optional=False, positive=False):
    if optional and value == "":
        return None
    try:
        result = int(value) if integer else float(value)
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError(f"Invalid numeric field: {field}") from error
    if integer and result > 2**63 - 1:
        raise ValueError(f"Numeric field exceeds supported range: {field}")
    if not math.isfinite(result) or result < 0 or positive and result == 0:
        raise ValueError(f"Invalid numeric field: {field}")
    return result


def read_samples(path):
    samples = []
    with Path(path).open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != COLUMNS:
            raise ValueError("CSV schema does not match the supplied external template")
        for raw in reader:
            if None in raw or any(value is None for value in raw.values()):
                raise ValueError("CSV row does not match the supplied schema")
            row = {key: value.strip() for key, value in raw.items()}
            for field in ("run_id", "host_id", "network", "source_id", "comparison_id"):
                if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", row[field]):
                    raise ValueError(f"Expected a non-secret label for {field}")
            if row["mode"] not in ("single", "fixed", "adaptive", "idm") or row["cache_policy"] not in ("cold", "warm"):
                raise ValueError("Invalid mode or cache policy")
            if not re.fullmatch(r"[A-Za-z0-9 ._()+/-]{1,80}", row["tool_version"]):
                raise ValueError("Missing or invalid tool version")
            try:
                stamp = datetime.fromisoformat(row["captured_at_utc"].replace("Z", "+00:00"))
                if stamp.tzinfo is None or stamp.utcoffset().total_seconds() != 0:
                    raise ValueError()
            except (ValueError, AttributeError) as error:
                raise ValueError("Expected a UTC capture timestamp") from error
            if row["verified"] != "true" or not re.fullmatch(r"[a-fA-F0-9]{64}", row["sha256"]):
                raise ValueError("Every result must have verified bytes and a full SHA-256")
            row["sha256"] = row["sha256"].lower()
            for field in ("bytes", "sequence"):
                row[field] = _number(row[field], field, integer=True, positive=True)
            row["seconds"] = _number(row["seconds"], "seconds", positive=True)
            row["received_bytes"] = _number(row["received_bytes"], "received_bytes", integer=True, optional=True)
            row["peak_rss_bytes"] = _number(row["peak_rss_bytes"], "peak_rss_bytes", integer=True, optional=True)
            row["process_cpu_seconds"] = _number(row["process_cpu_seconds"], "process_cpu_seconds", optional=True)
            if row["received_bytes"] is not None and row["received_bytes"] < row["bytes"]:
                raise ValueError("Received payload cannot be smaller than the verified file")
            samples.append(row)
    if not samples:
        raise ValueError("No measured samples supplied")
    return samples


def percentile(values, quantile):
    values = sorted(values)
    rank = (len(values) - 1) * quantile
    lower = math.floor(rank)
    upper = math.ceil(rank)
    return values[lower] * (1 - (rank - lower)) + values[upper] * (rank - lower)


def summarize(samples, minimum=10):
    if not isinstance(minimum, int) or minimum < 1 or not samples:
        raise ValueError("Measured samples and a positive minimum are required")
    grouped = defaultdict(list)
    seen = set()
    for row in samples:
        if row["run_id"] in seen:
            raise ValueError("Duplicate run identifier")
        seen.add(row["run_id"])
        grouped[tuple(row[field] for field in CONTEXT)].append(row)
    result = []
    for context, rows in sorted(grouped.items()):
        if len({(row["bytes"], row["sha256"]) for row in rows}) != 1:
            raise ValueError("Compared samples must verify the same file size and SHA-256")
        modes = {row["mode"] for row in rows}
        if "idm" not in modes or len(modes) != 2:
            raise ValueError("Each comparison must pair exactly one AJDM mode with IDM on the same host/network/source/cache")
        if len({row["sequence"] for row in rows}) != len(rows):
            raise ValueError("Duplicate sequence in a comparison")
        by_mode = {mode: [row for row in rows if row["mode"] == mode] for mode in modes}
        if any(len(values) < minimum for values in by_mode.values()) or len({len(values) for values in by_mode.values()}) != 1:
            raise ValueError(f"Each tool needs equal counts and at least {minimum} verified samples per comparison")
        # AB/BA pairs balance drift while keeping adjacent comparisons on the same source/network.
        ordered = sorted(rows, key=lambda row: row["sequence"])
        first = defaultdict(int)
        for index in range(0, len(ordered), 2):
            pair = ordered[index:index + 2]
            if {row["mode"] for row in pair} != modes:
                raise ValueError("Run ordering must use adjacent AJDM/IDM pairs")
            first[pair[0]["mode"]] += 1
        if abs(first["idm"] - first[next(mode for mode in modes if mode != "idm")]) > 1:
            raise ValueError("Balance AJDM-first and IDM-first pairs")
        if any(len({row["tool_version"] for row in values}) != 1 for values in by_mode.values()):
            raise ValueError("Tool versions must remain constant within a comparison")
        idm_median = percentile([row["seconds"] for row in by_mode["idm"]], .5)
        summary = {**dict(zip(CONTEXT, context)), "bytes": rows[0]["bytes"], "sha256": rows[0]["sha256"], "modes": {}}
        for mode, values in sorted(by_mode.items()):
            times = [row["seconds"] for row in values]
            extra = [row["received_bytes"] - row["bytes"] for row in values if row["received_bytes"] is not None]
            cpu = [row["process_cpu_seconds"] for row in values if row["process_cpu_seconds"] is not None]
            rss = [row["peak_rss_bytes"] for row in values if row["peak_rss_bytes"] is not None]
            summary["modes"][mode] = {
                "samples": len(values), "tool_version": values[0]["tool_version"], "median_seconds": percentile(times, .5),
                "p10_seconds": percentile(times, .1), "p90_seconds": percentile(times, .9),
                "idm_time_ratio": idm_median / percentile(times, .5),
                "median_extra_received_bytes": percentile(extra, .5) if len(extra) == len(values) else None,
                "median_process_cpu_seconds": percentile(cpu, .5) if len(cpu) == len(values) else None,
                "median_peak_rss_bytes": percentile(rss, .5) if len(rss) == len(values) else None,
            }
        result.append(summary)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_files", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New report directory")
    args = parser.parse_args()
    try:
        samples = [row for path in args.csv_files for row in read_samples(path)]
        groups = summarize(samples)
        args.output.mkdir(parents=True, exist_ok=False)
        metadata = {"recorded_at_utc": datetime.now(timezone.utc).isoformat(), "minimum_per_tool": 10, "comparisons": groups,
                    "validation_limit": "Caller-reported conditions and verification; importer cannot independently observe IDM or mobile radio state."}
        (args.output / "summary.json").write_text(json.dumps(metadata, indent=2) + "\n")
        text = ["# External AJDM/IDM comparisons", "", "Only matched, verified, balanced comparisons with at least ten samples per tool are included.", "",
                "| Host / network / source / cache / comparison | Mode | n | Median s | p10 s | p90 s | IDM/tool median time ratio |",
                "|---|---|---:|---:|---:|---:|---:|"]
        for group in groups:
            context = " / ".join(group[field] for field in CONTEXT)
            for mode, stats in group["modes"].items():
                text.append(f"| {context} | {mode} | {stats['samples']} | {stats['median_seconds']:.3f} | {stats['p10_seconds']:.3f} | {stats['p90_seconds']:.3f} | {stats['idm_time_ratio']:.3f}x |")
        text += ["", "Ratios above 1 mean that tool had a lower median completion time than IDM. Missing resource metrics remain null in summary.json.",
                 "Conditions and hash verification are supplied by the operator; this importer cannot verify radio state, timers, cache flushing, or independence of runs.",
                 "Local synthetic importer tests are not measurements of IDM or a mobile network."]
        (args.output / "report.md").write_text("\n".join(text) + "\n")
        print(args.output / "report.md")
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
