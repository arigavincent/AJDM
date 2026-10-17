#!/usr/bin/env python3
"""AJDM emulated-network lab: real kernel TCP under netem inside an unprivileged network namespace.

Every client downloads the same file from the same Range server over the same shaped link; each output is
SHA-256 verified. Client order rotates per trial. Nothing here touches the host network or needs root.

  python3 tools/netlab/run_lab.py --out DIR [--profiles a,b] [--clients x,y] [--trials N]

AJDM runs from `./gradlew :ajdm-bench:installDist` output with Java 25 (set JAVA_HOME).
These are emulations: they show behaviour under loss/latency/throttling, not results on a real carrier or vs IDM.
"""
import argparse
import csv
import hashlib
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MiB = 1024 * 1024

# netem delay is applied on each pass through loopback, so RTT is about twice `delay`.
PROFILES = {
    "good-5g":        dict(delay="8ms",   jitter="2ms",  loss="0.05%", rate="150mbit", size=256, desc="Good 5G: ~16 ms RTT, 150 Mbit/s, negligible loss"),
    "typical-4g":     dict(delay="25ms",  jitter="5ms",  loss="0.5%",  rate="30mbit",  size=96,  desc="Typical 4G: ~50 ms RTT, 30 Mbit/s, 0.5% loss"),
    "lossy-4g":       dict(delay="40ms",  jitter="10ms", loss="2%",    rate="20mbit",  size=48,  desc="Lossy 4G cell edge: ~80 ms RTT, 20 Mbit/s, 2% loss"),
    "bad-3g":         dict(delay="120ms", jitter="20ms", loss="3%",    rate="3mbit",   size=12,  desc="Very poor 3G/EDGE-like: ~240 ms RTT, 3 Mbit/s, 3% loss"),
    "throttled-host": dict(delay="25ms",  jitter="5ms",  loss="0.3%",  rate="30mbit",  size=48,  per_conn_rate=150 * 1024,
                           desc="4G link to a host that throttles each connection to 150 KiB/s"),
    "capped-host":    dict(delay="25ms",  jitter="5ms",  loss="0.3%",  rate="30mbit",  size=48,  per_conn_rate=300 * 1024, max_conns=4,
                           desc="Host allowing 4 connections at 300 KiB/s each; extra connections get 503"),
    "flapping-4g":    dict(delay="25ms",  jitter="5ms",  loss="0.5%",  rate="20mbit",  size=48,  drop_prob=0.05, blackout=(20, 6),
                           desc="4G with 6 s total outages every 20 s and 5% of responses cut mid-body"),
}

CLIENTS = ["curl-1", "aria2-8", "aria2-16", "ajdm-fixed-8", "ajdm-fixed-16", "ajdm-adaptive-16"]


def sh(*cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def netem(profile, override_loss=None, change=False):
    loss = override_loss or profile["loss"]
    sh("tc", "qdisc", "change" if change else "replace", "dev", "lo", "root", "netem",
       "delay", profile["delay"], profile["jitter"], "loss", loss, "rate", profile["rate"], "limit", "10000")


def blackout_loop(profile, stop):
    period, length = profile["blackout"]
    while not stop.wait(period - length):
        netem(profile, override_loss="100%", change=True)
        if stop.wait(length):
            break
        netem(profile, change=True)
    netem(profile, change=True)


def payload(work, size_mib):
    path = work / f"payload-{size_mib}MiB.bin"
    if not path.exists():
        rng = os.urandom
        with open(path, "wb") as out:
            for _ in range(size_mib):
                out.write(rng(MiB))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return path, digest


baseline_classpath = ""


def jars(directory):
    return os.pathsep.join(str(p) for p in sorted(Path(directory).glob("*.jar")))


def command(client, url, target, digest, java, classpath):
    client, _, variant = client.partition("@")
    name, _, count = client.rpartition("-")
    if client == "curl-1":
        return ["curl", "-sS", "--retry", "50", "--retry-all-errors", "--retry-delay", "1", "-C", "-", "-o", str(target), url]
    if name == "aria2":
        return ["aria2c", "-q", f"-x{count}", f"-s{count}", "-k1M", "--file-allocation=none", "--max-tries=0",
                "--retry-wait=1", "--connect-timeout=10", "--timeout=15", "--auto-file-renaming=false",
                "-d", str(target.parent), "-o", target.name, url]
    mode = "adaptive" if "adaptive" in name else "fixed"
    if name.startswith("base-"):
        classpath = baseline_classpath
    # "@wN" caps each request window at N MiB, for window-size experiments.
    window = [f"-Dajdm.lab.windowMiB={variant[1:]}"] if variant.startswith("w") else []
    extra = os.environ.get("AJDM_LAB_JVM_OPTS", "").split()  # e.g. -Dajdm.trace=FILE for real-link diagnosis
    return [java, "-Dajdm.hostLearning.disabled=true", *window, *extra, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
            "io.ajdm.bench.LabDownload", mode, count, url, str(target), digest]


class Resident:
    """One long-lived AJDM engine, like the product core, fed one download per line."""

    def __init__(self, cmd):
        self.process = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        text=True, bufsize=1)
        self.lines = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()
        if self.lines.get(timeout=60) != "LAB_READY":
            raise RuntimeError("resident AJDM did not start")

    def _pump(self):
        for line in self.process.stdout:
            self.lines.put(line.strip())
        self.lines.put(None)

    def run(self, request, timeout):
        self.process.stdin.write(request + "\n")
        self.process.stdin.flush()
        line = self.lines.get(timeout=timeout)
        if line is None:
            raise RuntimeError("resident AJDM exited")
        return line

    def close(self):
        self.process.kill()
        self.process.wait()


residents = {}
IDM_PREFIX = os.environ.get("AJDM_IDM_PREFIX", str(Path.home() / "Documents" / "AJDM-idm" / "prefix"))
IDM_EXE = "C:\\Program Files (x86)\\Internet Download Manager\\IDMan.exe"
idm_runs = 0


def run_idm(client, url, target, digest, size, timeout):
    """IDM under Wine: resident app restarted with the requested connection count, fed via its /d command line."""
    global idm_runs
    idm_runs += 1
    env = dict(os.environ, WINEPREFIX=IDM_PREFIX, WINEDEBUG="-all")
    quiet = dict(stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
    subprocess.run(["wineserver", "-k"], **quiet)
    subprocess.run(["wine", "reg", "add", "HKCU\\Software\\DownloadManager", "/v", "MaxConnectionsNumber",
                    "/t", "REG_DWORD", "/d", client.rpartition("-")[2], "/f"], **quiet)
    subprocess.Popen(["wine", IDM_EXE], **quiet)
    time.sleep(8)  # Let the resident app settle; not part of the measured download.
    name = f"{target.stem}-{idm_runs}{target.suffix}"
    final = target.with_name(name)
    final.unlink(missing_ok=True)
    started = time.monotonic()
    # A unique query keeps IDM from treating repeated runs as duplicate downloads; the server ignores it.
    subprocess.run(["wine", IDM_EXE, "/d", f"{url}?run={idm_runs}", "/p", "Z:" + str(target.parent), "/f", name, "/n"], **quiet)
    status, seen = "timeout", None
    while time.monotonic() - started < timeout:
        if final.exists() and final.stat().st_size == size:
            stamp = (final.stat().st_size, final.stat().st_mtime_ns)
            if stamp == seen and hashlib.sha256(final.read_bytes()).hexdigest() == digest:
                status = "ok"
                break
            seen = stamp
            time.sleep(0.2)
            continue
        seen = None
        time.sleep(0.05)
    # The last poll interval and stability check add at most ~0.25 s; subtract the confirmation pause.
    seconds = time.monotonic() - started - (0.2 if status == "ok" else 0)
    subprocess.run(["wineserver", "-k"], **quiet)
    final.unlink(missing_ok=True)
    return dict(client=client, status=status, seconds=round(seconds, 3), bytes=size if status == "ok" else 0,
                mib_s=round((size if status == "ok" else 0) / MiB / seconds, 3), received="", transfer_seconds="")


def run_one(client, url, target, digest, size, timeout, java, classpath, resident=False):
    if client.startswith("idm-"):
        return run_idm(client, url, target, digest, size, timeout)
    target.unlink(missing_ok=True)
    started = time.monotonic()
    timed_out, code, detail = False, None, ""
    cmd = command(client, url, target, digest, java, classpath)
    try:
        if resident and client.startswith(("ajdm-", "base-")):
            split = cmd.index("io.ajdm.bench.LabDownload") + 1
            if client not in residents:
                residents[client] = Resident(cmd[:split] + ["--serve"])
                started = time.monotonic()
            try:
                line = residents[client].run(" ".join(cmd[split:]), timeout)
            except (queue.Empty, RuntimeError):
                residents.pop(client).close()
                raise subprocess.TimeoutExpired(cmd, timeout)
            code = 0 if line.startswith("LAB_RESULT") else 1
            detail = [line]
        else:
            done = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            code = done.returncode
            detail = (done.stdout + done.stderr).strip().splitlines()
    except subprocess.TimeoutExpired:
        timed_out = True
        detail = []
    seconds = time.monotonic() - started
    have = target.stat().st_size if target.exists() else 0
    verified = (not timed_out and code == 0 and have == size
                and hashlib.sha256(target.read_bytes()).hexdigest() == digest)
    extra = {}
    for line in detail if isinstance(detail, list) else []:
        if line.startswith("LAB_RESULT"):
            extra = dict(part.split("=", 1) for part in line.split()[1:])
    status = "ok" if verified else "timeout" if timed_out else f"failed({code})"
    # On timeout, partial bytes over the limit give a lower bound; segmented clients may hold sparse data.
    return dict(client=client, status=status, seconds=round(seconds, 3), bytes=have if verified else 0,
                mib_s=round((size if verified else 0) / MiB / seconds, 3),
                received=extra.get("received", ""), transfer_seconds=extra.get("transfer_seconds", ""))


def in_namespace(args):
    # Real paths carry ~1500-byte packets; loopback's 64 KiB MTU would hide most of the cost of loss.
    sh("ip", "link", "set", "lo", "mtu", "1500")
    sh("ip", "link", "set", "lo", "up")
    out = Path(args.out).resolve()
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    java = str(Path(os.environ.get("JAVA_HOME", "/usr")) / "bin" / "java")
    classpath = jars(REPO / "ajdm-bench" / "build" / "install" / "ajdm-bench" / "lib")
    global baseline_classpath
    if args.baseline_lib:
        baseline_classpath = jars(args.baseline_lib)
    clients = args.clients.split(",")
    rows = []
    results = out / "results.csv"
    fields = ["profile", "trial", "order", "client", "status", "seconds", "mib_s", "bytes", "received", "transfer_seconds"]
    with open(results, "w", newline="") as handle:
        csv.DictWriter(handle, fields).writeheader()
    for name in args.profiles.split(","):
        profile = PROFILES[name]
        size_mib = max(1, int(profile["size"] * args.scale))
        path, digest = payload(work, size_mib)
        size = path.stat().st_size
        line_rate = float(profile["rate"].removesuffix("mbit")) * 1e6 / 8
        timeout = max(60.0, size / line_rate * args.timeout_factor)
        trace = out / f"trace-{name}.log" if args.trace else None
        netem(profile)
        server = subprocess.Popen([sys.executable, "-I", str(HERE / "range_server.py"), str(path), "--port", "8080",
                                   "--per-conn-rate", str(profile.get("per_conn_rate", 0)),
                                   "--drop-prob", str(profile.get("drop_prob", 0)),
                                   "--max-conns", str(profile.get("max_conns", 0)),
                                   *(["--trace", str(trace)] if trace else [])],
                                  stdout=subprocess.PIPE, text=True)
        assert server.stdout.readline().strip() == "READY"
        url = "http://127.0.0.1:8080/" + path.name
        print(f"== {name}: {profile['desc']} | {size_mib} MiB | timeout {timeout:.0f}s", flush=True)
        try:
            for trial in range(args.trials):
                order = clients[trial % len(clients):] + clients[:trial % len(clients)]
                for position, client in enumerate(order):
                    stop = threading.Event()
                    flapper = None
                    if "blackout" in profile:
                        flapper = threading.Thread(target=blackout_loop, args=(profile, stop), daemon=True)
                        flapper.start()
                    if trace:
                        with open(trace, "a") as marks:
                            marks.write(f"{time.monotonic():.4f} 0 BEGIN {client} {trial + 1}\n")
                    try:
                        row = run_one(client, url, work / f"out-{client}.bin", digest, size, timeout, java, classpath, not args.cold_ajdm)
                    finally:
                        stop.set()
                        if flapper:
                            flapper.join()
                    row.update(profile=name, trial=trial + 1, order=position + 1)
                    if trace:
                        with open(trace, "a") as marks:
                            marks.write(f"{time.monotonic():.4f} 0 FINISH {client} {trial + 1} {row['status']}\n")
                    rows.append(row)
                    with open(results, "a", newline="") as handle:
                        csv.DictWriter(handle, fields).writerow({k: row.get(k, "") for k in fields})
                    print(f"  t{trial + 1} {client:17s} {row['status']:10s} {row['seconds']:8.1f}s {row['mib_s']:7.3f} MiB/s", flush=True)
                    (work / f"out-{client}.bin").unlink(missing_ok=True)
                    time.sleep(1)
        finally:
            server.terminate()
            server.wait()
    for process in residents.values():
        process.close()
    (out / "profiles.json").write_text(json.dumps({n: PROFILES[n] for n in args.profiles.split(",")}, indent=2))
    shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--profiles", default=",".join(PROFILES))
    parser.add_argument("--clients", default=",".join(CLIENTS))
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--cold-ajdm", action="store_true", help="start a new JVM per AJDM download instead of a resident engine")
    parser.add_argument("--trace", action="store_true", help="record server request timelines per profile")
    parser.add_argument("--baseline-lib", help="lib/ of another AJDM bench build; clients named base-fixed-N / base-adaptive-N use it")
    parser.add_argument("--scale", type=float, default=1.0, help="multiply payload sizes (e.g. 0.25 for a quick pass)")
    parser.add_argument("--timeout-factor", type=float, default=8.0, help="timeout = max(60 s, factor x ideal line-rate time)")
    args = parser.parse_args()
    if os.environ.get("AJDM_NETLAB_NS") != "1":
        env = dict(os.environ, AJDM_NETLAB_NS="1")
        os.execvpe("unshare", ["unshare", "-rn", sys.executable, "-I", __file__, *sys.argv[1:]], env)
    in_namespace(args)


if __name__ == "__main__":
    main()
