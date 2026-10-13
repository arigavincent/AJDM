# Measuring AJDM toward the IDM target

Phase 6 now supplies a repeatable local matrix and an importer for real external measurements. The current Linux results are exploratory. They do not satisfy [AJDM_PRD.pdf](../AJDM_PRD.pdf) sections 3 and 12 or architecture Appendix C's same-host Windows acceptance gate. The user's 5G goal extends the PRD's explicit 4G-hotspot scenario.

## PRD acceptance conditions

G1 is **AJDM median / IDM median <=0.95 in at least 70% of scenarios and <=1.10 in every scenario**. Declare the scenario aggregation before running comparisons; do not omit losses. If the nine B1–B9 rows each count equally, at least seven must qualify. Declare treatment of B8's two resolutions and additional 5G trials explicitly.

| ID | Required scenario |
|---|---|
| B1 | Unthrottled LAN, 2 GB file |
| B2 | Per-connection cap 2 MB/s using nginx limit_rate |
| B3 | Global cap 10 Mbit/s |
| B4 | Actual 150 ms RTT using network shaping |
| B5 | Actual 1–3% packet loss |
| B6 | Flaky server with random disconnects |
| B7 | Real large CDN file |
| B8 | YouTube 1080p and 2160p, end-to-end including local mux |
| B9 | Physical phone 4G hotspot |

Use >=10 alternating, cold-cache trials per tool for every acceptance scenario, all hashes, median/p10/p90 wall time and **mean link utilization** with an independently measured capacity denominator. PRD utilization targets are >=90% on an unthrottled link and >=95% of min(link, N × C) on per-connection-capped sources; ENG-03 additionally requires reaching that capped throughput within 10 s.

The external importer currently reports the inverse time ratio, IDM/AJDM. Convert it before applying G1. It validates individual paired groups but does not yet gate complete B1–B9 coverage, aggregate G1 or mean link utilization. Retain capacity/network counters separately and extend acceptance reporting before declaring a pass. The [alignment audit](PRD_ALIGNMENT.md) tracks all remaining gates. The small local matrix profiles below are tuning fixtures, not substitutes for these conditions.

## Reproduce the local matrix

Build `:ajdm-bench:installDist`, set `JAVA_HOME` to Java 25, and choose a fresh output directory:

```bash
JAVA_OPTS='-Xms128m -Xmx768m' ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench \
  --state /tmp/ajdm-benchmark/state.db benchmark-matrix /tmp/ajdm-new-report 6
python3 tools/verify_performance_matrix.py /tmp/ajdm-new-report
python3 tools/plot_performance_matrix.py /tmp/ajdm-new-report
```

The Java matrix and verifier require no new engine dependencies. The optional figure script requires Matplotlib. Reusing an output directory is rejected before any files are overwritten. Completed samples and their reports are retained incrementally; interrupted reports stay marked in progress.

Six rounds cover all six orderings of single, fixed, and adaptive modes, balancing every position and pairwise order. Each mode gets a warmup. Every measured transfer uses the same hash/durability/buffer/checkpoint settings, a fresh fixture, and a unique target. Construction and the independent output rehash are outside the timer; engine probing, transferring, forcing, SQLite commits, SHA-256, and publication are inside it. Warm JVM/filesystem caches are explicit and are not described as cold-cache evidence.

The profiles cover unthrottled loopback, per-response cap, shared cap, 150 ms HTTP response delay, a scheduled shared-rate decrease/recovery, and two deterministic incomplete response bodies. These do not simulate TCP slow start, radio handover, packet loss, or actual RTT. The earlier phase-4 report separately exercises a slowing TCP peer. HTTP/1.1 connection reuse remains enabled; a logical worker is not a physical connection identity.

Resource metrics sample every 50 ms. CPU, GC, heap, and Linux RSS describe the entire JVM, including the source fixture and sampler. Observed peaks may miss short bursts. The queue metric counts pending submitted disk-write bytes across every engine mode; acknowledged-minus-durable bytes measure checkpoint lag rather than disk backlog. Received bytes count application-read payload and retries; server offered bytes count successful writes that may not reach the client after cancellation. Neither metric measures complete network traffic including HTTP/TLS/IP headers.

## Same-host Windows AJDM/IDM comparison

Use the same Windows computer, output volume, network interface, source representation, and verification expectations for both tools. Record OS, CPU/RAM/storage, Java/build hash, IDM version, HTTP/proxy settings, antivirus behavior, worker limits, and whether host-profile learning or caches are reused. Keep these constant within each comparison. AJDM currently has no persistent host profile.

Run each comparison as at least ten adjacent pairs: five AJDM-first and five IDM-first, alternating the pair direction. Compare single/IDM, fixed/IDM, and adaptive/IDM in separate `comparison_id` groups. More samples and randomized pair order improve evidence. Do not combine measurements from different hosts, network types, sources, bytes/hashes, cache policies, or tool versions.

For controlled local fixtures on Windows, the installed runner can serve a profile without starting the engine:

```powershell
.\ajdm-bench\build\install\ajdm-bench\bin\ajdm-bench.bat serve-benchmark shared-cap 8765
```

It binds to loopback and prints the file URL, size, and expected SHA-256. Stop and restart the fixture before every tool trial so its rate clock, request ordinals, and counters are fresh. The other profile names are listed in `profiles.csv`. IDM is run manually by the operator against the printed source. This command does not invoke IDM or give it ownership of AJDM transfers.

Capture a client-only AJDM sample with the standardized matrix options (choose `single`, `fixed`, or `adaptive`):

```powershell
.\ajdm-bench\build\install\ajdm-bench\bin\ajdm-bench.bat --state C:\AJDM-bench\state.db benchmark-download fixed 'http://127.0.0.1:8765/payload.bin' C:\AJDM-bench\fixed-01.bin '<expected-sha256>'
```

The command measures from engine start through verified completion, excluding JVM startup, and writes `fixed-01.bin.benchmark.csv` plus resource/progress telemetry next to the verified target. CPU/RSS describe the AJDM client JVM; the fixture runs separately. Unknown server concurrency is recorded as -1. Every sample requires an expected SHA-256. Use a fresh target and sidecar filenames. For external CDN files, replace the URL and hash with the known source; URL/auth values are not emitted in the metric sidecars. Context labels and operator observations are added when filling the external comparison template. Optional counters marked -1 must become blank in that template rather than a fabricated zero.

For cold-cache tests, explicitly define and apply the same cache-reset procedure to both tools, reset learned policy where the tool permits it, and record the steps. A new filename does not establish cold cache. Keep warm-cache tests in a separate group. The importer checks the declared category; it cannot confirm the operator's cache-reset procedure.

Choose sources and sizes that sustain at least 20 seconds of transfer where possible; very short files are dominated by setup and completion costs. Keep source bytes and checksum stable. Verify each output independently with `Get-FileHash -Algorithm SHA256` and file size before admitting its timing. Keep failed/unverified attempts in a separate rejection log, with their failure rate and elapsed time, rather than quietly excluding them from success-rate claims.

Time from initiating the task until successful verified completion. Distinguish this from payload-only time; record extraction and mux timing separately for media. Use the same verification policy for both tools. If IDM does not perform equivalent hash verification, include an external hash step in its verified-completion timing and describe that difference. Do not silently compare AJDM's verified-completion time against IDM's payload-only time.

## Real latency/loss and 4G/5G

For actual controlled RTT/loss, use an explicitly configured network emulator or router and retain its settings and observation logs. The built-in HTTP delay/truncation profiles are not substitutes. This milestone installs no packet shaping or system network configuration.

For 4G and 5G, retain operator/location/time, modem/device, radio mode and available signal readings, connection method, endpoint/CDN, and independent capacity observations taken near each pair. Keep competing traffic and power policy controlled and record radio changes rather than treating the label as a constant capacity. Run both tools on the same Windows host using the same tether or modem. No radio state is inferred by this harness.

Use stable CDN files with known hashes before media. For YouTube/media, compare the same format/codec/container and separate audio/video streams. Extraction selects direct URLs and complete headers; AJDM alone downloads them. ffmpeg may mux completed local files. Record expired URLs, failed refreshes, and overall success rate. Formats changing byte identity must not be combined. Live streams, DRM, segmented HLS/DASH adapters, and ffmpeg integration remain separate work.

Link utilization requires an independent measured denominator and network byte counters. Application payload throughput divided by a configured fixture cap is only fixture efficiency. The local matrix's throughput and received counters do not establish 95% physical link utilization or performance against IDM.

## External CSV and report

Copy [external-benchmark-template.csv](external-benchmark-template.csv), retaining the exact column order. Use non-secret labels for `run_id`, `host_id`, `network`, `source_id`, and `comparison_id`; never put signed URLs, cookies, or bearer tokens into these fields. Record UTC capture time, declared cold/warm policy, actual execution sequence, one mode (`single`, `fixed`, `adaptive`, or `idm`), stable tool version, verified bytes/hash, and verified completion seconds. Optional application payload, CPU, and process RSS counters remain blank if unavailable; zero is a measured zero, not an unknown value.

`comparison_id` identifies a two-tool paired comparison such as `adaptive-idm`. Sequence numbers are unique within a context and reflect actual order. File identity must match within the group. Optional CPU/RSS counters must use the same whole-process definition for both tools; exclude a separately hosted fixture if measuring client CPU. The local same-JVM measurements are not interchangeable with that scope.

Merge one or more exported files:

```bash
python3 tools/summarize_external_benchmarks.py windows-4g.csv windows-5g.csv --output /tmp/ajdm-external-report
```

The importer rejects missing verification, invalid hashes/timing, mixed file identities/versions, duplicate run IDs/order, unmatched contexts, fewer than ten samples per tool, unequal counts, and biased pair direction. It reports linear-interpolated p10/median/p90 and IDM/tool median time ratios. Ratios above one mean the tool had a lower median time than IDM in that exact group. Missing resource metrics stay unknown. Imported timings and verification are operator-supplied; the importer cannot independently observe IDM, timers, cache state, or the radio.

Retain raw samples, metadata, hashes, failed attempts, radio/capacity/emulator logs, and resource capture files with every published comparison. The final acceptance claim is based on those measured matched groups, not synthetic importer tests or an assumed advantage from parallelism.
