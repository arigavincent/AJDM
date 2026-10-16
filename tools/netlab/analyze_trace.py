#!/usr/bin/env python3
"""Summarise a run_lab.py --trace log per client run: startup, connections, requests and tail.

  python3 tools/netlab/analyze_trace.py OUT/trace-PROFILE.log
"""
import re
import sys
from collections import defaultdict


def runs(path):
    current = None
    with open(path) as source:
        for line in source:
            parts = line.split()
            t, port, event = float(parts[0]), int(parts[1]), parts[2]
            if event == "BEGIN":
                current = dict(client=parts[3], trial=parts[4], begin=t, events=[])
            elif event == "FINISH" and current:
                current.update(finish=t, status=parts[5])
                yield current
                current = None
            elif current:
                current["events"].append((t, port, event, parts[3:]))


def describe(run):
    begin, events = run["begin"], run["events"]
    requests = [e for e in events if e[2] == "REQ"]
    payload = [e for e in requests if e[3] and e[3][0] == "GET" and e[3][1] != "bytes=0-0"]
    ends = [e for e in events if e[2] == "END"]
    ports = {e[1] for e in requests}
    per_port = defaultdict(list)
    for e in events:
        per_port[e[1]].append(e)
    # Idle gap on a kept-alive connection: END of one response to the next REQ on the same port.
    gaps = []
    for port_events in per_port.values():
        last_end = None
        for t, _, event, _ in port_events:
            if event == "END":
                last_end = t
            elif event == "REQ" and last_end is not None:
                gaps.append(t - last_end)
                last_end = None
    total = run["finish"] - begin
    first_payload = payload[0][0] - begin if payload else float("nan")
    # Tail: from the last payload request to the end of the run, a proxy for how long the end of the file dragged.
    last_payload = payload[-1][0] - begin if payload else float("nan")
    head = sum(1 for e in requests if e[3] and e[3][0] == "HEAD")
    probe = sum(1 for e in requests if e[3] and e[3][1] == "bytes=0-0")
    rejects = sum(1 for e in events if e[2] == "REJECT")
    return (f"{run['client']:17s} total {total:6.2f}s  first-payload {first_payload:5.2f}s  conns {len(ports):3d}  "
            f"payload-reqs {len(payload):4d}  head {head} probe {probe} rejects {rejects}  "
            f"keepalive-gaps {len(gaps):3d} (sum {sum(gaps):5.2f}s)  last-req-at {last_payload:6.2f}s  "
            f"tail {total - last_payload:5.2f}s")


def main():
    for path in sys.argv[1:]:
        print(path)
        for run in runs(path):
            print("  " + describe(run))


if __name__ == "__main__":
    main()
