# Emulated-network lab

`tools/netlab/run_lab.py` compares download clients on real kernel TCP shaped by netem inside an unprivileged Linux network namespace (`unshare -rn`), so it needs no root and never touches the host network. Loopback uses a 1500-byte MTU; netem adds delay, jitter, loss and a rate limit (RTT is about twice the configured delay). `tools/netlab/range_server.py` serves one random payload with Range/ETag support and can throttle each connection, cap concurrent responses with 503, or cut responses mid-body. Outage profiles switch the link to 100% loss on a schedule. Every output is SHA-256 verified and client order rotates per trial.

```
./gradlew :ajdm-bench:installDist          # Java 25 in JAVA_HOME
python3 tools/netlab/run_lab.py --out DIR [--profiles a,b] [--clients x,y] [--trials N] [--baseline-lib OTHER_BUILD/lib]
```

Clients: `curl-1`, `aria2-N`, `ajdm-fixed-N`, `ajdm-adaptive-N` (through `io.ajdm.bench.LabDownload`, using `TransferOptions.defaults()` plus the connection setting, as the product core does; host learning disabled) and `base-fixed-N` / `base-adaptive-N` from another build's `lib/` for before/after comparisons.

These are emulations. They show behaviour under loss, latency, throttling and outages; they are not carrier measurements and do not include IDM. aria2 stands in as a segmented download manager on Linux. Physical 4G/5G and matched Windows IDM comparisons remain separate acceptance work.

## Results, 16 October 2026

Median useful throughput in MiB/s, three trials each. "Old" is `main` at `ba14cea`; "new" adds dynamic window splitting (and, where marked, connection shedding).

| Client | typical-4g | lossy-4g | bad-3g | throttled-host | capped-host | flapping-4g | good-5g |
|---|---|---|---|---|---|---|---|
| curl, 1 connection | 0.57 | timeout ×3 | 0.05 | timeout ×3 | timeout ×3 | 0.33 | — |
| aria2 ×8 | 3.05 | 1.12 | 0.25 | 1.17 | 1.16 † | 1.42 | 16.23 † |
| aria2 ×16 | 3.22 | 1.69 | 0.29 | 2.30 | 1.16 † | 1.58 | 16.30 † |
| old AJDM fixed 8 | 2.54 | 1.02 | 0.24 | 1.14 | 0.73 † | 1.04 | 13.50 † |
| new AJDM fixed 8 | 2.86 | 1.08 | 0.25 | 1.14 | 1.14 † | 1.06 | 14.49 † |
| new AJDM fixed 16 | 3.09 | 1.64 | 0.29 | 2.13 | 1.09 † | 1.52 | 14.73 † |
| new AJDM adaptive 16 | 2.74 | 1.46 | 0.23 | 1.80 | 1.12 † | 1.48 | 13.62 † |

† Separate run with the connection-shedding build and no concurrent builds; the other columns used the splitting-only build. Profiles: typical-4g 30 Mbit/s, ~50 ms RTT, 0.5% loss; lossy-4g 20 Mbit/s, ~80 ms, 2%; bad-3g 3 Mbit/s, ~240 ms, 3%; throttled-host 30 Mbit/s with 150 KiB/s per connection; capped-host 300 KiB/s per connection and at most four connections (503 beyond); flapping-4g 20 Mbit/s with 6 s outages every 20 s and 5% of responses cut; good-5g 150 Mbit/s, ~16 ms, 0.05%.

Findings: single connections collapse under realistic loss; dynamic splitting improved the old engine on every lossy profile; shedding moved the capped host from 0.73 to 1.14 MiB/s. AJDM is level with aria2 on bad-3g and within roughly 2–10% elsewhere, not ahead. Sixteen connections beat eight by 15–90% on lossy and throttled links, adaptive mode trails fixed 16, and per-request overhead costs about 10% at 5G rates. Those are the next optimisation targets.
