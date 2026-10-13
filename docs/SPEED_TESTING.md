# Testing AJDM download speed

A slow mobile link is useful for resilience, latency and efficient bandwidth use. It cannot prove fast-link throughput: request windows, buffer limits, CPU and disk costs become different bottlenecks as available capacity grows. Test both ends and changing conditions. The goal is useful file bytes per second and completion time, with correct output and durable resume. Adaptive windows now use a 1 MiB floor bounded by the caller’s request cap, and can grow to the configured maximum (4 MiB by default). This avoids repeatedly shrinking requests to 64 KiB on slow/delayed sources; final tails and smaller explicit caps can still be smaller. Scheduler completion also wakes the adaptive coordinator and idle workers immediately, without waiting for their normal 250/100 ms sampling intervals. This particularly matters for small files on fast links.

Use the same source, exact format IDs, quality, network/device and output disk for comparisons. Run trials sequentially, rotate their order, and repeat at least three times. Avoid other downloads during comparison. Report median, range, startup time, retries, received versus useful bytes, and controller decisions. Keep source metadata/URLs private. A short sample is exploratory; only complete downloads establish total completion time and output integrity. Network/source throttling changes can still confound a mobile comparison.

## Bounded comparison on a video source

The development [MediaSpeedProbe](../tools/MediaSpeedProbe.java) extracts once through AJDM's metadata adapter, then uses AJDM's actual engine for nine samples: fixed four, fixed eight, adaptive up to eight, in three rotated rounds. It uses production transfer defaults and a separate private state directory. It refuses to start while the core has active downloads and stops its own sample if a core download begins. It never controls existing core jobs.

Each sample requests a pause after 20 seconds or at least 4 MiB acknowledged. In-flight bytes and pause/checkpoint overhead can exceed those thresholds; these are not strict network-data or wall-clock caps. The report shows useful acknowledged throughput before stopping and settled received/written totals separately. Paused sample partials and private recovery state are retained; they are not playable full files or SHA-256-verified outputs.

After building CLI packages with Java 25, substitute an exact large direct format ID obtained from `formats --show-all` and a **new** report-directory name:

```bash
cd /home/ariga/Documents/AJDM
export JAVA_HOME=/usr/lib/jvm/jdk-25.0.1-oracle-x64
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED \
  --class-path 'ajdm-cli/build/install/ajdm/lib/*' \
  tools/MediaSpeedProbe.java \
  'https://youtu.be/J70bt3I_1i4' 313 \
  /home/ariga/.local/bin/yt-dlp \
  /tmp/ajdm-real-test-1000/ajdm.sock \
  artifacts/my-speed-comparison-01
```

CLI's installed directory is named `ajdm` when using the project application name; verified runtime distributions can also supply their `lib/*` path. The source must expose a direct HTTPS stream with known or estimated size >=16 MiB. The supplied example selects 4K VP9 video only so all samples exercise one payload source. The normal UI can continue to show the paused existing job. Read `report.md` and `samples.json` inside the new directory; comparison results omit signed URLs and headers, while private SQLite recovery state necessarily contains source URLs.

For a **new full media download**, enable the media picker's **Adaptive connections (experimental)** and set connections to eight. CLI equivalent after loading the new backend:

```bash
.runtime/ajdm --socket /tmp/ajdm-real-test-1000/ajdm.sock \
  add-media 'https://youtu.be/J70bt3I_1i4' \
  --format 313 --audio 251 --adaptive --connections 8 \
  --directory manual-test/downloads --wait
```

This admits a new job. It does not convert or resume an older fixed-mode job. Older backends reject the new adaptive media field; update the backend only while no jobs are active. Existing paused jobs retain their scheduling mode and bytes.

## Controlled conditions

The benchmark module's `benchmark-matrix NEW_DIRECTORY 6` exercises unthrottled, per-response-capped, shared-capped, response-delayed, changing-shared-rate and dropped-body fixtures. Six rounds balance all three-mode order permutations. Each completed file is independently rehashed; logs include requests, peak active responses, received-byte overhead, controller decisions and resource telemetry. Application delays/body truncation do not simulate TCP RTT or packet loss; actual loss/RTT acceptance requires a separately controlled network.

```bash
./gradlew --offline :ajdm-bench:installDist
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench \
  --state /tmp/ajdm-speed-fixture-state/ajdm.db \
  benchmark-matrix artifacts/my-local-speed-matrix-01 6
```

Use unique isolated state/report paths, never the live core database. Local matrix results and media sampling do not establish IDM superiority. A matched IDM comparison must use the same source/format, network/device/disk and alternating repeated full downloads, including separately reported extraction/mux time when applicable. Achieving ENG-03's >=95% target requires measured link/server capacity and convergence time; a low observed source rate alone cannot identify those capacities.
