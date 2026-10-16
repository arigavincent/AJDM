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
    return [java, "-Dajdm.hostLearning.disabled=true", "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
            "io.ajdm.bench.LabDownload", mode, count, url, str(target), digest]


def run_one(client, url, target, digest, size, timeout, java, classpath):
    target.unlink(missing_ok=True)
    started = time.monotonic()
    timed_out, code, detail = False, None, ""
    try:
        done = subprocess.run(command(client, url, target, digest, java, classpath), capture_output=True, text=True, timeout=timeout)
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
        netem(profile)
        server = subprocess.Popen([sys.executable, "-I", str(HERE / "range_server.py"), str(path), "--port", "8080",
                                   "--per-conn-rate", str(profile.get("per_conn_rate", 0)),
                                   "--drop-prob", str(profile.get("drop_prob", 0)),
                                   "--max-conns", str(profile.get("max_conns", 0))],
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
                    try:
                        row = run_one(client, url, work / f"out-{client}.bin", digest, size, timeout, java, classpath)
                    finally:
                        stop.set()
                        if flapper:
                            flapper.join()
                    row.update(profile=name, trial=trial + 1, order=position + 1)
                    rows.append(row)
                    with open(results, "a", newline="") as handle:
                        csv.DictWriter(handle, fields).writerow({k: row.get(k, "") for k in fields})
                    print(f"  t{trial + 1} {client:17s} {row['status']:10s} {row['seconds']:8.1f}s {row['mib_s']:7.3f} MiB/s", flush=True)
                    (work / f"out-{client}.bin").unlink(missing_ok=True)
                    time.sleep(1)
        finally:
            server.terminate()
            server.wait()
    (out / "profiles.json").write_text(json.dumps({n: PROFILES[n] for n in args.profiles.split(",")}, indent=2))
    shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--profiles", default=",".join(PROFILES))
    parser.add_argument("--clients", default=",".join(CLIENTS))
    parser.add_argument("--trials", type=int, default=3)
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
