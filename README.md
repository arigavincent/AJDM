# AJDM

The production CLI talks to one long-lived core over a private Unix socket. Build with `./gradlew :ajdm-cli:installDist`, run `ajdm-cli/build/install/ajdm/bin/ajdm daemon`, then use `add URL`, `list`, `pause ID`, `resume ID` or `cancel ID` through the installed `ajdm` executable. With an explicit `daemon --yt-dlp PATH`, `formats URL` discovers choices and `add-media URL --format ID` hands an individual stream to AJDM. See the [core/CLI guide](docs/CORE_CLI_IPC.md) and [media guide](docs/MEDIA_EXTRACTION.md). With `--ffmpeg PATH`, two-stream presets or `--format VIDEO_ID --audio AUDIO_ID` create durable paired jobs with local copy mux. See the [paired-job guide](docs/MEDIA_JOBS.md). Flat playlist listing, per-entry choices and durable bounded downloads are available through `playlist`, `add-playlist` and `playlist-status`; see the [playlist guide](docs/PLAYLISTS.md). Persistent queues and Windows/native OS qualification remain open.

AJDM's single-stream, durable-resume, fixed-parallel, adaptive scheduling, and coordinated source-refresh paths are implemented in Java 25. AJDM controls HTTP requests, range ownership, worker scheduling, retries, stall detection, pause/resume, cancellation, disk writes, and verification. No external program downloads the payload.

The project now follows [AJDM_PRD.pdf](AJDM_PRD.pdf) v1.0 for product scope and acceptance. The [requirement audit](docs/PRD_ALIGNMENT.md) covers all 61 functional IDs, defaults, release gates and outstanding product layers. This is an engine foundation, not yet a PRD-compliant desktop release: adaptive defaults/policy, portable no-clobber publication and cross-OS evidence need alignment. Known-size logical preallocation and journaled Overwrite move recovery are implemented. Public errors, disk admission and safe filename discovery/persisted conflict policies are implemented; [ENGINE_PLAN.md](ENGINE_PLAN.md) prioritizes the remaining gaps before further optional tuning.

## Desktop preview

A native JavaFX UI now shows live progress, MiB/s, ETA and pause/resume controls with stopped-job connection changes. It also provides direct-file, media-format and playlist pickers, light/dark styles and About. Run `./gradlew :ajdm-ui:installDist`, then `ajdm-ui/build/install/ajdm-ui/bin/ajdm-ui --socket /path/to/ajdm.sock`. See the [desktop guide](docs/DESKTOP_UI.md) for the existing manual-test socket/database and verification. This is a Linux preview; full PRD UI acceptance remains pending.

## Build and test

Install a Java 25 JDK and set `JAVA_HOME` to it. Use the checked-in Gradle wrapper; a system Gradle installation is unnecessary.

```bash
./gradlew check
python3 -m unittest discover -s tools/tests -v
```

The full private-IPC regression suite currently targets Linux. Windows/macOS CI runs portable model/HTTP checks and backend compilation; complete OS parity remains pending. On Windows, use `gradlew.bat :ajdm-model:check :ajdm-net:check` for that portable subset. The wrapper pins Gradle 9.3.1 and checks its official distribution SHA-256. Dependency versions and artifact checksums are committed. SQLite JDBC 3.53.4.0 supplies persistence; the transfer path uses Java's networking and filesystem primitives. Native access is enabled for the JDBC driver in tests and generated launchers.

The backend test pack now includes ArchUnit module rules, an enforced 80% engine/store line-coverage floor, seeded HTTP faults and extended kill/resume and scheduler campaigns. See the [verification guide](docs/BACKEND_VERIFICATION.md) for repeatable commands, evidence and outstanding PRD gates. Coverage reports are under `build/reports/jacoco/backendCoverage/`; ordinary `check` enforces the floor.

## Run a verified local download

```bash
./gradlew :ajdm-bench:run --args='--state /tmp/ajdm-state/ajdm.db demo /tmp/ajdm-resume-demo.bin'
```

The demo starts AJDM's deterministic local range server, downloads 8 MiB + 17 bytes, and checks the complete file's SHA-256. Choose an output path that does not already exist.

Download an ordinary HTTP(S) file:

```bash
./gradlew :ajdm-bench:run --args='download https://example.com/file.zip /tmp/file.zip'
```

To verify content, append a known 64-digit SHA-256 as the final argument. Without it, completion verifies byte count and file size; it does not claim checksum verification. The development runner requires an explicit safe destination and retains Ask/no-clobber behavior. Probe metadata can suggest a safe filename for product callers through `TransferRequest.inDirectory(...)`; server metadata never chooses a parent folder.

For URLs containing spaces or characters interpreted by Gradle's argument parser, use the installed runner directly:

```bash
./gradlew :ajdm-bench:installDist
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench download 'https://example.com/file?token=...' /tmp/file.zip
```

## Engine behavior

- Validated sources use a fixed target of four virtual-thread workers by default. Small transfers use fewer workers; the default minimum segment size is 1 MiB. HTTP/1.1 is the bulk-transfer default. The JDK connection pool provides no guarantee of worker-to-socket affinity.
- Probe verifies real range support. Range responses must match the requested offset, end, total size, and identity encoding.
- The scheduler assigns each unfinished segment to one worker. Every queued write needs a reservation inside that lease, and progress advances only after the positional write is acknowledged. Workers request bounded windows and accept no bytes outside their assignment. Sources without usable ranges, known size, or validators use a single stream.
- Transient network failures and server pushback have bounded retries with jitter and `Retry-After`. A partial ranged response retries from acknowledged bytes when the source validator permits it. A failed single-stream response restarts from zero. Malformed range headers retry once with an independent budget; 416 and file 403/410 coordinate one validated re-probe. See [retry/pause contract](docs/RETRY_AND_PAUSE.md).
- Platform threads perform ordered asynchronous positional writes from multiple network producers, with bounded, exclusively owned buffers. A union of acknowledged intervals counts distinct bytes, including writes above gaps. Partial writes are completed before acknowledging bytes. Disk backpressure does not count as a network stall.
- Shared engine limits default to eight active HTTP responses per hostname, 32 globally, and 64 MiB of in-flight buffer bytes. Response leases cover header waits, body lifetime, and each redirect hop. These limits apply across downloads in one engine; they do not count idle JDK pool sockets or all resident memory.
- Pause, cancellation, and terminal worker failures stop and join the whole worker generation. If the server rejects ranges with an unchanged validator, all old workers and queued writes settle before switching to a single stream, retaining only a confirmed contiguous prefix.
- Received, written, and durable progress are separate. Checkpoints run every five seconds by default, and on pause/stopped-state changes. An ordered writer barrier waits for its included writes and forces the partial file before SQLite commits their ranges. Writes submitted after that barrier are excluded. Received bytes count traffic in the current attempt; written/durable coverage includes restored bytes.
- Legacy explicit targets and the development runner use Ask/no-clobber. Product factories default to Rename and persist a numbered sibling before publication; a late collision retries naming without redownloading. Explicit Overwrite uses an atomic replacement of a verified staging hard link while retaining the partial through completion commit. Directories/observed symlinks cannot be overwritten. Rename/Ask require hard links. When staging links fail, explicit Overwrite commits a hash and move intent before atomically moving the partial itself; recovery verifies the final file if the partial is gone. Overwrite always requires atomic replacement. See [filename/policy contract](docs/FILENAMES_AND_CONFLICTS.md) for API and crash behavior.
- Known-size partials are logically preallocated before payload transfer and re-extended after a single-stream rewind. This may be sparse and does not reserve physical blocks. Only acknowledged/checkpointed intervals count as progress; unknown-size streams grow normally. See [file layout and publication](docs/FILE_LAYOUT_AND_PUBLICATION.md).
- Paused/failed transfers retain a uniquely named `.ajdm-*.ajdmpart` file and persisted download ID. Safe resume uses only saved coverage and revalidates size and source identity. Saved disjoint ranges are retained; every gap is fetched again regardless of partial-file length, and uncheckpointed tail bytes beyond the highest saved range are discarded. Canceled downloads are terminal and retain their partial file for inspection.
- URL schemes and request headers are validated. Engine-owned headers cannot be overridden. HTTPS downgrades are rejected; caller headers are dropped across origins. The runner prints neither signed source URLs nor credentials in error messages.
- Known-size transfers require full size +64 MiB usable space before payload starts/resumes; unknown-size transfers require the 64 MiB reserve. Refreshed sources pass the check before replacement commit. Rejection preserves saved bytes and reports AJDM-2003. This checks current capacity rather than reserving it; verified publication recovery needs no extra payload capacity. See [disk admission and errors](docs/ERROR_CODES.md).
- Download exceptions expose a public AJDM code, catalog-owned sentence and next action through `userMessage()`. The runner prints this safe presentation instead of exception details. The [catalog](docs/ERROR_CODES.md) lists PRD codes, engine extensions and remaining product integration.

## Pause and recover a download

The runner prints a download ID before transferring. The default database is in the platform's AJDM application-data directory; `--state <database>` chooses an isolated database. Keep the same database when resuming.

For interruption experiments, run the installed development runner directly after setting `JAVA_HOME` to Java 25:

```bash
./gradlew :ajdm-bench:installDist
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-state/ajdm.db download 'https://example.com/file.zip' /tmp/file.zip
```

Ctrl+C requests a graceful pause and checkpoint. A hard process kill recovers from the last committed checkpoint. After the old process exits:

```bash
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-state/ajdm.db list
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-state/ajdm.db resume '<download-id>'
```

In Java, construct `AjdmEngine(databasePath)`, call `handle.pause()` and await its completion, then call `engine.resume(id)` to obtain the new attempt's handle. A caller-requested pause ends transfer completion with `ErrorCode.PAUSED`. Automatic source/disk/auth pauses retain their specific error and action in history and completion. Graceful engine close pauses active transfers. Startup recovers interrupted states as PAUSED; it does not automatically start network requests.

Sources without range support, known size, or a usable validator restart from zero. Changed validators pause with persisted `SOURCE_CHANGED` and preserve the old partial. Explicit `engine.restart(id)` creates a new ID/partial while retaining the old record and bytes; keep partial requires no mutation. Missing/truncated partials clear stale coverage and fail explicitly; start a new download rather than padding lost bytes and trusting them. Recovery reconstructs pending ranges from the saved interval ledger, retaining higher completed ranges while requesting only gaps. File length, sparse allocation, and bytes left in gaps never establish completed coverage.

Final file creation has a persisted ready-to-publish marker. A crash after that marker is committed can finish from the verified partial file without contacting the source. Committed Rename destinations and staged Overwrite recover by the same marker and file identity. Ask and Rename protect unrelated existing files; explicitly selected Overwrite replaces only at verified publication. See [publication recovery](docs/FILENAMES_AND_CONFLICTS.md).

SQLite runs with WAL and `synchronous=FULL`, chosen to retain committed transactions under SQLite's documented durability model. See [SQLite synchronization guidance](https://www.sqlite.org/pragma.html#pragma_synchronous). The database uses an exclusive engine ownership lock and private permissions on POSIX filesystems. Source/resolved URLs needed for resume are stored in that private database; Authorization, Cookie, Referer, and arbitrary API-key headers are excluded. Credentials remain in memory during same-engine pause/resume; supply replacement headers through `resume(id, headers)` after restarting when required.

## Parallel settings and controlled comparison

`TransferOptions.defaults()` requests four workers. Use `withConnections(1)` for a baseline or `withParallelism(count, minimumSegmentBytes)` to change both settings. Counts are bounded to 1–32; actual workers depend on remaining bytes and shared request limits. The older 10/11-argument constructors retain one-worker behavior. New options persist with the download. SQLite schemas v1/v2/v3/v4 migrated transactionally to v5; existing downloads retain their previous connection/mode settings, selected targets and Ask policy. Current schema v8 also preserves media origins, paired parents and playlist item intent during migration.

`new AjdmEngine(database, new EngineLimits(8, 32, 64 * 1024 * 1024))` sets shared limits. The same active-response and buffer limits apply to fixed and adaptive transfers.

The [controlled comparison report](artifacts/phase3-parallel-comparison/report.md) records three alternating trials downloading 4 MiB + 17 bytes from a local server capped at 512 KiB/s per response. All six outputs matched SHA-256. Median completion times were **8.101 s with one worker** and **2.192 s with four workers**, a measured **3.70× speedup**. [Raw samples](artifacts/phase3-parallel-comparison/samples.csv) include elapsed time, traffic, concurrency, and hashes. This fixture does not impose a shared link cap; it does not establish performance against IDM or over 4G/5G.

Reproduce it in a new report directory:

```bash
./gradlew :ajdm-bench:installDist
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-compare/ajdm.db compare /tmp/ajdm-compare-report
```

The runner writes verified test files plus `samples.csv` and `report.md`. Existing output files remain protected.

## Adaptive scheduling

The [October speed milestone](docs/SPEED_MILESTONE.md) updates new adaptive startup to four, adds expiring network-scoped host hints and protected laggard splitting/independent-pool recycling. Existing jobs retain their persisted startup count. Fixed remains the default. The final build passes 539 tests and the coverage gate; native/mobile/IDM qualification remains open. See the [results and preview](artifacts/speed-milestone-20261013/REPORT.md). The earlier benchmark results below remain historical evidence.


Enable it explicitly with `options.withAdaptiveParallelism(maximumConnections, minimumSegmentBytes)`. Existing constructors, `defaults()`, and `withParallelism(...)` retain fixed mode for reproducible comparisons. The `connections` field is a maximum in adaptive mode; AJDM starts at up to four lanes for new jobs (two for migrated legacy jobs), or a revalidated host hint, with the maximum also bounded by configured host/global limits and remaining work.

```bash
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-adaptive/ajdm.db adaptive 'https://example.com/file.zip' /tmp/file.zip
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-adaptive-compare/ajdm.db compare-adaptive /tmp/ajdm-adaptive-report
```

AJDM measures acknowledged useful throughput and per-worker speeds every 250 ms. Growth trials double the target up to the configured maximum. Each trial follows a two-second baseline, one second of settling, and two seconds of measurement. A gain below 15% in doubling trials or 5% in one-lane trials rolls back admissions; strong regressions can reduce the settled count. Plateau trials enter a ten-second cooldown and remain held until a sustained throughput change permits another trial; inconclusive doubling can switch to one-lane trials. Server pushback reduces admissions. A full writer queue must persist for 500 ms before it suppresses growth or rolls back a trial, so brief checkpoint/write bursts do not freeze adaptation.

Idle admitted workers choose a victim by estimated completion time and divide its unrequested tail using smoothed speeds. A scheduler lock protects the complete HTTP window before a request starts; its boundary stays protected through retries and queued writes. The old worker retains everything below the split. Admission reductions take effect at validated, settled window boundaries, and unfinished tails return to the pending queue. These handoffs reuse acknowledged bytes without duplicate fetching.

Adaptive request windows target roughly two seconds at the measured worker speed, with a 1 MiB floor and the configured `windowBytes` cap. Smaller explicit caps and final tails are respected. Actual latency can grow when a link slows suddenly; current responses finish or hit the ordinary stall/retry policy. Timed laggard detection can split only an unrequested tail or recycle its isolated lane pool; acknowledged prefixes and protected ownership remain authoritative. Network-scoped host hints expire after 30 days. See the milestone contract for limits and qualification status.

`handle.adaptation()` returns target admissions, busy worker count, queued write bytes, per-worker smoothed speeds, and at most 256 attempt-local decisions. The trace excludes source URLs and headers and restarts on resume or a refreshed worker generation. It records trials, acceptance, rollback, pushback, network changes, and tail stealing. Fixed and single-stream fallback transfers may return an empty trace.

The adaptive comparison runner retains verified files, raw times, observed response peaks, and decision CSVs for a slowing TCP peer, a shared bandwidth cap, and per-response caps. Its report includes conditions and both gains and losses; adaptive mode is not assumed to beat a known-good fixed count.

The [final local comparison](artifacts/phase4-adaptive-comparison-final/report.md) uses three alternating trials per scenario. Slowing-peer medians fell from **10.199 s fixed to 8.637 s adaptive** (15.3% less completion time). Shared-cap medians were 8.040/8.333 s (3.6% slower adaptive, two lanes rather than four); per-response-cap medians were 8.287/9.862 s (19.0% slower adaptive). Bounded doubling reduced the initial policy's stable-cap penalty from 52.65% to 19.00%, but cautious startup still costs time on short, stable transfers. All 18 final files passed independent SHA-256, size, and traffic checks; [verification and spread](artifacts/phase4-adaptive-comparison-final/verification.md) and [raw samples](artifacts/phase4-adaptive-comparison-final/samples.csv) are retained. The initial additive-policy report is preserved for comparison.


## Expired URL and header refresh

Attach a `SourceRefresher` when the caller can re-extract a direct payload URL. AJDM invokes it for HTTP 401, 403, 404, or 410 from the source or payload requests. Proxy authentication (407) pauses for caller credentials; payload 416 coordinates a validated re-probe. Protocol and ordinary 429/5xx failures retain bounded handling. Without a provider, file 403/410 gets one fresh-probe retry before access denial. Certificate rejection fails with AJDM-2011 and never bypasses verification.

```java
// The caller obtains these from its extraction layer, never from a payload downloader.
SourceRefresher refresher = context -> {
    context.checkCanceled();
    return new DownloadSource(replacementUri, replacementHeaders);
};
TransferHandle handle = engine.start(request, refresher,
        new SourceRefreshPolicy(3, Duration.ofSeconds(30)));
```

The replacement carries a complete header set. Old Authorization, Cookie, Referer, and arbitrary API-key values are not merged into it. URL and header validation is shared with initial requests; HTTPS downgrade is rejected, and redirect origin checks still apply. Source/request/context diagnostic strings redact URLs and credentials. Caller exception messages and causes are excluded from refresh errors.

AJDM stops and joins the failed worker generation, drains and forces accepted writes, and calls the provider once for that expiration episode. It probes the replacement before committing anything. Retaining bytes across a replacement requires matching known size, matching strong ETag, and proven range support on both sources. A format ID, Last-Modified, weak/missing ETag, or partial-file length does not establish cross-URL identity. A mismatch or insufficient proof fails explicitly and retains the previous source and coverage. With no retained bytes, a freshly probed source can start without range/validator support; known size or strong-validator changes are still rejected.

The validated source URL, resolved URL, probe, and restart-safe headers commit in one SQLite transaction that preserves the range ledger. AJDM then builds a new worker generation for the remaining gaps. Adaptive measurements restart for the new generation, which may use a different CDN. HTTP retries keep their own limits; replacement candidates that are already expired consume the bounded refresh budget. Defaults allow three refresh calls per running attempt and 30 seconds per callback, including waiting for an earlier callback to exit.

Same-engine `resume(id)` retains the provider and current in-memory credentials. After reopening the engine, attach it with `resume(id, refresher)` or `resume(id, headers, refresher, policy)`. The provider and sensitive headers are not persisted. Saved signed URLs remain in the private state database because resume requires them. A validated saved source can resume with `resume(id, headers)` without calling extraction again.

Pause, cancellation, and timeout stop waiting for a callback and reject any late result. Providers should honor `context.checkCanceled()` and interruption. AJDM cannot forcibly terminate arbitrary caller code that ignores cancellation; the per-download gate prevents a second callback from overlapping it within the same engine, and a later attempt times out if that callback has not exited. Late callbacks cannot commit sources or write payload bytes.

The [phase-5 validation report](artifacts/phase5-source-refresh/report.md) records identity, failure, credential, shared-limit, and process-kill coverage. The core now supplies extraction refresh, format listing and local copy mux through the [media layers](docs/MEDIA_JOBS.md); live-platform acceptance remains separate.

## Three-mode performance matrix

```bash
ajdm-bench/build/install/ajdm-bench/bin/ajdm-bench --state /tmp/ajdm-matrix/state.db benchmark-matrix /tmp/ajdm-new-matrix 6
python3 tools/verify_performance_matrix.py /tmp/ajdm-new-matrix
```

Six rounds balance all six single/fixed/adaptive run orders. Six profiles cover unthrottled loopback, per-response and shared caps, HTTP response delay, changing shared rate, and incomplete bodies. Runs save verified outputs, incremental samples, percentiles, request boundaries, decisions, and resource telemetry. `handle.queuedWriteBytes()` now exposes submitted unfinished disk-write bytes in every mode.

The [phase-6 matrix](artifacts/phase6-performance-matrix/report.md) contains 108 measured transfers and three warmups. All passed [independent verification](artifacts/phase6-performance-matrix/verification.md). Per-response-cap medians were **16.506/4.345/8.691 s** single/fixed/adaptive; shared-cap medians were **8.262/8.034/8.314 s**. Fixed wins five of six scenario medians; single wins unthrottled. Adaptive loses to fixed across these short, mostly stable profiles. [Measured findings](artifacts/phase6-performance-matrix/analysis.md) explain conservative startup, extra request overhead, the 250 ms completion poll, and retained completion-delay outliers. [Standalone figure](artifacts/phase6-performance-matrix/completion-times.svg) shows every sample.

Client-only capture is available through `benchmark-download <single|fixed|adaptive> <url> <output> <sha256>`. `serve-benchmark <profile> [port]` serves the deterministic file on loopback for separately operated tools. The [benchmark protocol](docs/BENCHMARK_PROTOCOL.md) and external CSV/importer prepare same-host Windows IDM/4G/5G measurements. The importer requires verified identity, matched conditions, balanced pairs, stable versions, and at least ten samples per tool. Missing metrics remain unknown. Actual IDM/mobile measurements are still pending; the local HTTP delay and incomplete bodies do not represent real RTT or packet loss.

## Modules

| Module | Responsibility |
|---|---|
| `ajdm-model` | Requests, options, progress, states, results, errors |
| `ajdm-net` | Replaceable HTTP transport, probing, response validation, cancellation |
| `ajdm-store` | Serialized SQLite transactions, coverage ledger, history, startup recovery |
| `ajdm-engine` | Segment leases, fixed/adaptive workers, controller trials, shared budgets, bounded disk writer, checkpoints, recovery, verification |
| `ajdm-bench` | Deterministic fault server, integration tests, development runner |
| `ajdm-core` / `ajdm-ipc` | Long-lived core ownership and private client commands |
| `ajdm-media` | Metadata extraction, media selection and local mux |
| `ajdm-cli` | Production command-line client and daemon launcher |
| `ajdm-ui` | Native JavaFX client, live progress and file/media/playlist pickers |

## Current validation and next milestones

The [connection-resume check](artifacts/prd-alpha-resume-connections/report.md) subsequently passed **438 Java tests**, zero failures/errors/skips, retaining the same 96 kill checks. Paused file/media jobs now accept `resume ID --connections 8` without losing saved bytes; see [usage](docs/RESUME_CONNECTIONS.md).

On Linux, the prior [playlist check](artifacts/prd-alpha-playlists/report.md) passed **431 Java tests with zero failures, errors or skips**. Its 96 forced-kill checks comprise 95 bit-identical completion recoveries and one intentional source-change pause preserving old bytes/reason. The 100-item local playlist completed in 201.967 seconds with the real two-second extraction interval, no rejected starts, and every output verified byte-for-byte. This does not establish live-site rate-limit acceptance. Existing lossless MP4/MKV, safe filenames, retry/pause and source renewal tests remain in the full check. See [current summary](artifacts/prd-alpha-playlists/test-summary.json). Earlier [paired media](artifacts/prd-alpha-media-jobs/report.md), [retry/pause](artifacts/prd-alpha-retry-pause/report.md), 200 single-worker and 50 fixed-parallel checks remain historical evidence. Unchanged Python tools previously passed seven tests.

Run the standalone importer/verifier tests with `python3 -m unittest discover -s tools/tests -v`; these use synthetic data to validate tooling and do not measure IDM.

Tests cover correct/faulted transfers, bounded/partial writes, checkpoint barriers and force failures, safe redirects, source identity, pause/reopen/resume, missing/truncated partials, unsupported resume, SQLite rollback, ownership, private state-file permissions, and credential exclusions. Separate JVM workers are killed before/after file force, after metadata commit, before/after publication, and at seeded varied download offsets; recovered files must match SHA-256 and reuse exactly the saved prefix.

The regular suite uses ten single-worker kills, ten fixed-parallel kills, ten adaptive kills after handoffs, five fixed boundaries, and eighteen refresh-boundary kills (three repetitions across fixed/adaptive modes and three boundaries). Increase either matrix for extended validation:

```bash
./gradlew check -PcrashRuns=200 -PparallelCrashRuns=50 -PadaptiveCrashRuns=50 -PrefreshCrashRuns=10
```

Process-kill tests exercise application crashes. Physical power loss and cross-OS recovery still need separate validation; checkpoint timing can slip while storage stalls.

The production CLI/core/IPC now supports individual/paired media and durable playlists plus caller decisions, while native no-clobber/filesystem qualification remains open. Adaptive policy, connection caps and persistent host profiles still require the specified acceptance work. Full JavaFX acceptance, live playlist/tool lifecycle acceptance, queues/scheduling, proxies, browser integration and installers remain pending; see [PRD alignment](docs/PRD_ALIGNMENT.md) for requirement-level status and release order. No throughput advantage over IDM has been measured yet.

Optional offline mirror: set `AJDM_MAVEN_REPOSITORY` or pass `-PajdmMavenRepository=file:///path/to/maven-repository`. Ordinary builds resolve dependencies from Maven Central.
