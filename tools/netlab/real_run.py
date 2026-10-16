"""Real-network comparison on your own link, reusing the lab clients.

  python3 tools/netlab/real_run.py URL ROUNDS OUT_DIR SIZE_BYTES   (JAVA_HOME = Java 25; uses real data: ROUNDS x 4 x SIZE)
"""
import csv, hashlib, os, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_lab as lab

url, rounds, out = sys.argv[1], int(sys.argv[2]), Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
cp = lab.jars(lab.REPO / "ajdm-bench" / "build" / "install" / "ajdm-bench" / "lib")
target = out / "payload.bin"
clients = ["curl-1", "aria2-16", "ajdm-fixed-16", "idm-16"]

# Reference digest from an untimed single fetch would cost extra data, so curl's first timed run supplies it,
# checked against the server's size; every other client must match that digest.
size = int(sys.argv[4])
rows = []
def record(trial, r):
    rows.append(dict(trial=trial, **r)); print(f"t{trial} {r['client']:14s} {r['status']:8s} {r['seconds']:7.1f}s {r['mib_s']:.3f} MiB/s", flush=True)

started = time.monotonic(); target.unlink(missing_ok=True)
subprocess.run(lab.command("curl-1", url, target, "", java, cp), timeout=1800)
seconds = time.monotonic() - started
assert target.stat().st_size == size
digest = hashlib.sha256(target.read_bytes()).hexdigest()
print("reference sha256", digest)
record(1, dict(client="curl-1", status="ok", seconds=round(seconds, 3), bytes=size, mib_s=round(size / lab.MiB / seconds, 3)))
for trial in range(1, rounds + 1):
    order = clients[trial - 1:] + clients[:trial - 1]
    for client in order:
        if trial == 1 and client == "curl-1":
            continue
        r = lab.run_one(client, url, target, digest, size, 1800, java, cp)
        record(trial, {k: r[k] for k in ("client", "status", "seconds", "bytes", "mib_s")})
with open(out / "results.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
target.unlink(missing_ok=True)
