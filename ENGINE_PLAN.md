# AJDM download engine implementation plan

## October speed milestone

Implemented [new adaptive policy and host learning](docs/SPEED_MILESTONE.md) in the speed milestone: persisted initial/max separation, measured growth/backoff, 16/64 shared response defaults, protected timed laggard splitting, independent-pool recycling and expiring network-scoped hints. Fixed remains the product default. Final-source `check` passes 539 tests with zero failures/errors/skips; engine/store line coverage is 94.43%/97.18%. Physical faults, extended stress, native transport/resource qualification, matched cellular/IDM and PRD acceptance remain open. Previous phase observations below are historical; consult the [artifact report](artifacts/speed-milestone-20261013/REPORT.md) for current measurements and preview launch instructions.

## Objective and ownership

The [core/CLI/IPC slice](docs/CORE_CLI_IPC.md) provides one daemon owner and private socket clients with durable controls, safe errors and numeric exits. The [media extraction slice](docs/MEDIA_EXTRACTION.md) adds discovery, selection previews and individual-format AJDM downloads with restart-safe renewal. The [paired media layer](docs/MEDIA_JOBS.md) adds durable two-stream parents, local copy mux and saved-hash publication recovery. The [playlist layer](docs/PLAYLISTS.md) adds durable item scheduling and bounded lazy extraction. Next is general persistent queue scheduling; cross-OS IPC/filesystem, live media corpus and real IDM/4G/5G gates remain open.

Build AJDM's own HTTP(S) transfer engine with correct parallel ranges, durable resume, and adaptive allocation tuned for changing 4G/5G conditions. Measure its performance against IDM under comparable conditions before claiming a win.

AJDM owns probing, range planning, connection policy, scheduling, retries, rate limiting, disk writing, persistence, and recovery. Java supplies HTTP/TLS and file primitives. No yt-dlp, ffmpeg, curl, aria2, or other downloader handles transfers. yt-dlp supplies media URLs and headers through the extraction adapter; ffmpeg muxes only completed local files through the paired-job adapter.

The supplied [AJDM_PRD.pdf](AJDM_PRD.pdf) v1.0 controls product requirements, release defaults and acceptance gates. [architecture.md](architecture.md) supplies compatible implementation detail. The [PRD alignment audit](docs/PRD_ALIGNMENT.md) maps all 61 functional requirement IDs to current code and gaps. Completed engineering phases below do not imply that PRD milestones M0–M6 have passed.

PRD G1 requires AJDM/IDM median completion time <=0.95 in at least 70% of benchmark scenarios and <=1.10 in every scenario. Integrity requires zero mismatches in 1000 chaos runs, 200/200 random kill resumes and 10,000 steal interleavings. Same-host Windows IDM, real network conditions, cross-OS CI and the 24-hour soak remain acceptance work.

## Initial scope

Start with ajdm-model, ajdm-net, ajdm-engine, ajdm-store, and ajdm-bench. Use a small development runner to exercise the engine. Add production core composition, UI, IPC, browser integration, and media extraction after the transfer foundation works.

The first demonstrable result is a CLI-driven local test transfer producing a byte-identical file. The first resilient result supports kill/restart recovery without trusting unwritten or unflushed bytes.

## Phase 0: Build and test foundation

- Locate or provision Java 25 and a Gradle wrapper compatible with it. Do not silently downgrade the architecture to the installed Java 17 or use the installed Gradle 4.4.1 for the new project.
- Establish minimal modules and the transfer/event contracts.
- Build a deterministic range server producing known bytes and expected hashes.
- Support scripted HEAD rejection, range rejection, malformed range responses, premature EOF, delays, throttling, and changed validators.

Pass: reproducible build, a running test server, and meaningful failing tests for the first transfer behavior.

## Phase 1: Correct single-connection transfer

- Implement probe and explicit range verification, capturing total size, final URI, validators, and encoding.
- Implement single-stream fallback for servers without usable ranges.
- Validate status, Content-Range, representation size, and received byte count before accepting ranged data. Reject incompatible encoded responses on the range path.
- Implement bounded pooled buffers and positional disk writes. A queued write owns its buffer until completion; handle partial FileChannel writes until the buffer is exhausted.
- Separate received, write-acknowledged, and durable progress.
- Add bounded retries, stall cancellation, cancellation cleanup, safe destination names, and final publication after writer completion and verification.
- Measure JDK transport concurrency and reuse before promising worker-to-socket affinity; retain the HttpTransport replacement boundary.

Pass: deterministic test files match SHA-256; malformed or truncated responses never become successful downloads; buffer reuse cannot alter queued data; cancellation releases resources.

## Phase 2: Durable pause and resume

- Implement RangeSet and SQLite persistence.
- Pause request issuance, settle in-flight work, flush acknowledged writes, and commit exactly those completed ranges.
- For periodic checkpoints, capture a coherent write boundary, wait for all writes included in it, force the file, then commit its ranges. Later writes are excluded from that checkpoint.
- Choose SQLite transaction durability settings that support the stated crash/power-loss recovery budget; WAL mode alone does not establish that budget.
- Reconstruct pending ranges from durable coverage on restart. Never infer coverage from preallocated file length.
- Re-probe identity and size before reusing a partial file. Define conservative behavior when trustworthy validators are absent.
- Keep source changes, missing/truncated partial files, and unsupported resume explicit.

Pass: repeated process kills and pause/resume cycles yield matching hashes; injected failures between write, flush, and commit never cause the database to claim missing bytes. Power-loss tests are a separate gate from process-kill tests.

## Phase 3: Safe parallel ranges

- Begin with a fixed connection target to isolate partition and ownership correctness.
- Make the segment scheduler authoritative for assignments, range leases, and ownership changes.
- Coordinate a split at a safe handoff boundary: account for already reserved/queued writes and prevent the old worker from accepting new bytes beyond its revised assignment.
- Maintain a ledger for pending, in-flight, written, and durable ranges. No offset may be assigned to two ordinary writers; every unfinished offset remains recoverable.
- Respect host/global caps and disk backpressure across downloads.

Pass: deterministic interleaving and randomized fault tests show no overlapping write ownership, gaps, or corrupted outputs. A capped-per-connection server provides the first controlled parallel-transfer performance comparison.

## Phase 4: Dynamic rebalancing

- Measure per-worker throughput, aggregate throughput, remaining bytes, write backlog, and errors.
- Add controlled connection-count trials with settling periods, hysteresis, and rollback when additional connections hurt.
- Pick work-stealing victims by estimated completion time; allocate shares using measured speeds and the safe handoff protocol.
- Reduce admissions on server pushback and sustained writer pressure. Current speed-sized windows and ordinary stalls are only partial ENG-04 coverage; timed laggard splitting and physical connection recycling are required completion work.
- Re-evaluate after sustained throughput changes instead of assuming old connection counts remain optimal. Persistent host profiles with expiry/revalidation are ENG-19 and an M2 deliverable, still outstanding.
- Record controller decisions so speed changes can be explained.
- Defer speculative end-game duplication until ownership, winner arbitration, cancellation, and duplicate-data accounting are proven.

Pass: scripted speed changes demonstrate useful work redistribution and improved completion time over the fixed-allocation baseline; global-cap scenarios do not cause uncontrolled connection growth or persistent oscillation.

## Phase 5: Network and media resilience — core implemented

- Exercise disconnects, long stalls, throttling, retry exhaustion, disk-full failures, and connectivity changes.
- Provide a URL/header refresh hook without embedding extraction in the engine.
- Coordinate refresh once per stream; verify size and representation identity before retaining completed ranges from a replacement URL. A matching format ID alone is not sufficient proof.
- Run video and audio as ordinary engine transfers under shared resource caps.

Pass: refresh and interruption scenarios preserve correct bytes; failed refreshes terminate predictably; no subprocess performs downloading.

## Phase 6: Performance evidence — local harness implemented

- Compare single-connection, fixed-parallel, and adaptive AJDM modes to identify the effect of each optimization.
- Use repeatable per-connection caps, global caps, latency, loss, and changing-rate scenarios.
- Compare AJDM and IDM on the same Windows host and network, using the same source and output expectations; alternate repeated runs and separate cold from warm host-profile measurements.
- Add real 4G and 5G runs. Record completion time, throughput, useful versus duplicate/retried bytes, CPU, memory, and disk backlog.
- For media, report transfer time and total user-visible time including extraction and muxing separately.
- Publish medians and spread, hashes, conditions, versions, and limitations. Treat 95% utilization and beating IDM as measured acceptance targets rather than unconditional guarantees.

Pass: a reproducible report establishes where AJDM wins, ties, or loses and identifies the next bottleneck.

## Next implementation milestone

Close the remaining PRD alpha integration/qualification gaps before further standalone throughput tuning: production CLI/core/IPC and user decision flows, native no-clobber publication and physical filesystem acceptance (ENG-05). Core source-change pauses/restart and retry-table recovery (ENG-07/09) are implemented; see [retry/pause contract](docs/RETRY_AND_PAUSE.md). Public errors, disk admission, probe filename/content-type metadata and persisted Rename/Ask/explicit Overwrite are implemented; see [error/admission contract](docs/ERROR_CODES.md) and [filename/publication contract](docs/FILENAMES_AND_CONFLICTS.md). ENG-01's external host corpus and ENG-17's cross-OS acceptance/prompt integration remain open. Preserve flush-before-commit and authoritative durable ranges; further publication and preallocation changes need targeted crash tests.

Then qualify adaptive behavior against ENG-02/03/04/12/19: distinct initial 4/max 16 settings, hard cap 32, host/global 16/64, 512 KiB aligned minimum segments, adaptive 1–16 MiB windows with chunk-host caps, slow-start/hill-climb and multiplicative decrease, timed laggard handling and expiring host learning. Fold measured completion-notification/startup/window bottlenecks into this work. Profile completion waits before modifying force/SQLite semantics. Fixed remains the current control/default during qualification; the PRD product must provide the specified adaptive flow rather than shipping fixed mode as its substitute.

Add missing release infrastructure and product layers in the audit's execution order: cross-OS CI/coverage/ArchUnit, persistent queues on the existing core/CLI/private IPC, JavaFX UI, live playlist/tool lifecycle/media acceptance, proxies/limiting/scheduler, then browser/packaging/branding. Acceptance remains >=10 paired cold-cache Windows IDM runs per B1–B9 scenario with mean link utilization and exact G1 ratios, plus the user's additional 5G measurements. Current local fixtures/importer do not fulfill that gate.

## Current status

The prior retry/pause alpha slice implements reasoned source/disk/auth pauses, explicit new-ID restart preserving old partials, bounded malformed-range/status recovery, strict certificate failure actions and an atomic pause-reason checkpoint. The final check passed 312 Java tests (55 additions) and 85 forced-kill checks: 84 matching completed files and one intentional source-change pause with old bytes/reason preserved. See [retry/pause evidence](artifacts/prd-alpha-retry-pause/report.md). The previous [file-layout slice](artifacts/prd-alpha-file-layout/report.md) added logical preallocation and journaled Overwrite; the [filename slice](artifacts/prd-alpha-filenames/report.md) added safe probing/conflicts and 10,000 hostile names. GUI prompts and native no-clobber/filesystem qualification remain open. The subsequent core/CLI and media slices passed 383 Java tests and 87 forced-kill checks (86 matching completions and one intentional source-change pause). [Extraction evidence](artifacts/prd-alpha-media-extraction/report.md) covers extraction-only discovery, deterministic selection, individual-format AJDM transfers and persisted origin renewal after reopening; paired stream/mux jobs are implemented in the [current evidence slice](artifacts/prd-alpha-media-jobs/report.md), with 410 Java tests and 92 forced-kill checks (91 matching completions and one intentional source-change pause). Unchanged Python tooling's seven passing tests remain historical evidence.

Phases 0–3 and the core phase-4 adaptive policy and phase-5 source recovery are implemented. The final phase-6 Gradle check passed all 188 Java tests on Linux with zero failures, errors, or skips. Seven standalone Python importer/verifier tests also passed. It included 20 fixed-parallel process kills, 20 adaptive kills after tail handoffs, ten single-worker kills, five existing crash boundaries, and eighteen refresh-boundary kills. Two 100-seed scheduler matrices exercise fixed leases and concurrent protected-window splitting. Recovered files match SHA-256 and only gaps are requested. Earlier acceptance runs also passed 200 single-worker kills and 50 fixed-parallel kills.

Phase 5 adds caller-supplied direct URL/full-header refresh with bounded attempts and callback waiting, cancellation and late-result rejection, generation-wide coordination, and atomic validated source persistence. Matching known size, strong ETag, and actual range support are required to retain bytes across replacements. Failure paths preserve the old source and ledger. Tests cover initial-probe and mid-transfer expiration, identity/credential rejection, header replacement, reattachment after reopen, post-refresh network failures, injected disk-force failure, shared media-transfer limits, and process kills before/after source commit. See [phase-5 evidence](artifacts/phase5-source-refresh/report.md). No extraction or mux subprocess performs downloading.

Adaptive scheduling is opt-in and persists through SQLite schema v3. The controller measures useful throughput, makes settled connection trials, rolls back neutral/harmful growth, reduces admissions on server pushback, and ignores brief writer queue bursts while responding to sustained pressure. Idle admitted workers steal only tails beyond protected request windows and outstanding writes. Changing admissions never transfers an active window's ownership.

Validation covers cap-per-response trial acceptance, shared-cap rollback without repeated oscillation, transient and sustained writer pressure, network-change re-evaluation, server pushback, scoped cancellation/fallback, safe host limits, adaptive source/protocol/integrity failures, pause/reopen, and process kills after actual handoffs. Diagnostics retain at most 256 decisions and expose worker speed/backlog without source URLs or headers.

The [phase-3 comparison](artifacts/phase3-parallel-comparison/report.md) measured one-worker versus fixed four-worker AJDM at a local 512 KiB/s per-response cap: median completion fell from 8.101 s to 2.192 s (3.70x). The [final phase-4 comparison](artifacts/phase4-adaptive-comparison-final/report.md) measured a 15.3% slowing-peer completion-time reduction, a 3.6% shared-cap slowdown with fewer lanes, and a 19.0% per-response-cap slowdown. Bounded doubling reduced the initial stable-cap penalty from 52.65%; all 18 final files passed independent SHA-256 checks. Cautious startup and request boundaries still carry measurable cost, so adaptive remains opt-in.

Persistent host profiles and explicit laggard socket recycling remain PRD/M2 gaps. Rename/Ask retain no-clobber hard links and fail safely without them. Explicit Overwrite uses atomic staging replacement or a journaled direct atomic move when links fail. Known-size logical preallocation is implemented; physical allocation guarantees and portable native no-clobber publication remain open. Disk admission checks full known size +64 MiB (unknown size: reserve only), including resume and validated replacement sources, preserving saved coverage on rejection. Physical power-loss, an actually full volume, cross-OS, IDM, real 4G/5G, and live media/large-mux acceptance remain separate validation or implementation gates. No throughput advantage over IDM has been established.


Phase 6's local harness is implemented: a balanced six-round, six-scenario single/fixed/adaptive matrix records timing, received/offered bytes, HTTP requests, controller decisions, CPU/GC, sampled heap/RSS, exact pending disk writes, and checkpoint lag. All 108 measured outputs and three warmups passed independent verification. [Findings](artifacts/phase6-performance-matrix/analysis.md) record every gain/loss and outlier; adaptive loses to fixed in these short/stable scenarios. The engine's scheduling policy is unchanged. A standalone client capture, loopback fixture command, and strict external CSV merger prepare the [Windows/IDM protocol](docs/BENCHMARK_PROTOCOL.md). Physical RTT/loss, cold-cache external runs, real 4G/5G, and IDM timings remain pending. The PRD's performance and release acceptance gates are not complete.

## Durable playlist alpha slice

Flat metadata inventory preserves positions/unavailable entries, supports all/range/per-entry presets or exact custom pairs, and exposes safe CLI/IPC previews/status. Schema v8 commits selected choices and a pre-admission child UUID; pause/reopen and errors after admission reuse the same child. One active playlist shares the three-transfer core guard and admits at most three metadata tasks/results with persisted two-second extraction starts. The full check/distributions passed in 6m7s: 431 Java tests, zero failures/errors/skips, 96 forced-kill checks (95 matching completions plus one intentional source-change pause). A 100-item local rate fixture completed in 201.967s and verified every output. See [playlist evidence](artifacts/prd-alpha-playlists/report.md). Live 100-item/site acceptance, general queues, UI/tool lifecycle and real IDM/4G/5G gates remain open.

## Backend qualification pack

The [verification guide](docs/BACKEND_VERIFICATION.md) records pinned testing skills, ArchUnit dependency rules, aggregate JaCoCo reporting and the PRD 80% engine/store line floor enforced by `check`. Configurable seeded HTTP fault, separate-process kill/resume and scheduler matrices support extended qualification on the implemented backend. The CI workflow is prepared with a full Linux job and portable Windows/macOS checks; remote results, complete OS parity and remaining product features are distinct gates.
