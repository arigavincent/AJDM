#!/usr/bin/env python3
"""Read-only AJDM download monitor; stopping this client never stops the download."""
import argparse
import collections
import datetime
import json
import math
import subprocess
import time
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("job", type=uuid.UUID)
    parser.add_argument("--socket", required=True)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--samples", type=int, default=0, help="Stop after this many readings; 0 monitors until completion.")
    args = parser.parse_args()
    if not math.isfinite(args.interval) or args.interval < 0.25 or args.samples < 0:
        parser.error("interval must be at least 0.25 seconds; samples must be nonnegative")
    project = Path(__file__).resolve().parent.parent
    executable = project / "ajdm-cli/build/install/ajdm-resume8/bin/ajdm"
    if not executable.is_file():
        executable = project / "ajdm-cli/build/install/ajdm/bin/ajdm"
    command = [str(executable), "--socket", args.socket, "list", "--json"]
    history = collections.deque(maxlen=6)
    readings = 0
    try:
        while True:
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            if result.returncode:
                print(result.stderr.strip() or "AJDM status query failed.")
                return 1
            row = next((r for r in json.loads(result.stdout)["downloads"] if r["id"] == str(args.job)), None)
            if row is None:
                print("Job not found on this socket.")
                return 1
            now = time.monotonic()
            done, total, state = row["writtenBytes"], row["totalBytes"], row["state"]
            if state != "DOWNLOADING" or history and done < history[-1][1]:
                history.clear()
            history.append((now, done))
            elapsed = now - history[0][0]
            speed = max(0, (done - history[0][1]) / elapsed) if elapsed and state == "DOWNLOADING" else 0
            percent = f"{100 * done / total:.1f}%" if total > 0 else "100.0%" if total == 0 and state == "COMPLETED" else "?"
            size = f"{total / 1048576:.1f}" if total >= 0 else "?"
            eta = str(datetime.timedelta(seconds=math.ceil(max(0, total - done) / speed))) if total >= 0 and speed > 0 else "—"
            print(f"{state} | {done / 1048576:.1f}/{size} MiB ({percent}) | {speed / 1048576:.2f} MiB/s | ETA {eta}", flush=True)
            if row.get("error"):
                error = row["error"]
                print(f"{error.get('code', '')}: {error.get('message', '')} {error.get('action', '')}")
            readings += 1
            if state in {"COMPLETED", "FAILED", "CANCELED"} or args.samples and readings >= args.samples:
                return 0
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\nMonitor stopped; download continues.")
        return 0
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError):
        print("Could not read AJDM status. Check the daemon, socket and Java 25 JAVA_HOME.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
