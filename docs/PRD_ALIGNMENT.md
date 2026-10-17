# AJDM PRD alignment audit

Audit date: 2 October 2026. Baseline: [AJDM_PRD.pdf](../AJDM_PRD.pdf), v1.0, dated 1 October 2026, all 16 pages reviewed. Page references below refer to the PDF's printed page numbers. [architecture.md](../architecture.md) supplies compatible technical detail; the PRD controls product scope, release defaults and acceptance. [ENGINE_PLAN.md](../ENGINE_PLAN.md) tracks implementation work.

**The project has a tested engine, core/CLI and media extraction foundation, but is not yet PRD compliant or ready for a PRD release milestone.** Existing engineering phases are narrower than PRD milestones M0–M6. Passing local tests is not equivalent to satisfying every requirement's acceptance criteria. Alpha slices implement public errors, disk admission, safe filenames/policies, resilient retries private core/CLI integration and extraction-only discovery, individual/paired media downloads and durable playlist orchestration. See [current evidence](../artifacts/prd-alpha-resume-connections/report.md).

## October speed milestone update

The [speed milestone](SPEED_MILESTONE.md) adds initial/max separation (new initial four, legacy saved two), 16/64 response budgets, 512 KiB new default segments, controlled doubling/+1 growth, link-plateau hold, multiplicative pushback, timed protected tail splitting and isolated pool recycling, plus bounded network-scoped thirty-day host hints. These close implementation portions of ENG-02/03/04/12/19; the older table below is the previous audit snapshot. Physical connection counts, convergence, cold/warm/mobile/IDM measurements and complete release criteria remain acceptance-pending. No requirement is accepted solely from this update.

## Direction retained

Java 25, AJDM-owned HTTP requests/scheduling/writing/recovery, virtual network threads, HTTP/1.1, positional writes, SQLite and flush-before-commit remain compatible with sections 1, 7 and 10. `yt-dlp` must only extract formats, direct URLs and headers; `ffmpeg` must only mux completed local streams with `-c copy`. The extraction adapter exists; the local copy mux adapter also exists. Standard networking primitives are permitted; another downloader must never own AJDM's transfer orchestration.

The product is a local desktop application for Windows 10/11 x64, macOS 12+ Intel/Apple Silicon, and Ubuntu 22.04+/Pop!_OS/Fedora. Android/iOS, cloud accounts, DRM bypass, re-encoding, FTP and torrents stay outside v1.0. Full non-DRM HTTPS format selection remains the second core objective, alongside measured speed and resilient resume.

## Status and evidence key

- **Partial**: a relevant implementation exists, but behavior, integration or acceptance evidence is incomplete.
- **Implemented**: the scoped behavior and automated tests exist; broader product/release gates remain separate.
- **Missing**: no implementation of the requirement was found.
- **Deferred**: the PRD targets v1.1 or later; keep it outside the v1.0 critical path.

No row is marked accepted until its complete acceptance criteria have evidence. `M`, `S`, `C` preserve PRD Must/Should/Could priorities, including a Must's specified earliest release.

Evidence anchors used in the tables:

| Anchor | Implementation / evidence |
|---|---|
| Probe / HTTP | [Probe](../ajdm-net/src/main/java/io/ajdm/net/Probe.java), [ProbeResult](../ajdm-model/src/main/java/io/ajdm/model/ProbeResult.java), [transport](../ajdm-net/src/main/java/io/ajdm/net/JdkHttpTransport.java), [response checks](../ajdm-net/src/main/java/io/ajdm/net/ResponseChecks.java), [safe names/policies](FILENAMES_AND_CONFLICTS.md) |
| Engine / writes | [AjdmEngine](../ajdm-engine/src/main/java/io/ajdm/engine/AjdmEngine.java), [bounded writer](../ajdm-engine/src/main/java/io/ajdm/engine/BoundedFileWriter.java), [scheduler](../ajdm-engine/src/main/java/io/ajdm/engine/SegmentScheduler.java) |
| Settings / controller | [TransferOptions](../ajdm-model/src/main/java/io/ajdm/model/TransferOptions.java), [EngineLimits](../ajdm-engine/src/main/java/io/ajdm/engine/EngineLimits.java), [AdaptiveController](../ajdm-engine/src/main/java/io/ajdm/engine/AdaptiveController.java) |
| Recovery / refresh | [checkpoints](../ajdm-engine/src/main/java/io/ajdm/engine/Checkpoints.java), [SQLite store](../ajdm-store/src/main/java/io/ajdm/store/SqliteDownloadStore.java), [refresh tests](../ajdm-bench/src/test/java/io/ajdm/bench/SourceRefreshTest.java), [phase-5 evidence](../artifacts/phase5-source-refresh/report.md) |
| Validation | [current alpha summary](../artifacts/prd-alpha-resume-connections/test-summary.json): 438 Java tests, 96 forced-kill checks (95 bit-identical completions + one intentional source-change pause), 10,000 hostile filename cases; Linux only. Logical preallocation, journaled Overwrite, reasoned pauses/retries and independent core/CLI controls/recovery and media expiry/kill/reopen renewal, paired controls, lossless MP4/MKV, playlist intent/control recovery and a 100-item local rate gate are tested. Prior [phase-6 summary](../artifacts/phase6-performance-matrix/test-summary.json) includes 188 Java/7 Python tests. Earlier 200 single-worker kills and 50 fixed-worker kills are historical, not a current unified release-gate result. |
| Performance / CLI | [matrix analysis](../artifacts/phase6-performance-matrix/analysis.md), [independent verification](../artifacts/phase6-performance-matrix/verification.md), [EngineRunner](../ajdm-bench/src/main/java/io/ajdm/bench/EngineRunner.java), [external protocol](BENCHMARK_PROTOCOL.md) |

## Functional requirement traceability

### Transfer engine — PRD p. 7

| ID | Priority / release | Status | Existing behavior and remaining acceptance work |
|---|---|---|---|
| ENG-01 | M / 0.1 | Partial | Probe determines final URL, size, actual range support, validators, sanitized filename and content type; HEAD/GET/redirect/empty metadata cases pass. Collect >=100 URL / >=20 host corpus and healthy <=1.5 s probe evidence. |
| ENG-02 | M / 0.1 | Partial | Fixed parallel ranges and hashes tested; hard cap 32 enforced. Fixed starts at 4, adaptive at 2; no separate initial-4/default-max-16 settings. Align options and persisted settings without changing old downloads silently. |
| ENG-03 | M / 0.5 | Partial | Settled doubling trials, rollback and pushback exist; direct and single/paired media now expose explicit adaptive mode in CLI/UI, persisted across pause/reopen. Missing specified slow-start + hill-climb, explicit link-bound detection and multiplicative decrease/reset tracking. Prove >=95% of min(link, N × C) within 10 s. |
| ENG-04 | M / 0.5 | Partial | Largest-ETA stealing protects active windows and queued writes. Missing timed <0.35× median/3 s upper-half split and <0.10× median/5 s physical connection recycle. Earlier slow-peer gain does not prove <=10% penalty against an identical no-slow-peer baseline. |
| ENG-05 | M / 0.1 | Partial | Direct bounded positional writes, known-size logical preallocation and `.ajdmpart`, no merge/copy. Rename/Ask use no-clobber hard links. Overwrite uses a staging link or journaled direct atomic move when links fail. Physical block reservation, portable no-clobber support and cross-OS filesystem qualification remain open; measure peak extra disk usage <=size+64 MiB. See [layout contract](FILE_LAYOUT_AND_PUBLICATION.md). |
| ENG-06 | M / 0.1 | Partial | Durable ledger, fsync-before-commit, pause/reopen and kill recovery tested. Default checkpoint 5 s. Storage stalls can delay it; <=5 s redownload is not yet proven. Rerun consolidated 200-kill gate on final engine; distinguish power loss from process kills. |
| ENG-07 | M / 0.1 | Partial | Core If-Range/validator failures pause with SOURCE_CHANGED and retain old metadata/bytes. Explicit restart creates a new ID/partial and keeps the old record; source-pause reason and coverage commit together. GUI now offers a confirmed Restart on source-change pauses, retaining previous files. Full live-source/native acceptance remains open. See [retry/pause contract](RETRY_AND_PAUSE.md). |
| ENG-08 | M / 0.1 | Partial | Single-stream fallback including ranged-200 and unknown-size tested; also conservative fallback without validators. Add explicit resume-capability presentation in core/UI. |
| ENG-09 | M / 0.1 | Partial | Core table now includes independent malformed-range retry-once, coordinated 416/file 403/410 re-probes, reasoned disk/auth pauses, bounded network retries and Retry-After. Certificate failures are fatal AJDM-2011; uncertain/transient TLS uses strict verification on each bounded retry. HTTP fixtures, certificate/status tests and kill checks cover core actions. Credential prompts, Waiting for network UI and native/real-volume qualification remain pending. |
| ENG-10 | M / 0.1 | Partial | Default 15 s no-byte watchdog and stop-sending tests exist. Add explicit acceptance evidence at production 15 s setting; distinguish disk backpressure from network stalls. |
| ENG-11 | S / 0.5 | Missing | No global/per-download rate limiter. Fixture throttling limits the source, not AJDM. Add limiter and +/-5% measurements. |
| ENG-12 | M / 0.5 | Partial | Shared active-response budgets and stress/cancellation tests exist. Defaults 8 per host / 32 global differ from 16/64. Physical active TCP connection bounds and pool behavior need validation; logical workers are not sockets. |
| ENG-13 | M / 0.8 | Missing | No explicit system/manual HTTP/SOCKS5 configuration/authentication or proxy acceptance tests. JDK implicit system behavior does not establish this contract. |
| ENG-14 | S / 1.0 | Partial | Caller-supplied validated headers can carry Authorization and Cookie; secret persistence exclusions and redirects tested. No credential UI, managed cookie flow or complete protected-server acceptance. |
| ENG-15 | S / 1.1 | Deferred | Mirror balancing/failover absent; outside v1.0. |
| ENG-16 | S / 1.0 | Implemented | Supplied SHA-256 verification and mismatch tests exist; public AJDM-3005 text/action now defined and download exceptions rendered safely in the runner. Product UI integration remains outstanding. |
| ENG-17 | M / 0.1 | Partial | Safe metadata filename factory, Windows device/punctuation rules, UTF-16/UTF-8/code-point bounds, fresh explicit-target validation and persisted Rename/Ask/explicit Overwrite implemented. 10,000 hostile names, collisions, migrations and five publication kills pass on Linux. Native three-OS acceptance and product Ask prompt remain open. |
| ENG-18 | M / 0.1 | Implemented | FileStore-backed, overflow-safe full-size+64 MiB check before starts/resumes and validated replacement-source commit; rejection requests no payload, preserves saved coverage and reports AJDM-2003. Unknown sizes require the reserve only. Deterministic injected-capacity tests pass; real full-volume/mid-transfer behavior remains a separate validation gap. |
| ENG-19 | S / 0.5 | Missing | No persistent host capabilities/best concurrency. Add schema migration, reuse, 30-day expiry and stale/network-change revalidation. |
| ENG-20 | C / 1.0 | Missing | No last-2-MiB duplicate race. Optional; defer until ownership and duplicate traffic accounting are safe; disable in data-saver. |

### Media — PRD p. 8

| ID | Priority / release | Status | Remaining work |
|---|---|---|---|
| MED-01 | M / 0.8 | Partial | Bounded extraction-only yt-dlp subprocess/JSON adapter and individual-format engine handoff implemented. Durable playlist flow implemented; >=30 videos across >=5 sites live corpus pending. See [media guide](MEDIA_EXTRACTION.md). |
| MED-02 | M / 0.8 | Partial | CLI/IPC full non-DRM HTTPS metadata table, exact/~estimated sizes, HDR and unknown values implemented independently of filtering. JavaFX picker/live-corpus completeness acceptance pending. |
| MED-03 | M / 0.8 | Partial | CLI default 2160, ceiling up to 4320 and Show all implemented; persisted setting/UI acceptance pending. |
| MED-04 | M / 0.8 | Partial | Deterministic BEST/P1080/P720/AUDIO and custom pair previews implemented, including stable ties and estimates. Individual and paired plans download through AJDM; local copy mux and durable parent controls/recovery implemented. Full picker/live acceptance pending. |
| MED-05 | M / 0.8 | Partial | Parallel AJDM children, durable parent, local-only ffmpeg copy mux, saved-hash atomic publication and failure retention implemented. Real small MP4/MKV decode and equal stream hashes pass; 20 GB SSD timing, real 4K and native qualification pending. See [paired-job guide](MEDIA_JOBS.md). |
| MED-06 | M / 0.8 | Partial | [Durable CLI/IPC playlists](PLAYLISTS.md): flat listing, all/range/per-entry presets/custom pairs, safe skipped reasons, extraction concurrency <=3, persisted two-second starts, item intent/controls/reopen and four kill boundaries. 100-item local rate fixture passes with all bytes verified; live 100-item/site rate-limit acceptance and picker UI remain pending. |
| MED-07 | M / 0.8 | Partial | Schema v6 origin/format identity and extraction adapter reattach after reopen. Real HTTPS forced expiry and production daemon kill/reopen renewals complete exactly. Media ID/codec match plus engine size/strong-validator proof guard bytes. Renewal spacing, network-change detection and live media corpus pending. |
| MED-08 | S / 1.1 | Partial (pulled forward, 17 Oct 2026) | AJDM's engine downloads clear video-on-demand HLS media playlists itself: parallel parts, verified lengths/ranges, in-order join into the engine's partial file, durable resume keyed to playlist structure (renewed signed links resume), pause/resume through the normal download record. Extraction offers `m3u8`/`m3u8_native` formats (container `ts`); a plain file wins ties; split HLS video+audio mux through the explicit, sniffed-demuxer ffmpeg path. AES-128 HLS whose 16-byte key the site serves over HTTPS to any player (KEYFORMAT identity) is decrypted per part, by the user's decision of 17 Oct 2026. Refused: master/live playlists and real DRM (SAMPLE-AES, SAMPLE-AES-CTR, any non-identity key format such as Widevine, FairPlay or PlayReady). Expired or revoked playlist, part or key links (401/403/404/410) are renewed through the media refresher mid-transfer, keeping joined parts. A single HLS stream is stream-copied into MP4, M4A or MKV beside its transport stream after completion (the record follows it; any failure keeps the playable .ts), and split HLS video+audio mux into one file. DASH representations (`http_dash_segments`) are downloaded the same way from extraction's fragment list, stored with the download as a playlist of absolute HTTPS links (schema v11), and published in their own container (fMP4/WebM, no remux); paired DASH video+audio mux as usual. Expired DASH links are renewed with a re-extracted fragment list (SourceRefresher.refreshStream). A remux interrupted by a crash or shutdown is finished when the core starts; pending remuxes list as MERGING. Merged and remuxed outputs are owner-only like every download. yt-dlp still never downloads payloads. |
| MED-09 | S / 1.1 | Deferred | Subtitles/thumbnail/metadata embedding absent. |
| MED-10 | M / 0.8 | Partial | Explicit trusted executable override and user-data bin resolution exist. Bundled extractor, weekly/failure-triggered update, SHA-256 verification and rollback pending. |
| MED-11 | M / 0.8 | Partial | Root/format DRM detection/refusal with AJDM-4004 tested; live DRM corpus and picker integration pending. |
| MED-12 | S / 1.0 | Partial | Explicit session-only exported Netscape cookie-file selection, private subprocess copies and scoped metadata cookie handoff implemented. Automatic browser-store import and cross-OS ACL qualification pending; authenticated live-site corpus unqualified. |
| MED-13 | S / 0.8 | Partial | Selection preview reports combined exact/approximate/unknown size before admission; paired job flow implemented; UI/live acceptance pending. |

### Queue and scheduler — PRD p. 8

| ID | Priority / release | Status | Remaining work |
|---|---|---|---|
| QUE-01 | M / 0.5 | Missing | Multiple queues/Main, default three concurrent downloads, reorder and priority. Engine segment scheduling is not a product queue. |
| QUE-02 | M / 0.8 | Missing | Day/time schedule windows and strict close-window pause. |
| QUE-03 | M / 0.8 | Missing | None/sleep/hibernate/shutdown/run-command with cancelable 60 s countdown. |
| QUE-04 | S / 0.8 | Missing | Normalized-URL or ETag+size duplicate prompt. |
| QUE-05 | S / 0.8 | Missing | Configurable item retry attempts/delay beyond engine per-window retry. |
| QUE-06 | S / 0.8 | Missing | Category default folders: Video, Audio, Documents, Software, Compressed. |

### UI — PRD p. 9

| ID | Priority / release | Status | Remaining work |
|---|---|---|---|
| UI-01 | M / 0.5 | Partial | JavaFX list/progress/MiB speed/ETA/state, filters/search, state-aware pause/resume/retry/cancel/restart/delete and context actions implemented. Delete confirms entry-only vs owned-file removal. Queue column, 4 Hz subscriptions and complete acceptance pending. |
| UI-02 | M / 0.5 | Partial | Direct URL, explicit clipboard paste, folder, filename/checksum and connections implemented. Persistent queue/now-later admission pending. |
| UI-03 | M / 0.8 | Partial | Human-readable all-format quality table, automatic/explicit audio pairing, audio-only/silent/combined choices, advanced presets/IDs, preview sizes; paged playlist checkboxes/ranges and per-entry presets implemented. Full live-corpus acceptance and per-entry exact-format UI pending. |
| UI-04 | M / 0.5 | Partial | Written/durable bytes, whole-job speed history and stopped-job connection override implemented. Segment map, actual connection telemetry and event log pending. |
| UI-05 | M / 0.8 | Partial | Session socket/folder/connections/theme defaults implemented. Persistent grouped/searchable settings covering architecture §14 pending. |
| UI-06 | M / 0.8 | Partial | Independent UI-client shutdown leaves the separate daemon alive. Tray, pause-all, limit toggle, open folder and full background UX pending. |
| UI-07 | M / 0.5 | Partial | Light/dark JavaFX styles use PRD brand tokens. System tracking, persistence and full contrast qualification pending. |
| UI-08 | M / 0.8 | Missing | Completion/failure desktop notifications with code/action. |
| UI-09 | M / 0.5 | Partial | Native controls, explicit names/focus and Ctrl+N/Ctrl+R/F11 implemented. Complete keyboard, screen-reader and AA contrast qualification pending. |
| UI-10 | S / 0.8 | Missing | Drop URLs/text-file bulk add. |
| UI-11 | S / 1.1 | Deferred | Preview uses English strings; resource bundles and Swahili remain pending. |
| UI-12 | M / 1.0 | Partial | About includes unchanged original PRD logo, version and exact §5.4 dedication. Full packaged license notices and release decisions pending. |

### Browser / CLI / diagnostics — PRD p. 9

| ID | Priority / release | Status | Remaining work |
|---|---|---|---|
| BRW-01 | M / 1.0 | Missing | Chrome/Edge/Brave MV3 + Firefox native messaging, >default 1 MiB interception, URL/referrer and consent-compatible cookies. |
| BRW-02 | M / 1.0 | Missing | Download with AJDM context menu and current-page video picker action. |
| BRW-03 | M / 1.0 | Missing | Allow/deny lists, per-site disable, fail-open when core unavailable. |
| BRW-04 | S / 1.1 | Deferred | Playable-media detection and badge. |
| CLI-01 | S / 0.1 | Partial | Production picocli add/formats/add-media/list/pause/resume/cancel/restart/remove/daemon and numeric exits use one private-IPC core. Remove keeps files by default, with explicit --delete-files for stopped owned files. Individual media choices and origin renewal tested. Playlist listing/selection/download/status and entry previews tested; Windows ACL/native OS and product acceptance remain open. |
| DIA-01 | M / 0.5 | Partial | IDs and bounded redacted controller events exist. No rolling production logs/per-download trace contract. |
| DIA-02 | M / 0.5 | Partial | Benchmark CSV telemetry/decisions exist. No durable/exportable production per-download speed/connection timeline. |
| DIA-03 | S / 1.0 | Missing | One-click redacted diagnostic bundle with settings/logs/host profile/controller decisions. |
| DIA-04 | S / 1.1 | Deferred | Signed application auto-update with rollback. |
| DIA-05 | C / 1.0 | Missing | Optional opt-in crash reporting; current project sends no telemetry. Keep default off. |

## Defaults to reconcile — PRD Appendix A, p. 15

| Setting | Current engine | PRD target / action |
|---|---|---|
| Initial / maximum workers | Fixed 4; adaptive starts 2, usually max 4 | Separate initial 4 (range 1–8) and default maximum 16, hard cap 32; learned-host override later |
| Mode | Fixed default, adaptive opt-in | Product flow ramps 4→8→16 when useful; qualify PRD adaptive policy before product default activation. Fixed remains a benchmark/control mode, not a replacement for ENG-03. |
| Host / global cap | 8 / 32 active responses | 16 / 64 across downloads; test actual active transport connections too |
| Concurrent downloads | No admission queue | 3 globally and per queue |
| Minimum segment | 1 MiB | 512 KiB; 64 KiB split alignment |
| Request windows | Fixed 4 MiB; adaptive 1–4 MiB by default, ~750 ms speed sizing, with smaller explicit request caps respected | Adaptive 1–16 MiB, speed×2 s in architecture; <=10 MiB on chunk-limited hosts; final tails may be smaller |
| Checkpoint | 5 s default | Retain fsync-before-commit; measure worst-case checkpoint/redownload lag |
| Connect / stall timeout | 10 / 15 s | Retain and verify production defaults |
| Retry | 10, 500 ms–30 s full jitter | Retain; complete classification/action table |
| Controller | 250 ms sampling, 2 s baseline/trial, 12% growth acceptance | 2 s decisions; +15% slow-start, +5%/-10% hill-climb, multiplicative decrease; higher-frequency observation may remain internal |
| Data saver | Missing | Off by default; max 4 connections, no duplicate end-game |
| Media height | CLI 2160 default, up to 4320 plus Show all; persisted UI setting pending | 2160 default, up to 4320 plus Show all |
| Conflict policy | Product factories Rename; legacy constructor/records/runner Ask; explicit Overwrite available | Core behavior matches policies; product prompt/CLI settings and cross-OS filesystem qualification remain |
| Telemetry | None | Off; crash reports only by opt-in |

Apply new defaults to new transfers/settings. Preserve old persisted options during migration and preserve fixed-mode benchmark reproducibility. Do not merely change constants and claim the controller or caps accepted.

## Public error contract — PRD Appendix B, p. 15

Current [ErrorCode](../ajdm-model/src/main/java/io/ajdm/model/ErrorCode.java) now defines the full numeric catalog with safe sentences/actions. `DownloadException.userMessage()` renders it without raw exception details, and the production CLI and development runner use it. HTTP 401/407 presentation uses AJDM-2010 without changing the persisted ACCESS_DENIED enum. The table below records remaining product integration; extraction, DRM and IPC errors now have adapters; local mux errors now have an adapter. See [error contract](ERROR_CODES.md).

| Public code | Existing internal foundation | Required meaning / next action |
|---|---|---|
| AJDM-1001 / 1002 | UNSUPPORTED_SCHEME / INVALID_URL defined; validation still throws IllegalArgumentException | Wire URL/argument validation to public distinctions; check link |
| AJDM-2001 | NOT_FOUND | 404; verify link |
| AJDM-2002 / 2010 | ACCESS_DENIED / AUTH_REQUIRED presentation | HTTP 401/407 pause once and persist AUTH_REQUIRED; caller-supplied header resume works; product credential prompts pending |
| AJDM-2003 / 2004 | INSUFFICIENT_DISK_SPACE / DISK_WRITE_ERROR | Admission and disk-error pause/reasons implemented; actual full-volume ENOSPC validation/product UI remain pending |
| AJDM-3001 | SOURCE_CHANGED | File changed; pause and offer restart/keep partial |
| AJDM-3002 | RETRIES_EXHAUSTED | Check connection/retry |
| AJDM-3003 | RANGE_UNSUPPORTED defined; fallback represented by probe behavior | Wire capability presentation; disclose that interruption restarts transfer |
| AJDM-3004 | PROTOCOL_VIOLATION | Inconsistent server data; retry/report host |
| AJDM-3005 | CHECKSUM_MISMATCH | Re-download |
| AJDM-4001 | EXTRACTION_FAILED; bounded extractor implemented | Extraction failed; update tools/retry |
| AJDM-4002 | REFRESH_* engine failures | Expired media link refresh failed; retry from page link; retain internal failure reason |
| AJDM-4003 | MUX_FAILED; bounded local-only mux implemented | Mux failed; open log, keep downloaded streams |
| AJDM-4004 | DRM_PROTECTED; root/format refusal implemented | Protected content; not supported |
| AJDM-5001 | Private IPC client and safe numeric exit 4 implemented | Core unavailable; start AJDM; browser fail-open integration pending |

Engine extensions are now documented in [ERROR_CODES.md](ERROR_CODES.md): partial missing/truncated 2005/2006, target collision 2007, certificate verification 2011, state/ownership 6001/6002, paused/canceled 9001/9002. Refresh variants retain internal distinctions under public 4002. Cancellation remains a control state. Never leak source/header credentials into messages or discard useful internal causes merely to fit the public catalog.

## Goals and non-functional gates — PRD pp. 4, 10, 13

| Goal / area | Required gate | Current disposition |
|---|---|---|
| G1 / speed | AJDM median / IDM median <=0.95 in >=70% of scenarios, <=1.10 in every scenario | No IDM evidence. Nine B1–B9 rows imply >=7 qualifying rows if each is one equally weighted scenario; declare aggregation before running, including B8's two qualities. Do not cherry-pick scenarios. |
| G2 / formats | 100% of non-DRM HTTPS formats with attributes | Extractor/CLI inventory, filtering and the JavaFX preview picker are implemented; live corpus completeness remains pending. |
| G3 / integrity | Zero mismatches in 1000 chaos runs; 200/200 random kill resumes | Local qualification now runs 1,000 seeded recoverable HTTP faults and 200 randomized single-worker kills, plus fixed/adaptive and publication/media/playlist/renewal boundary recovery. The strict final-version result is recorded in the [backend report](../artifacts/backend-verification/report.md). Kernel-level faults and physical power-loss qualification remain separate. |
| G4 / portability | Feature parity and green Windows/macOS/Linux CI | Linux evidence only. A pinned CI workflow now includes full Linux and portable Windows/macOS subset checks; remote results, feature parity and installers remain pending. |
| G5 / trust | Paste-to-first-byte <=1.5 s; all errors have clear actions | Desktop preview provides paste/add and format-selection flows, but <=1.5 s first-byte performance has not been measured. Download-exception numeric messages/actions exist; full product error paths remain partial. |
| G6 / brand | Logo/dedication in About, installer, website | JavaFX About uses the unchanged original logo extracted from PRD page1 and exact dedication. Approved standalone icon, installer and website surfaces pending. |
| Link utilization | Unthrottled >=90% measured link; per-connection cap >=95% min(link,N×C) | Local configured-cap throughput is not physical utilization; independent capacity/network counters missing. |
| Other performance | YouTube formats <=6 s typical; UI cold start <=2 s with AppCDS; <=10% total CPU on 4 cores at 100 MB/s | Not measured. Whole-JVM loopback fixture CPU does not establish production client CPU. |
| Reliability | Resume >=99%; crash-free >=99.5%; no thread/handle/memory growth in 24 h | No release-scale interruption denominator, beta cohort or 24 h soak. Power-loss behavior not physically tested. |
| Resources | Idle RSS <=200 MB; 32-connection transfer RSS <=400 MB | Benchmark sampled whole-JVM RSS includes fixture/caches; not these acceptance measurements. |
| Scale | 10,000 history; 1,000 visible rows at 60 fps; 100 queued downloads | Store exists, but these scale tests/queue/UI absent. |
| Security / privacy | No default TCP listener, private Unix socket, verified TLS/tools, no shell strings, secrets never logged, local-only/default no telemetry | TLS/private POSIX state/header exclusions and bounded private Unix IPC implemented. Strict frames/input, endpoint ownership/lock, safe DTO/errors tested on Linux. Windows ACL/tool update pipelines remain missing; signed URLs stored privately for resume. Fixture TCP is explicit benchmark tooling. |
| Accessibility / usability | Keyboard/screen reader/AA/scalable fonts; zero-config first transfer; every error code + sentence + action | JavaFX preview has keyboard actions and scalable controls; screen-reader/AA/full usability acceptance remains pending. Public error catalog/presentation exists; not all product prompts or CLI exit mappings are integrated. |
| Maintainability | ArchUnit module enforcement, >=80% line coverage engine/store, CI <15 min | Five ArchUnit cases and an enforced >=80% engine/store line-coverage gate now exist. Aggregate local evidence is recorded in the [backend verification pack](BACKEND_VERIFICATION.md). Pinned CI is prepared; remote/cross-OS results and CI latency qualification remain pending. |
| Release | 10,000 steal interleavings; clean installer install/launch/update/uninstall | Extended local scheduler qualification runs 10,000 complete concurrent trials in each of two matrices, checking exact-once byte coverage and protected window/tail handoffs. Installer/three-OS acceptance remains pending; see the [backend pack](BACKEND_VERIFICATION.md). |

## Benchmark acceptance — PRD pp. 12–13

| Scenario | PRD production conditions | Current evidence / gap |
|---|---|---|
| B1 | Unthrottled LAN, 2 GB | Small loopback profiles only; no 2 GB LAN acceptance. |
| B2 | Per-connection 2 MB/s, nginx limit_rate | 512 KiB/s per-response fixture; pooled connections are not the same as per-TCP caps. |
| B3 | Global 10 Mbit/s | Small server-shared-cap fixture; correct production-cap comparison pending. |
| B4 | Actual 150 ms RTT, tc netem | 150 ms response-header delay is not RTT; pending. |
| B5 | Actual 1–3% packet loss, tc netem | Incomplete HTTP bodies are not packet loss; pending. |
| B6 | Random disconnects | Fault/chaos foundation exists; >=10 paired Windows/IDM trials pending. |
| B7 | Real large CDN file | Pending stable source/hash and paired trials. |
| B8 | YouTube 1080p + 2160p including mux | Extraction and paired copy-mux foundation tested locally; live end-to-end and matched IDM trials pending. |
| B9 | Physical phone 4G hotspot | Pending actual hotspot runs. User also requires 5G; add a separate recorded 5G scenario without silently altering G1 aggregation. |

Each acceptance scenario needs the same Windows machine/network/file for AJDM/IDM, alternating >=10 runs per tool, a documented cold-disk-cache procedure, every SHA-256, median/p10/p90 and mean link utilization, raw CSVs/scripts. Linux/macOS regression remains separate. The current matrix's 108 hash-verified small transfers and three warmups are valuable tuning evidence, not this release gate.

The external importer prepares paired comparisons but uses the inverse `IDM median / AJDM median`; release decisions must convert to `AJDM / IDM` and apply the exact thresholds. Its schema/report does not yet establish mean link utilization, complete B1–B9 coverage or an overall G1 pass. Extend acceptance reporting and retain unsuccessful attempts. Host learning must eventually be measured in declared cold/warm groups.

## Product integration and release obligations — PRD pp. 5–6, 10–14

The ten existing modules are model, net, store, engine, bench, core, IPC, production picocli CLI, media and JavaFX UI. Required future module is scheduler; durable paired-stream/mux jobs now extend the media foundation. CLI clients now talk to one long-lived engine/store owner through user-private Unix-domain-socket IPC; native browser messaging remains absent. Windows ACL support fails closed until implemented, and cross-OS IPC is not accepted. Production CLI downloads operate independently of the benchmark runner/server. The three-active-transfer guard rejects excess admissions; it is not a persistent queue scheduler.

Implement flows A–D and all eight screens: main, add URL, media picker, playlist picker, details, scheduler, settings, About. During transient disconnection show Waiting for network and resume when it returns; bounded retry exhaustion currently produces FAILED. This is distinct from automatically launching every paused download after app startup, which architecture §14 defaults off. Refresh media URLs through the extraction adapter without sacrificing byte identity.

Brand tokens: Legacy Navy `#0B1F3A`, Memory Gold `#E8B04A`, Flow Teal `#1FB5A8`, Mist `#EEF3F8`, Slate `#5B6B82`. Preserve logo clear space, A/J/M white or navy, D teal, simplified small badge, and fixed tagline **My brother. My legacy.** About dedication must use PRD §5.4 verbatim. Obtain/source the actual approved assets before packaging; do not alter the family photograph.

Track §11 release deliverables: first-run/About responsibility notice, no DRM bypass, yt-dlp and LGPL ffmpeg as separate processes with license texts, source-photo permissions and separate brand terms, local privacy and cookie opt-in, factual comparative benchmarks, legitimate IDM evaluation and no binary reverse engineering. These are specified product obligations; their implementation and owner decisions remain pending.

PRD §15 decisions remain open: code/brand license, ffmpeg bundled vs first-run acquisition, Windows/macOS signing budget, exact IDM version/license, VP9/AV1+Opus default container, website/domain. None blocks this alignment audit or correctness repairs; settle each by its stated milestone rather than requesting them all now.

## Specification differences to resolve explicitly

1. **Laggard timing:** PRD ENG-04 says <0.35× median for 3 seconds; architecture §8.7 says three samples while decisions occur every 2 seconds. Implement PRD wall-clock duration; separate observation frequency from decision interval.
2. **Playlist gates:** M3 exit criterion says 50 items, MED-06 acceptance says 100. Use 100 for acceptance; 50 may be an intermediate demonstration.
3. **Checkpoint budget:** nominal 5 s scheduling plus blocking fsync/commit cannot promise a hard 5 s loss budget on arbitrarily stalled storage. Retain durability, instrument lag and declare the tested storage/failure envelope. Do not claim timer configuration alone meets ENG-06.
4. **Publication:** Rename/Ask preserve no-clobber hard links; explicit Overwrite has staged atomic replacement and journaled direct atomic-move recovery when links fail. Known-size logical preallocation is implemented. Native no-clobber support, physical allocation and filesystem qualification remain gaps. Do not weaken no-clobber safety or silently overwrite without explicit policy.
5. **Transport control:** JDK pooled workers cannot guarantee a fresh TCP flow on recycle. Establish physical-flow evidence and use the existing HttpTransport boundary if explicit recycle/SOCKS5/throughput needs cannot be met. Netty is the architecture's contingent escape hatch, not an automatic dependency choice.
6. **All formats versus ceilings:** extract the complete non-DRM HTTPS inventory; ceiling/presets filter presentation/selection, with Show all exposing it. Do not discard formats during extraction. Fragment downloading stays v1.1.

## Revised execution order and milestone gates

1. **Close alpha correctness gaps (ENG-01/05/07/09/16/17/18, CLI-01, Appendix B).** Public errors, disk precheck, safe filename probe, Rename/Ask/explicit Overwrite, logical preallocation, Overwrite no-link recovery, reasoned pauses and bounded retries are implemented. Production core/private IPC/CLI now exposes safe validation, durable controls and explicit source-change restart/collision decisions. Remaining: live format acceptance, large/native mux acceptance, UI prompts, Content-Disposition name selection in the CLI/add flow, Windows ACL support, native no-clobber without hard links, physical allocation and cross-OS qualification. Retain range-ledger/allocation/journal/collision crash checks.
2. **Qualify adaptive engine (ENG-02/03/04/12/19, DIA-01/02).** Separate initial/max settings and migrate defaults, caps 16/64, aligned 512 KiB segments, specified 1–16 MiB windows/chunk caps, 2 s controller with 15%/5%/-10% and multiplicative decrease, timed laggards, measured connection recycling, expiring host profiles. Fold measured completion-notification/startup/window improvements into these requirements. Run large controlled comparisons and required acceptance gates before activating the product adaptive default.
3. **Establish PRD infrastructure/measurements (G1/G3/G4, §8/12.3).** Three-OS CI, ArchUnit, coverage, 1000 chaos/200 kills/10,000 interleavings, 24 h soak; B1–B7 matched IDM evidence and transport decision. No PRD M0/M1/M2 acceptance claim until their full exits pass.
4. **Complete beta product integration.** Extend the direct-file core/private IPC/CLI foundation with persistent queues/default max 3, native browser messaging and initial JavaFX list/details/themes/accessibility (v0.5). Rate limits and data saver remain planned product features.
5. **Complete media and v0.8 work.** Extend extraction, format presets, DRM refusal and restart-safe renewal with live playlist acceptance, tool lifecycle and live/large-mux acceptance; add proxies/auth integration, scheduler/settings/tray/notifications. M3 accepts 4K integrity and 100-item playlist; M4 requires all Must UI demoable.
6. **Complete browser and v1.0 release.** Fail-open browser/native messaging, countdown post-actions, three-OS installers/signing, exact branding/dedication/notices/docs, public B1–B9 + additional 5G evidence and G1 aggregate, all release gates. Application auto-update, fragmented HLS/DASH, subtitles, Swahili and mirrors stay v1.1.

This orders the currently authorized engine work without dropping product obligations. PRD M0–M6 overlap these engineering slices: foundations still lack cross-OS CI, segmented alpha has correctness/CLI gaps, adaptive beta lacks controller/profile/IDM acceptance, and media/UI/browser/release are largely unbuilt. The PRD's relative 20-week estimate is a planning assumption, not a promise derived from completed internal phases.

JavaFX preview usage, verification, skill provenance and limits: [desktop guide](DESKTOP_UI.md). It uses client-only private IPC, 1 Hz observed-byte rate/ETA sampling and bounded background workers; this is partial UI implementation, not beta/PRD acceptance.

Current preview regression note: full check447cases recorded one unchanged100-item playlist gate failure (99/100 extractions); isolated rerun passed. Preserve both results and investigate dispatch/durable start-spacing under load before claiming release acceptance.

Platform/details follow-up (4 October 2026): selected-job Details and native folder/completed-file controls are implemented and graphically exercised. Public X and Instagram samples complete through AJDM and decode with video+audio; TikTok/FB qualification remains source-dependent (see the platform report). MED-12 now has explicit session-only exported Netscape cookie-file selection and scoped extractor cookie handoff; automatic browser-store import, credential-vault persistence and non-POSIX ACL qualification remain absent. No HLS/DASH v1.1 delivery or complete live-site corpus acceptance is claimed.

ArDownload port follow-up (4 October 2026): the supplied public TikTok share video now completes and decodes through AJDM Java via explicit resolver choices, including durable partial reuse after core reopen. A fresh Instagram direct-HTTPS video/audio pair also completes and decodes. Public TikTok metadata may use external TikWM/legacy Douyin services; session cookies disable those services and never reach them. Broad live-site/quality corpus, private-session acceptance and matched IDM/mobile-network superiority remain unqualified. See [evidence](../artifacts/ar-download-port/report.md).

Packaged-core boundary follow-up (16 October 2026): hosted run 38388849762 failed one architecture rule because `io.ajdm.ui.PackagedLauncher` constructed `CoreService`. The Windows `AJDM-core` launcher now runs `io.ajdm.cli.PackagedCore` from the CLI JAR; the desktop launcher only supervises that process and uses IPC. The rule was not changed. The next hosted run also exposed a fixture race (`FaultyRangeServer` counted a paced transfer through its trailing pacing sleep, reporting 2 concurrent transfers under a host limit of 1), now fixed in the fixture, and one unreproduced 20 s hang of adaptive NORMAL mode (six one-CPU local reruns: ~0.2 s). That test keeps its bound and now dumps threads/progress/adaptation on timeout; treat the hang as open until it recurs with diagnostics or stays absent across repeated hosted runs. Results on commit `040d09b`: hosted Linux full check, architecture rules and coverage gate passed (547 tests, 0 failures); portable Windows/macOS passed (54 each); local Java 25 full check passed (547 tests; engine 94.29%, store 97.18% line coverage). Windows preview run 38813212834 (production source identical to `040d09b`) passed 59 Windows checks plus app-image and installed-EXE window/core/CLI/shutdown smoke; installer SHA-256 `9bbdaf296d89395c32c11ecca81b6923b0a81d22eb4457ea347b2566bcb7699b`, still NotSigned. No physical Windows session, trusted signing, 4G/5G or IDM comparison has been performed.
