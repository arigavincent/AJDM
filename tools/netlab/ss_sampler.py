"""Client-side view of a real download: samples `ss -tin` for TCP connections to the server every interval.

Used by real_run.py. No privileges needed; it sees every local socket to the server's addresses, so run one
download at a time. summarize() turns samples into startup, parallelism, per-connection rate and tail figures.
"""
import re
import socket
import subprocess
import threading
import time
from urllib.parse import urlsplit

FIELD = {name: re.compile(name + r":([0-9.]+)") for name in ("bytes_received", "segs_in", "lastrcv", "rcv_rtt", "minrtt")}
RTT = re.compile(r"\brtt:([0-9.]+)/")


def addresses(url):
    host = urlsplit(url).hostname
    return {info[4][0] for info in socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)}


class Sampler:
    def __init__(self, url, interval=0.5):
        self.addresses, self.interval = addresses(url), interval
        self.samples, self._stop = [], threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self):
        self.started = time.monotonic()
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        self._thread.join()
        self.finished = time.monotonic()

    def _run(self):
        while not self._stop.is_set():
            now = time.monotonic() - self.started
            text = subprocess.run(["ss", "-tinH", "state", "established"], capture_output=True, text=True).stdout
            lines = text.splitlines()
            sockets = {}
            for i, line in enumerate(lines):
                parts = line.split()
                if len(parts) < 4 or line[:1].isspace():
                    continue
                peer = parts[3].rsplit(":", 1)[0].strip("[]")
                if peer.startswith("::ffff:"):
                    peer = peer[7:]
                if peer not in self.addresses or i + 1 >= len(lines):
                    continue
                info = lines[i + 1]
                values = {k: float(m.group(1)) for k, p in FIELD.items() if (m := p.search(info))}
                if (m := RTT.search(info)):
                    values["rtt"] = float(m.group(1))
                sockets[parts[2]] = values
            self.samples.append((now, sockets))
            self._stop.wait(self.interval)


def summarize(sampler, size):
    """Startup to first bytes, mean active connections, bytes seen on the wire versus file size, and the tail."""
    seen, first_byte, active_counts, last_total, rate_points = {}, None, [], 0, []
    for t, sockets in sampler.samples:
        active = 0
        for key, values in sockets.items():
            received = values.get("bytes_received", 0)
            if received > seen.get(key, 0):
                active += 1
            seen[key] = max(seen.get(key, 0), received)
        total = sum(seen.values())
        if first_byte is None and total > 0:
            first_byte = t
        active_counts.append(active)
        rate_points.append((t, total))
        last_total = total
    duration = (sampler.finished - sampler.started) if hasattr(sampler, "finished") else 0
    # Tail: time spent after 90% of the file had arrived.
    ninety = next((t for t, total in rate_points if total >= 0.9 * size), None)
    busy = [c for c in active_counts if c]
    rtts = [v["rtt"] for _, s in sampler.samples for v in s.values() if "rtt" in v]
    return dict(first_byte_s=round(first_byte, 2) if first_byte is not None else None,
                connections=len(seen), mean_active=round(sum(busy) / len(busy), 1) if busy else 0,
                wire_bytes=int(last_total), overhead_pct=round(100 * (last_total - size) / size, 1) if size else None,
                tail_after_90pct_s=round(duration - ninety, 2) if ninety is not None else None,
                median_rtt_ms=sorted(rtts)[len(rtts) // 2] if rtts else None)
