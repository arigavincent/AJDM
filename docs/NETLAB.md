# Emulated-network lab

`tools/netlab/run_lab.py` compares download clients on real kernel TCP shaped by netem inside an unprivileged Linux network namespace (`unshare -rn`), so it needs no root and never touches the host network. Loopback uses a 1500-byte MTU; netem adds delay, jitter, loss and a rate limit (RTT is about twice the configured delay). `tools/netlab/range_server.py` serves one random payload with Range/ETag support and can throttle each connection, cap concurrent responses with 503, or cut responses mid-body. Outage profiles switch the link to 100% loss on a schedule. Every output is SHA-256 verified and client order rotates per trial.

```
./gradlew :ajdm-bench:installDist          # Java 25 in JAVA_HOME
python3 tools/netlab/run_lab.py --out DIR [--profiles a,b] [--clients x,y] [--trials N] [--baseline-lib OTHER_BUILD/lib]
```

Clients: `curl-1`, `aria2-N`, `ajdm-fixed-N`, `ajdm-adaptive-N` (through `io.ajdm.bench.LabDownload`, using `TransferOptions.defaults()` plus the connection setting, as the product core does; host learning disabled; one resident engine per client unless `--cold-ajdm`), `base-fixed-N` / `base-adaptive-N` from another build's `lib/` for before/after comparisons, and `idm-N` (Internet Download Manager in a Wine prefix, see below). A `@wN` suffix on an AJDM client caps each request window at N MiB. `--trace` records every server request; `tools/netlab/analyze_trace.py` summarises startup, request counts, idle keep-alive gaps and tail per run.

These are emulations. They show behaviour under loss, latency, throttling and outages; they are not carrier measurements. IDM runs under Wine on Linux, not on Windows, so its numbers are indicative only. Physical 4G/5G and native Windows IDM comparisons remain separate acceptance work.

### IDM client

`idm-N` expects the IDM 6.43 build 15 trial installed into the Wine prefix `IDM_PREFIX` (default `~/Documents/AJDM-idm/prefix`). Each run restarts wineserver, sets `HKCU\Software\DownloadManager\MaxConnectionsNumber` to N, starts IDM (startup not timed), queues the URL with `IDMan.exe /d URL /p DIR /f NAME /n`, and polls until the file has the full size and the expected SHA-256. Time is measured from queueing to verified file, so it includes IDM's own start-up of the transfer (about 8.7 s before the first payload request in traces, which dominates short runs on fast links).

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

## Results, 16 October 2026, round 2 and IDM

Round 2 adds per-lease settling, a GET-first probe, 64 KiB steal pieces, resident engine measurement and 16/8 connection defaults. Median MiB/s of three SHA-verified trials; the IDM table is one run of the lab with all five clients interleaved.

| Client | bad-3g | lossy-4g | typical-4g | throttled-host | capped-host | flapping-4g | good-5g |
|---|---|---|---|---|---|---|---|
| IDM ×8 (Wine) | 0.25 | 1.14 | 2.89 | 1.12 | 1.14 | 1.27 | 12.04 |
| IDM ×16 (Wine) | 0.28 | 1.75 | 3.10 | 2.01 | 1.14 | 1.28 | 10.15 |
| AJDM fixed 8 | 0.28 | 1.11 | 2.90 | 1.16 | 1.16 | 1.45 | 12.71 |
| AJDM fixed 16 | **0.31** | **1.81** | **3.17** | **2.29** | 1.12 | **1.66** | 14.14 |
| aria2 ×16 | 0.27 | 1.72 | 3.08 | 2.24 | 1.16 | 1.51 | **14.50** |

Round 2 against the previous engine (separate run, same profiles):

| Client | good-5g | typical-4g | lossy-4g | bad-3g | throttled-host | capped-host | flapping-4g |
|---|---|---|---|---|---|---|---|
| curl, 1 connection | 14.48 | 0.59 | timeout | 0.05 | timeout | timeout | 0.00 (2 of 3 timed out) |
| aria2 ×8 | 16.17 | 2.88 | 1.09 | 0.25 | 1.17 | 1.16 | 1.08 |
| aria2 ×16 | 16.18 | 3.09 | 1.53 | 0.28 | 2.24 | 1.16 | 1.66 |
| old AJDM fixed 4 (old default) | 12.92 | 1.89 | 0.56 | 0.17 | 0.58 | 1.16 | 0.97 |
| old AJDM fixed 16 | 14.83 | 2.89 | 1.61 | 0.28 | 2.28 | 0.89 | 1.48 |
| AJDM fixed 8 | 15.26 | 2.93 | 1.14 | 0.27 | 1.16 | 1.15 | 1.42 |
| AJDM fixed 16 | 15.16 | 3.19 | 1.82 | 0.31 | 2.27 | 1.14 | 1.66 |

(The good-5g column of this run differs from the IDM run by about 1 MiB/s for every client; run-to-run variation on that profile is larger than the gaps between clients.)

Findings:
- AJDM fixed 16 had the highest median of all clients on bad-3g, lossy-4g, typical-4g, throttled-host and flapping-4g in the IDM run, and was ahead of IDM under Wine at both 8 and 16 connections on every profile except the 503-capped host, where every client is held to the same four-connection limit.
- Against the previous engine with its old default of four connections, the new default is 1.6–4× faster on lossy, throttled and outage profiles. At equal connection counts the capped host improved most (0.89 → 1.14).
- aria2 stays slightly ahead on good-5g. Traces show AJDM making about 90 range requests for 256 MiB against aria2's 32, because each request window is capped at 4 MiB. With 16 MiB windows (`ajdm-fixed-16@w16`) AJDM reached 14.30–14.50 against 13.98–14.26 with 4 MiB and aria2's 14.57–14.67, but was about 1% slower on typical-4g in all three trials, so the default is unchanged pending a rate-based window for fixed mode.
- IDM under Wine waits about 8.7 s from queueing to its first payload request and opens a new connection per piece with open-ended ranges; that start-up cost, not transfer rate, explains most of its deficit on fast links. A native Windows run is needed before drawing conclusions about IDM itself.

## Real network, 17 October 2026

From here on, measurements use the developer's own connection instead of emulation: a slow mobile-class link with ~110 KiB/s on one connection, ~200–300 KiB/s aggregate, 180–350 ms idle RTT and heavy bufferbloat (requests on a loaded link waited 1–7 s for headers). `tools/netlab/real_run.py` downloads OVH's public `proof.ovh.net/files/10Mb.dat` (10 MiB) with each client in rotating order, checks every copy against the same SHA-256, samples per-connection TCP state with `ss` (`tools/netlab/ss_sampler.py`) and, for AJDM, writes an engine trace (`-Dajdm.trace=FILE`). AJDM runs in one resident engine per client, as the product core does. Raw results: `docs/netlab-results/realnet-*-20261017.csv`.

The link's speed swings by up to 4× between minutes, so single runs mean little. Results are paired: each round runs every client back to back, and AJDM's speed is divided by each competitor's in the same round.

| Build | vs aria2 ×16 | vs IDM ×16 (Wine) |
|---|---|---|
| Before rate-based stealing and pre-warm | 12 of 25 rounds, geo-mean 0.97× | 10 of 17, 1.12× |
| With pre-warm (current default) | 9 of 14 rounds, geo-mean 1.11× | 4 of 5, 1.29× |

What the traces showed and what changed:
- **Tail.** Fixed 64 KiB steal pieces and cut margins were 10+ seconds of data for a ~20 KiB/s connection, so each lane's last ~190 KiB could not be split and idle workers exited 10 s before the last one. Pieces and margins now follow each lease's measured rate (about 1 s and 0.5 s of data, 16–64 KiB); worker exits bunch within 1–3 s.
- **Startup.** Connections opened after the first stream started waited behind its queued data (2–7 s for headers). Pre-warming the other connections with 1-byte range requests during the probe cut median initial header latency from 2.3–4.9 s to 1.5–2.4 s and won 6 of 9 rounds against the same build without it (1.10× geo-mean).
- **Steady state.** Between 10% and 90% of the file, AJDM's median rate was 0.238 MiB/s against aria2's 0.219, so the remaining differences are mostly at the start and end of a short file.

Caveats: one server, one link, 10 MiB files and 5–25 rounds per comparison; IDM runs under Wine, not Windows. On multi-gigabyte files the start and tail matter proportionally less.
