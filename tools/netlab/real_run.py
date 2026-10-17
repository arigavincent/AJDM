"""Real-network comparison on your own link, reusing the lab clients.

  python3 tools/netlab/real_run.py URL ROUNDS OUT_DIR SIZE_BYTES [CLIENTS]   (JAVA_HOME = Java 25)

Uses real data: about ROUNDS x clients x SIZE. CLIENTS is comma-separated (default curl-1,aria2-16,ajdm-fixed-16,idm-16)
and rotates each round. Unless REAL_SHA256 is set, the first client (curl or aria2, never the AJDM build under test)
supplies the reference SHA-256 that every other download must match. REAL_BASELINE_LIB names another build's lib/ for base-fixed-N
clients. Each result is appended to OUT_DIR/results.csv
as soon as it is measured, so repeated or interrupted sessions accumulate.
"""
import csv, hashlib, json, os, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_lab as lab
import ss_sampler

url, rounds, out, size = sys.argv[1], int(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4])
clients = sys.argv[5].split(",") if len(sys.argv) > 5 else ["curl-1", "aria2-16", "ajdm-fixed-16", "idm-16"]
out.mkdir(parents=True, exist_ok=True)
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
cp = lab.jars(lab.REPO / "ajdm-bench" / "build" / "install" / "ajdm-bench" / "lib")
if os.environ.get("REAL_BASELINE_LIB"):
    lab.baseline_classpath = lab.jars(Path(os.environ["REAL_BASELINE_LIB"]))  # base-fixed-N clients
target = out / "payload.bin"
results = out / "results.csv"
fields = ["when", "url", "trial", "client", "status", "seconds", "bytes", "mib_s", "received",
          "first_byte_s", "connections", "mean_active", "tail_after_90pct_s", "median_rtt_ms"]


def record(trial, r):
    row = dict(when=time.strftime("%Y-%m-%dT%H:%M:%S"), url=url, trial=trial, **{k: r.get(k, "") for k in fields[3:]})
    new = not results.exists()
    with open(results, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        if new:
            w.writeheader()
        w.writerow(row)
    print(f"t{trial} {r['client']:16s} {r['status']:8s} {r['seconds']:7.1f}s {r['mib_s']:.3f} MiB/s  first-byte {r.get('first_byte_s')}s"
          f"  conns {r.get('connections')} active {r.get('mean_active')}  tail {r.get('tail_after_90pct_s')}s  rtt {r.get('median_rtt_ms')}ms"
          f"  received {r.get('received') or '-'}", flush=True)


digest = os.environ.get("REAL_SHA256")
skip_first = False
if digest is None:
    first = clients[0]
    assert first.startswith(("curl", "aria2")), "first client must be curl or aria2 unless REAL_SHA256 is set"
    target.unlink(missing_ok=True)
    started = time.monotonic()
    subprocess.run(lab.command(first, url, target, "", java, cp), timeout=1800, capture_output=True)
    seconds = time.monotonic() - started
    assert target.stat().st_size == size, "reference download has the wrong size"
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print("reference sha256", digest, flush=True)
    record(1, dict(client=first, status="ok", seconds=round(seconds, 3), bytes=size, mib_s=round(size / lab.MiB / seconds, 3)))
    skip_first = True
for trial in range(1, rounds + 1):
    shift = (trial - 1) % len(clients)
    for client in clients[shift:] + clients[:shift]:
        if trial == 1 and skip_first and client == clients[0]:
            continue
        stamp = time.strftime('%Y%m%dT%H%M%S')
        (out / "traces").mkdir(exist_ok=True)
        os.environ["AJDM_LAB_JVM_OPTS"] = f"-Dajdm.trace={out / 'traces' / f'{stamp}-t{trial}-{client}.log'}"
        with ss_sampler.Sampler(url) as sampler:
            r = lab.run_one(client, url, target, digest, size, 1800, java, cp)
        record(trial, {**r, **ss_sampler.summarize(sampler, size)})
        (out / "samples").mkdir(exist_ok=True)
        (out / "samples" / f"{stamp}-t{trial}-{client}.json").write_text(json.dumps(sampler.samples))
target.unlink(missing_ok=True)
