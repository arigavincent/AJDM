# Progress

## 2026-10-01
- Read architecture.md and compared it with the user's extraction and throughput goals.
- Read core-engineering and planning-with-files skills.
- Inspected project files and default Java/Gradle versions.
- Created ENGINE_PLAN.md and planning notes.
- No engine code, dependency installation, toolchain changes, or benchmarks performed in this planning turn.

## 2026-10-01: Implementation started
- Added Java 25 multi-module Gradle build and domain/engine contracts.
- Added deterministic range/fault server and integration tests before the transfer implementation.
- Initial Gradle wrapper generation failed because the sandbox prevents its lock socket. Rerunning outside the sandbox; do not repeat the sandboxed invocation.
- Generated the pinned Gradle 9.3.1 wrapper with official distribution checksum.
- Added the HTTP transport, probe/response validation, bounded disk writer, stall guard, and single-worker transfer implementation.
- Direct Java 25 compilation with all warnings treated as errors passed after changing intentionally unused try resources to unnamed variables.
- Stopped a very slow redundant wrapper download and reused the installed distribution in /tmp/ajdm-gradle. Test dependencies use the existing local Maven mirror for this environment; the checked-in build defaults to Maven Central.
- First integration run passed, then expanded validation to 32 tests and reran successfully.
- Verified the default Maven Central build, generated dependency locks and SHA-256 verification metadata, and reran the same checks successfully.
- Added README usage, current limitations, and module descriptions. Added partial-file paths to transfer handles and runner failure output.
- Local demo smoke test is running; no IDM or real mobile-network performance measurements have been made.
- Local demo passed: 8,388,625 bytes downloaded, SHA-256 406262f5d38d471cf3d362875d19007777d5200debaeb0a7d31a5a5c860bd098. Independent sha256sum matched the engine's result.
- Phases 0 and 1 are complete for this milestone: 32 tests passed on Linux, build passed with Maven Central, and demo passed. Resume, parallelism, adaptive control, and cross-OS performance remain pending.

## 2026-10-01: SQLite resume implementation
- Added RangeSet tests and observed all four failing against the initial stub, then implemented coalescing coverage and gaps.
- Added ajdm-store with a dedicated JDBC platform thread, FULL/WAL transactions, application ownership lock, private state-file permissions, safe-header persistence, and startup recovery to PAUSED.
- RangeSet and initial store tests passed.
- Added acknowledged-write barriers, checkpoint coordination, pause/resume APIs, source validator checks, partial-file checks, and interrupted publication recovery.
- Added integration tests for pause/reopen/resume, graceful shutdown, changed validators, missing partials, and truncated partials. The full suite is running.
- Integrated suite passed with 44 tests. Added force-failure/barrier tests, unsupported-resume restart cases, and subprocess kill tests.
- Crash test compilation initially failed because a local variable named java shadowed the java package. Renamed it to javaBinary and reran the checks.
- The initial full resume suite passed with 55 tests. Added a fifth crash boundary after the ready-to-publish commit; its failing test exposed an unnecessary source probe when the final link had not yet been created.
- Unified publication recovery so either side of final-link creation can complete locally, while rejecting unrelated existing destinations. The focused recovery and private-permissions tests are running before the 200-kill matrix.
- All five fixed crash boundaries and POSIX permissions passed. The full acceptance run exposed a test-only assumption: a delayed checkpoint can contain the entire payload before the ready marker. Hash recovery succeeded, but the test indexed a nonexistent remaining range. Added the complete-coverage assertion and restarted the matrix.
- While the matrix runs, strengthened the existing store rollback test: a SQLite trigger now fails range insertion after state change and old-range deletion. This verifies rollback of actual mutations, rather than only rejection before a transaction mutates data. Rerun the store suite after the matrix.
- Acceptance passed: 200/200 varied-offset process kills plus all five fixed boundaries recovered matching SHA-256. Full check passed; the final check reran the strengthened store tests and reused the unchanged successful matrix.
- Final test reports: 57 tests, zero failures/errors/skips. Installed runner demo and list passed; independent SHA-256 matched 406262f5d38d471cf3d362875d19007777d5200debaeb0a7d31a5a5c860bd098 for 8,388,625 bytes.
- Updated README and ENGINE_PLAN with phase 2 behavior, evidence, and limits. Next is fixed-concurrency parallel range ownership and noncontiguous recovery, followed by adaptive rebalancing. Power-loss, cross-OS, IDM, and real 4G/5G tests remain pending.

## 2026-10-01: Parallel range implementation started
- Read engineering/planning guidance and inspected existing writer, checkpoint, recovery, cancellation, transport, and persistence boundaries.
- Recorded phase 3 scope and design before changing implementation. First regression test targets out-of-order acknowledged writes incorrectly counted as a prefix.
- Observed the out-of-order writer regression fail, then replaced prefix accounting with union coverage and exact forced snapshots. Added scheduler leases/tickets, shared budgets, and cancellation children.
- Core interval/ownership/budget/store tests passed after correcting an invalid compound var declaration in the new test. Scheduler tests run 100 seeded interleavings against per-byte ownership counts.
- Implemented fixed range workers, settle-before-fallback behavior, gap recovery, and SQLite v1-to-v2 option migration. Parallel fault/pause/truncation/cap integration tests are running.
- Parallel integration and migration tests passed. Full regression check then passed with ten killed parallel workers, ten existing single-worker kills, five fixed crash boundaries, and 20 seeded network-fault configurations.
- Added a local rate-cap comparison runner with three alternating trials and CSV/Markdown evidence. Review tightened rare end-of-window replay so durable claims are reduced before acknowledged coverage is discarded.
- Extended parallel process-kill matrix reached 40/50 passing runs. Added an injected replay-force failure regression to verify the discarded window is no longer claimed while higher saved ranges remain intact. Benchmark is running with the installed launcher.

## Parallel milestone completed
- The server restart interrupted only the final validation/documentation call. Verified no leftover test workers before resuming; completed benchmark and 50-kill results were preserved.
- Final check passed: 87 tests, zero failures/errors/skips. The added replay-force failure regression passed; unchanged successful integration/50-kill results were reused.
- The final phase-3 matrix includes 50 killed parallel processes, ten single-worker kills, five fixed boundaries, 20 seeded network-fault configurations, and 100 scheduler interleavings. Saved bytes were never refetched in recovery cases.
- Controlled benchmark passed all six outputs: one-worker median 8.101325 s, four-worker median 2.192103 s, 3.70x under a local 512 KiB/s per-response cap. Independent sha256sum matched f091e7411b443a68797ea2d20337096190628fa939383eff32886b5cdb5c3d08 for every 4,194,321-byte output.
- Updated README/ENGINE_PLAN with parallel defaults, shared active-response/buffer limits, interval recovery, SQLite migration, reproduction commands, measured evidence, and outstanding limits. Binary fixture outputs remain local and ignored; CSV/Markdown reports are retained.
- Phase 3 complete. Next: phase 4 controller trials and ownership-safe dynamic rebalancing. No IDM, real mobile-network, cross-OS, physical power-loss, or media extraction claim has been established.

## Phase 4 started
- Inspected scheduler, worker lifecycle, options, persistence, and prior acceptance criteria. Chose protected-window splitting to preserve strict response validation and avoid canceling a body merely to steal its tail.

- Initial new tests failed for missing controller/window APIs, then passed after implementing the protected-window scheduler and bounded trial controller. Existing parallel fault tests and SQLite migration tests passed after integration.
- Adaptive transfer fixtures are running for caps, steal ownership, pause/reopen, and faults. A new 100-seed concurrent split/window test is ready for the next check.

- Full regression completed 20 fixed and 20 adaptive process kills successfully. Adaptive kills occur after durable coverage exceeds the original fast lane's share, proving recovery after actual tail handoffs.
- One real throughput-trial acceptance assertion failed intermittently. A focused rerun passed, then deterministic tests reproduced an independent controller defect: a single transient full writer queue permanently entered hold. Changed pressure handling to require 500 ms persistence before rollback; sustained pressure remains protective. Network-change hysteresis now uses two seconds of monotonic time rather than a tick count.

- Final check passed all 120 tests, with zero failures/errors/skips. The 20 fixed and 20 adaptive process-kill matrices passed after the pressure fix. Installed comparison runner is collecting 18 alternating fixed/adaptive samples; slowing-peer runs favor adaptive while short shared-cap runs show a small slowdown with fewer lanes.
- Security review: adaptive options remain bounded, migration/row writes use hardcoded schema operations and parameterized statements, and diagnostics contain no URLs/headers. No new dependencies or external payload downloader were introduced.

- Benchmark exposed a 52.65% per-response-cap slowdown despite a 14.78% slowing-peer improvement. Added a failing deterministic test for bounded doubling and pushback rollback, then changed trial growth. Revalidating before collecting a fresh comparison; initial results remain preserved.

## Phase 4 core milestone completed
- Revised bounded-growth policy passed the final full check: 121 tests, zero failures/errors/skips, with 20 fixed and 20 adaptive process kills, ten single-worker kills, and five fixed crash boundaries. All adaptive recoveries preserved disjoint saved coverage after handoffs and avoided fetching saved bytes.
- Final comparison: slowing peer 10.199251/8.636951 s fixed/adaptive (15.32% faster adaptive); shared cap 8.040173/8.333339 s (3.65% slower with two rather than four lanes); per-response cap 8.287133/9.861570 s (19.00% slower). The revised policy reduced the original 52.65% stable-cap penalty. Fixed mode remains default; no universal speed claim.
- Independently verified all 18 final and 18 initial-policy files against hashes, size, and traffic. Final artifacts include raw samples, per-run decision traces, report, and verification/spread. Updated README, ENGINE_PLAN, and task plan. No payload downloading is delegated to external programs.
- Persistent host hints, explicit pooled-socket recycling, physical power-loss, cross-OS, IDM/real 4G/5G validation, and media extraction/refresh remain explicit future work. Next implementation milestone: coordinated source URL/header refresh and network resilience.

## Phase 5 start
Phase 5 started: inspected transfer lifecycle, transport, source persistence, cancellation scopes, and regression fixtures. No phase-5 implementation yet. Phase 4 remains verified with 121 passing tests and recorded fixed/adaptive benchmarks.

## Phase 5 contract
Added expiration fixture and contract tests before engine behavior. Initial test run failed compilation because the refresh API was absent. Added API types and compatibility overload scaffolding; next run demonstrates actual ACCESS_DENIED failures before coordination is implemented.

## Phase 5 first green run
Initial four source-refresh integration cases now pass. Both fixed and adaptive worker generations recover with one callback, preserve acknowledged ranges, and produce matching SHA-256. Initial-probe expiration recovers; no-provider behavior keeps ACCESS_DENIED. Implemented atomic SQLite source/probe/safe-header replacement and a callback gate held until caller code actually exits.

## Phase 5 hostile paths and restart
Added strong-validator model tests and integration coverage for callback limits, timeout, cancellation, and resume gating. Added SQLite reopen/rollback/credential/downgrade tests. Hard-kill tests now stop separate JVMs during the callback, immediately after source commit, and at a later refreshed checkpoint, for both fixed and adaptive modes; running three repetitions per boundary.

## Phase 5 recovery validation
All targeted source-refresh tests pass, including graceful pause/resume retaining the provider, reattaching extraction in a fresh engine, post-refresh body truncation/stall/429 recovery, bounded persistent failures, injected disk-force failure, and two ordinary audio/video transfers sharing connection/buffer limits. Eighteen hard kills (three repetitions of six fixed/adaptive refresh boundaries) recovered matching SHA-256 without refetching durable bytes.

## Phase 5 completion
Final offline check and installDist succeeded in 3m8s: 177 tests, 0 failures/errors/skips. Final matrices: 20 fixed, 20 adaptive, 10 single, 5 existing crash boundaries, 18 refresh-boundary kills = 73. Installed CLI demo verified 8,388,625 bytes and SHA-256. Saved artifacts/phase5-source-refresh/report.md and test-summary.json. Updated README/API usage and ENGINE_PLAN to phase 6 measurement; phase-5 milestone complete.

## Phase 6 start
Phase 6 started from completed 177-test source-refresh milestone. Read planning/core-engineering skills and benchmark/CLI code. No benchmark processes from the interrupted user turns were launched. Scope is controlled Linux evidence plus a Windows/real-mobile protocol; no physical 4G/5G or IDM timings will be invented.

## Phase 6 implementation
Statistics tests reproduced incorrect quantiles/missing validation before implementation. Added six programmable profiles, separate deterministic HTTP fixture, balanced mode order, incremental artifacts, whole-JVM resource sampler, and an exact queued-write-byte metric. First compile stopped at strict Java try-resource lint: sampler AutoCloseable declared InterruptedException and a queue test explicitly closed its resource. Removed the unnecessary AutoCloseable contract and used a separate already-closed file for failure injection.

## Phase 6 first green checks
New statistics/profile/matrix integration tests and all engine unit tests pass. Queue accounting returns zero after success and failure, and matrix outputs include independent hashes, request/decision/telemetry CSVs, and no-overwrite artifact handling. The reduced test profile recovers its configured dropped response in all three modes. Preparing six rounds per six profiles (108 measured outputs) with a bounded 768 MiB JVM heap.

## Phase 6 completed timing matrix
Collected 108 measured outputs plus three warmups across six mode permutations and six profiles. Every file was engine-verified and independently rehashed; request/byte/drop ledgers and telemetry are being audited. Fixed is faster in these short/stable fixtures; adaptive remains two lanes in most scenarios and has smaller-window overhead. Dropped bodies retain accepted prefixes within strong-validated ranges and yield zero extra received bytes. Two changing-rate outliers remain in the data: one single run had stale durable coverage after payload completion, and one adaptive run remained VERIFYING for several seconds; these indicate a waiting/flush/publication path, not demonstrated CPU saturation. Exact cause needs profiling.

## Phase 6 external protocol and tools
Client-only single/fixed/adaptive benchmarking passes integration tests and writes verified-completion timing plus telemetry without invoking IDM. Added docs/BENCHMARK_PROTOCOL.md and an exact external CSV template. Seven Python tool tests pass, including synthetic twenty-row CLI import/no-overwrite, mismatched file/context/version/order rejection, and corrupted matrix file/counter rejection. Synthetic importer rows are tests only and are never reported as real IDM measurements. Independent audit now checks the declared drop pattern and balanced order as well as all prior ledgers.

## Phase 6 completion
Phase 6 local milestone complete: 108 measured outputs and three warmups independently verified (679,479,084 measured bytes; zero extra received bytes across the declared partial-body faults). Final check/installDist passed 188 Java tests with zero failures/errors/skips, plus seven Python tool tests; 73 recovery kills retained. Installed fixture/client CLI smoke independently verified an 8,388,625-byte file, help, and invalid-profile exit. Saved report/analysis, CSV/profile/environment/request/decision/telemetry evidence, verification/test summaries, and standalone SVG/PNG plot. Updated README, ENGINE_PLAN, and Windows/IDM protocol/template. Next candidates: adaptive completion notification, trial startup/remaining-work gating, and latency-aware windows; real IDM/4G/5G acceptance still pending.

## 2026-10-02 — PRD audit in progress
Extracted all 16 PRD pages and reread full requirements pp. 7–10 without truncation. Updated task plan to prioritize alignment. Initial source read assumed com/ajdm and failed; inventory established io/ajdm and corrected reads succeeded. No production code changed.

## 2026-10-02 — PRD alignment complete
Updated docs/PRD_ALIGNMENT.md, ENGINE_PLAN.md, README.md, docs/BENCHMARK_PROTOCOL.md and task notes. Validation compared PDF-extracted requirement IDs to audit table rows: 61 unique IDs, no omissions/duplicates. Checked all 51 local links across four documentation files, table delimiters and trailing whitespace. No runtime code changed; existing 188 Java/7 Python/73-kill results are cited as prior evidence rather than a fresh test run. PRD release compliance and external measurements remain outstanding.

## Alpha error/disk admission — red/green
Added 3 public-error, 4 disk-space guard, 5 network admission/resume tests before implementation. Initial sandbox Gradle could not open lock sockets; escalated rerun failed compilation on the expected missing APIs. Implemented catalog text/codes/actions, DownloadException.userMessage(), secret-safe runner presentation, FileStore-backed injected DiskSpaceProbe and overflow-safe disk guard. Admission checks precede restored-ledger mutation and replacement-source commits. All 12 targeted tests passed. Added full catalog enum entries for future adapters without claiming those adapters exist. Further tests will cover refresh rejection and verified-publication recovery.

## 2026-10-02 — PRD alpha admission/public errors complete
Full Gradle check/installDist passed in 3m24s: 205 Java tests, zero failures/errors/skips, 73 forced kills (10 single, 20 fixed, 20 adaptive, 5 original boundaries, 18 refresh). Added 17 tests total across public catalog, disk arithmetic/admission and real runner output. New fixed/adaptive refresh rejection and verified-publication bypass tests pass. No dependency/schema/controller/publication change. Security review confirmed catalog-only error output, preserved header exclusions/TLS/SQL policy and no added shell execution. Python tools unchanged and not rerun.
Updated README, ENGINE_PLAN, PRD_ALIGNMENT and docs/ERROR_CODES.md; wrote artifacts/prd-alpha-admission/report.md, XML-derived test-summary.json and source-files.sha256 for 10 Java sources. A large audit patch initially failed due out-of-order hunks; reapplied in file order successfully. Verified 60 local documentation links, all 61 unique PRD functional IDs and whitespace. Next alpha slice is filename/probe/conflict handling; preallocation and portable publication need dedicated crash tests.

## Filename/conflict milestone — implementation
Initial contract build failed on expected missing filename/parser/policy APIs. Added model safe-leaf rules, RFC-based extended filename parsing, ProbeResult metadata, Rename-default product factory/legacy ASK constructor, schema v4 policy+metadata migration, transactional same-directory destination changes, explicit conflict-policy resume, no-clobber rename retries and staged atomic overwrite retaining the original partial. Existing migration fixtures now recreate complete v1/v2 shapes including removal of new v4 columns; added true v3 migration and verified-metadata/destination persistence tests. Targeted tests running.

## Filename/conflict validation — targeted crash success
Targeted model/net/store/bench tests passed, including 10,000 hostile names, five real metadata probe variants, and five new forced publication kills. Added legacy name compatibility tests; they reproduced overly strict TransferRequest construction blocking stored CON.txt metadata. Moved fresh-target validation to factories/engine.start/withTarget, keeping persisted local paths readable and preserving legacy no-clobber policy. Added an injected stage-failure then Rename recovery test that cleans only owned staging links. Full check/installDist now running on final Java implementation.

## Filename fuzz follow-up
The first full check stopped in model fuzzing after adding nonbreaking-space/combining-mark inputs: sanitize produced CON plus an extension after stem whitespace trimming, and a second sanitize then prefixed underscore. Isolated CON\u00a0.txt as a direct regression. Reserved-device detection now runs on the fitted/trimmed result, preventing cleanup from creating a reserved filename. No bench/crash results claimed from the stopped full run; focused regression then full check will rerun.

## 2026-10-02 — filenames/policies complete
Wrote docs/FILENAMES_AND_CONFLICTS.md and artifacts/prd-alpha-filenames/{report.md,test-summary.json,source-files.sha256}; updated README, ENGINE_PLAN, PRD_ALIGNMENT, ERROR_CODES and task notes. Verified 66 local links, all 61 unique requirement rows, table delimiters/whitespace, and hashes for 18 Java source files. Schema v4 migrates exact v1/v2/v3 fixtures, preserves policy/options/ledger, supports same-folder destination changes while retaining ready markers. Security review: header parsing never chooses folder; no SQL interpolation/dependencies/shell commands added; diagnostics redact sources; explicit overwrite rejects observed symlinks/directories and protects unrelated stages. Python tools unchanged/not rerun. All final Java checks pass; next slice is preallocation/portable publication, then source-change/retry transitions.

## 2026-10-02 — Preallocation/publication red contracts
PreallocationTest failed on the current engine (no allocation boundary and completed transfer instead of injected stop). Publication tests failed compilation on absent filesystem seam/journal contract. Added ordered logical extension, v5 journal metadata and filesystem operation seam; engine integration follows. No new dependencies. Rename/Ask no-link fallback intentionally remains fail-closed.

## 2026-10-02 — Targeted verification
Initial preallocation/portable tests and store regression passed. Expanded writer, filename conflicts, resume and forced-kill tests passed (51 seconds). Added exact v4 migration, journal validity/invalidation and destination-choice tests, empty-file allocation, symlink/foreign-final rejection and absence-of-intent regression. Full check with 20 fixed/20 adaptive/3 refresh repetitions is running. Published the layout contract and kept ENG-05 partial for native no-clobber, block allocation, disk usage and cross-OS/power-loss gates.

## 2026-10-02 — File-layout milestone complete
Final offline Java 25 check/installDist passed in 3m20s: 257 Java tests (30 model, 4 net, 14 store, 31 engine, 178 bench), zero failures/errors/skips; 81 forced kills. Summary counts independently derived from JUnit XML, 19 additions, hashes for 16 changed Java files retained under artifacts/prd-alpha-file-layout. Documentation/audit updated; ENG-05 remains partial for native no-clobber, physical blocks/disk usage and three-OS/power-loss qualification. Security pass: SQL prepared, journal proofs bounded/validated, no-follow regular-file recovery, no copy/non-atomic fallback or new dependency. Next: source-change pause/action and retry transitions.

## 2026-10-02 — Retry transitions red reproduction
New 19-case HTTP/state suite failed 17 cases against the old engine, isolating missing bounded retries/re-probes and paused reasons. Implemented reasoned pauses, persisted AUTH_REQUIRED for HTTP 401/407, coordinator re-probes after joined workers/checkpoints, one malformed-range retry with window invalidation, and certificate-cause classification. Updated legacy CHANGED_SOURCE expectations to PAUSED; other fatal cases retain FAILED. No dependency/schema changes.

## Retry transitions — targeted result and explicit restart
Initial implementation passed targeted transition/network/parallel/media/resume and certificate tests in 53s. Restart contract reproduced the missing API at compile time. Added explicit new-ID/new-partial restart retaining old state/bytes, metadata-range retry, and valid-prefix retention on range fallback. One compound patch was rejected for duplicate file operations; no edits applied, then split patches succeeded. Added three process-kill cases after re-probe commit with a retained 64 KiB window.

## Retry budget isolation
Three re-probe kill cases and source restart passed. A two-case mixed malformed-range then HTTP500 test reproduced premature RETRIES_EXHAUSTED in payload and probe paths. Changed counters to increment only for ordinary retry attempts. Retryable response errors now carry HTTP status; only 429/503 signal adaptive throttle, while other 5xx retain ordinary retry. Added status/Retry-After parsing and live delay checks.

## Retry error/action race follow-up
Budget/Retry-After/status and re-probe/restart/certificate unit tests passed in 19s. Actual self-signed HTTPS test confirmed fatal rejection but reproduced incorrect ACCESS_DENIED guidance; introduced TLS_VERIFICATION_FAILED/AJDM-2011 with a site/certificate action, preserving strict verification. A deterministic pause at source-change checkpoint commit reproduced losing SOURCE_CHANGED to a control PAUSED result; source/disk/auth pause reasons now win a concurrent pause while explicit cancel remains honored. Final full check/installDist is running on the resulting implementation.

## First broad retry regression
Full check/installDist passed in 3m47s: {'tests': 310, 'failures': 0, 'errors': 0, 'skipped': 0}. This precedes the final atomic pause-reason checkpoint change discovered in the crash boundary audit; a new forced-kill regression will first reproduce that gap, then the final version will be checked again.

## Atomic source-pause reason reproduction and repair
Forced kill at source-change AFTER_DB_COMMIT reproduced PAUSED with a missing error reason (one failed CHANGE case; the three re-probe cases stayed green). Added a checkpoint overload carrying state/error in the same SQLite coverage transaction and wired stopped source/auth checkpoints through it. Added reopen and invalid-coverage rollback test; no schema version or dependency change. Final regression follows this fix.

## Atomic pause checkpoint — targeted result
All four forced-kill cases (including source-change checkpoint reason) and retry-transition tests passed. The new store test incorrectly expected a raw IllegalArgumentException; the established store API wraps worker failures as DownloadException. Corrected that test expectation and added an insert-trigger failure after state/error mutation to prove full reason/range rollback. Final full check/installDist is running; production checkpoint fix remained unchanged.

## 2026-10-02 — Source-pause/retry milestone complete
Final full check/installDist passed in 3m41s: 312 Java tests (model31/net17/store15/engine34/bench215), zero failures/errors/skips, 55 additions. 85 forced-kill checks: 84 matching completion files and one intentional source-change pause retaining old bytes/reason. Atomic error+coverage checkpoint fix is covered by exact-boundary kill and transaction rollback tests. Evidence artifacts/prd-alpha-retry-pause contains report, XML-derived summary and hashes for 22 changed Java files. Added retry/pause contract and updated README, plan, audit/error catalog. No dependency/schema version change, no insecure TLS switch, no external payload downloader. GUI prompts/Waiting for network, CLI/core/IPC, native no-clobber and release acceptance remain open.

## Final artifact checks
All 22 source hashes match, local links and whitespace pass in six changed documents, all 61 unique functional requirement rows remain present, and evidence totals agree. The first ID checker used an incomplete prefix allowlist (omitting BRW/DIA); corrected the checker to validate all functional prefixes and confirmed 61 rows. No Java changes followed the final passing check.

## Core/IPC/CLI implementation progress
Initial red contract run failed at missing Protocol/AjdmCli classes as intended; pinned dependencies resolved, checksum metadata generated. Added ajdm-core, ajdm-ipc, ajdm-cli and engine-only inactive cancellation/combined credential-policy resume. First compile found Java -Xlint:try warnings for deliberate shutdown-hook close; annotated that intentional lifecycle. Follow-up contract/integration tests passed. Private endpoint cleanup now treats only connection refusal as stale, preserving a live but unresponsive endpoint. Added separate-process CLI and daemon forced-kill recovery tests; running those next.

## Conflict-flow test correction and CLI help
Full regression exposed an inaccurate test setup: Ask on an already-existing target stops before any HTTP, so resume necessarily downloads. Changed the test to introduce a collision while payload is active, then verify completed bytes publish with Rename without additional HTTP. Engine behavior was correct. Installed CLI inspection found control help used the generic 'control' name; set each picocli command name and add regression/inherited-option checks. Prior source-change IPC restart case passed and kept the old partial byte-for-byte.

## Broad verification state
Engine/store/network/model and the 85 existing forced-kill checks passed in the first broad run; that build failed only on the corrected IPC Ask test. The follow-up run passed 33 IPC and 7 CLI tests, including graceful active SIGTERM, independent --wait, source-change restart and late conflict resolution. During final recovery review, added the inactive QUEUED-record edge test and snapshot fix; run final core/client regression and distribution after the current broad benchmark task finishes. No throughput policy/default change or IDM claim.

## Production core/CLI milestone complete
Final check plus CLI/bench installDist passed in 41s after the 3m31s broad run; unchanged engine/benchmark checks reused appropriately. Final XML totals: 353 Java tests, zero failures/errors/skips (model31/net17/store15/engine34/bench215/IPC34/CLI7; core service covered through real IPC/process integration). 86 forced-kill checks: 85 exact completions plus one intentional source-change pause. Active SIGTERM and --wait process cases also passed. Evidence artifacts/prd-alpha-core-cli/report.md/test-summary.json/source-files.sha256. Verified all 134 source/configuration hashes, installed distribution jar equality, linked docs/whitespace and all 61 functional IDs. No Java changes after final check. Media formats/extraction/mux, scheduler queues, native Windows ACL/cross-OS/filesystem, UI/browser/packaging and IDM/real 4G/5G acceptance remain open.

## Media implementation progress
Initial media tests failed at missing model/parser/selector as intended, then passed after implementation (including subprocess isolation, timeout, output bounds, cancellation and safe diagnostics). Added schema v6 media_origin with download/origin/header insertion in one transaction, engine media start/resume/restart hooks, and core metadata extraction outside the control monitor. IPC/CLI now exposes safe formats, ceiling/show-all and preset/custom preview plus individual-format add-media. Updated the older missing-feature assertion to missing-extractor AJDM-4001 now that the adapter exists. Added strict trusted-HTTPS payload/refresh fixtures, rollback/migration/reopen tests and paired-plan rejection before admission. Verification running.

## HTTPS resume fixture correction
First trusted HTTPS format-to-engine handoff and forced mid-transfer expiry passed. The reopened-resume file also completed correctly, but its fixture flagged the expected credential-free request to the expired old URL as a missing-header failure. Move header assertions after expired-source rejection and assert that the old resumed probe did not carry a cookie while refreshed payloads do. No engine credential policy change: Cookie stays transient and is reattached from fresh extraction. Installed yt-dlp 2026.08.19 accepts the complete extraction-only option set in a non-network --version smoke check.

## Broad-check dependency lock correction
The first full check stopped before tests: adding ajdm-media changed ajdm-core's otherwise no-source testRuntimeClasspath, which targeted runs had not resolved into its lockfile. Regenerate through Gradle --write-locks for the full check/distribution graph; do not hand-edit locks. Existing checksum metadata already covers Gson and its annotation dependency; no new library versions or metadata trust entries needed.

### Media extraction milestone complete — 2 October 2026
- Final full check and CLI/benchmark install distributions passed in 3m55s: 383 Java cases, zero failures/errors/skips; 30 new media/store/CLI cases. No Java changes followed that command.
- 87 forced-kill checks: 86 bit-identical completions, one intentional source-change pause preserving bytes/reason. New media daemon kill/reopen test refreshes expired source from persisted origin.
- Recorded artifacts/prd-alpha-media-extraction/report.md, XML-derived test-summary.json and 150 source/config SHA-256 entries. Verified all hashes, nine built/installed module jar matches across CLI and bench distributions, 84 local documentation links, whitespace and all 61 unique functional PRD rows.
- Updated README, ENGINE_PLAN, PRD audit and CLI/media guides. Explicit extractor configuration, paired mux, playlists, tool lifecycle, native OS/live corpus and real IDM/4G/5G gates are documented. Next media milestone: durable paired-stream jobs and local-only copy mux.

## Paired job implementation
Added MediaJob model, schema7 atomic parent/two-child creation, engine prepare-without-launch, parent coordinator, bounded local ffmpeg adapter and CLI custom audio/tool options. Initial red mux contracts failed on missing class as intended. First compilation caught incorrect TARGET_EXISTS enum spelling; corrected. Mux flag test initially counted input whitelists only; output is also file-only, so the assertion now counts all three. Updated migration version expectation to7. Local mux/store tests and CLI compilation passed. HTTPS/real copy mux and three independent parent-boundary kill tests are running.

## Broad/final regression
Full check and distributions passed in3m46s including four parent-boundary kills and a paired active-checkpoint kill. Control-corrected full check passed in1m49s with410 tests/zero failures, reusing unchanged bench results. Final snapshot review corrected parent resumeSupported to reflect unfinished child validators rather than always true; existing boundary recovery assertions cover prepared versus completed-stream capability. Rechecking the affected product graph/distributions while unchanged engine tests remain cached.

## Paired media milestone complete
Final capability-corrected check and distributions passed in1m50s,38tasks(7executed31up-to-date); XML confirms410Java tests/zero failures/errors/skips. 27 new cases:4MediaJobStore,6Muxer,17PairedMedia. 92forced-kill checks:91matching completions+1intentional source-change pause. Artifacts/prd-alpha-media-jobs holds report/XML-derived summary/159source-config hashes. Verified all hashes,9built/installed module jars,92local links,61unique PRD IDs,whitespace and installed add-media help. No Java edits after final check. Updated README/ENGINE_PLAN/PRD/CLI/media guides. Next:playlists with durable bounded item orchestration; no large-mux/live/native/IDM acceptance claim.

## Playlist implementation start
Initial red parser/selection contracts failed on missing classes. Added flat inventory/choice/range model, shared strict JSON reading, extraction-only flat process mode, schema8 playlist/items and pre-child UUID intent. Media/store regression passed. Added rollback/migration/intent store cases and flat subprocess flags. Coordinator has bounded lazy extraction and durable2s starts, shared existing transfer admission, per-item terminal/skip reasons and group controls. Core child admission now accepts a planned UUID; CLI/IPC integration is next.

## Playlist contract compile corrections
IPC compile caught reuse of the outer request id name; renamed playlist IDs. Test compile caught Java var in a compound declaration; made CountDownLatch type explicit. Media/parser/process/store cases pass. Scheduler polls only individual child records instead of repeatedly listing all completed history, avoiding quadratic database reads for100items. Output title sanitization happens before adding the stable item prefix. Root DRM refusal now preserves4004.

## Playlist recovery and validation review
Focused controls, four independent playlist boundary kills, per-entry format preview, invalid choices/pagination and failed-payload continuation pass. A new post-paired-admission error test reproduced a persisted child incorrectly marked SKIPPED; corrected it to pause the group while retaining intent/child for resume. Full 100-item rate and regression run follows the focused fix.

## Playlist milestone complete
Final full check/distributions passed in6m7s,38tasks(15executed23up-to-date),431Java tests/zero failures/errors/skips. 21newcases:5parser,1flatprocess,3store,12flow including4separate-JVM kills. Total96kills:95matching completions+1intentionalSOURCEpause. Real2s100-item local rate fixture passed in201.967s; every output matches, no failures/skips/rejectedstarts. Retained slow-overlap fixture observes3concurrent extractions. Post-admission error regression passed after correcting SKIPPED to recoverable group pause. Added docs/PLAYLISTS.md and updated README/ENGINE_PLAN/core/media/PRD guides; MED06 Partial with live/UI gates explicit. Artifacts/prd-alpha-playlists has report/XML-derived summary/171source-config hashes. Verified171hashes,9built-installed jars,105local links,61uniquefunctionalPRD IDs and changed-source/doc whitespace. Installed add-playlist help passes withJava25; initial smoke useddefaultJava17 and was corrected viaJAVA_HOME. Manifest count-regex initially excludedQUE/BRW/DIA prefixes; corrected checker,61IDsunchanged. No Java edits after passing final check. Next:generalpersistentqueues; live100/site/tool/UI/native/IDM gates open.

## Resume connection override implementation
Added count-preserving TransferOptions helper, atomic stopped-record connection updates and engine liveRequests synchronization; core/paired parents configure only unfinished children before launch. CLI/IPC resume accepts validated1..32; active/terminal changes rejected and playlist parent override explicitly unavailable. Store3contracts plus4realCLI cases(single/paired x sameprocess/reopen) exercise saved prefix and completed audio reuse. Initial red store compile found missing new method as expected and corrected coverage accessor to completed(). Failed initial patch context made no edits; reapplied using actual methods.

## Eight-connection resume validation
Focused store3 and realCLI4cases passed26s. Full check withdistZip is running. Built archive staged separately atajdm-cli/build/install/ajdm-resume8;8modulejars independently matchbuilt artifacts andnewresumehelp exposesconnections1..32. Originalinstalled daemonfiles untouched. No livepause/resume/cancel orSQLwrites performed.

## Resume connection override complete
Final full check+distZip passed6m26s,36tasks(14executed22up-to-date):438Java tests/zero failures/errors/skips. Seven newcases(3store+4realindividual/paired sameprocess/reopenCLI), retained96kills. Separate staged8modulejars/help/hashes/whitespace/docslinks checked;172source-config manifest atartifacts/prd-alpha-resume-connections. No Java edits after passing full check. Originalinstalled daemon files/livejob/database untouched. Read-only30s monitor saw492.8->496.1MB and rates0.144->0.056MiB/s, unstablebaseline; no8worker live speed claim. User receivesoldpause,CtrlCgracefulstop,stageddaemon sameDB/socket/toolpaths,stagedresume8sameUUID. Future queue work remains separate.

## JavaFX UI first green pass (2026-10-04)
Installed four inspected JavaFX guidance skills at pinned commit, using installed Anthropic frontend skill. Added client-only desktop module, FXML shell/CSS brand tokens, bounded polling/actions, observed-byte rate/ETA, direct/media/playlist dialogs and paused worker overrides. Rate tests first failed due to missing implementation; implemented and nine new UI unit/real-core gateway tests now pass. Strict compilation and independent installDist succeeded. Prior real-test socket absent; no user state or transfers touched. Extracted original page1 logo unchanged from provided PRD after visual inspection. GUI smoke/screenshot and documentation pending.

## UI final interactions verified
Actual window/metadata screenshots inspected. Product source refinements: defer row selection restoration until transformed lists settle, make preset/audio choice explicit, show HDR notes as meaningful text, preserve selection-size lines, pass dark theme to dialogs, and provide fullscreen for narrow tiling desktops. Extended standalone smoke passed add/progress/pause/resume8, exact output bytes, all-format/custom selections, playlist, original-logo About, dark theme and byte-identical completion after UI client shutdown. Full check447cases had one existing hundred-item rate-gate failure (99/100); targeted legacy rerun in progress, original failure evidence retained. No core/engine Java changes and seven packaged core jars still match staged resume8 daemon.

## Desktop preview delivered
Final extended graphical check and installDist passed in33s after dark text refinement. Nine new tests passed; original full check failure and isolated100-item pass preserved. Wrote usage/PRD audit/provenance and source/package/XML/screenshot evidence. Independent UI launcher opened at prior manual socket; UI remains disconnected until the user starts their existing core. No payload speed advantage claimed and no user download/state was touched.

## Backend deterministic fix and test infrastructure
Observed new dispatch test fail with actual starts 0ms apart after adding executor constructor injection; implemented per-run monotonic start spacing and conservative two-second reopen guard without serializing extraction. Regression passes. Constructor failure shuts down injected/default extraction executor. Added seeded mixed range add/remove bitmap oracle (50,000 operations + Long.MAX_VALUE edges), ArchUnit 1.5.0 five architecture rules, JaCoCo 0.8.15 aggregate backend reporting and PRD80% engine/store line gate, and seeded local HTTP fault campaign. New dependency graphs/checksums generated by Gradle, no framework changes. Initial architecture/test compile failed due to implementation-only dependency visibility; removed class literal in favor of constructor-call predicate. Focused25 HTTP chaos iterations, model and architecture pass. Extended scheduler property now configurable up to10,000 trials per matrix. No production-data use, no user daemon changes. Awaiting optional feature scope response; proceeding existing-backend verification.

## Backend security regression
Review found CoreService.addMedia accepted explicit filename traversal for both single and paired media; target normalization plus leaf-only domain checks allowed escape from the caller's chosen directory. Added local-only two-case regression (both plans, six unsafe filename forms); observed both cases fail before behavior change. Fix validation before metadata and persistence, then rerun affected suites. Current extended run already passed200randomkills,50fixed+50adaptivekills,100itemratefixtureand10k-per-matrixscheduler. HTTPchaos continuing. Avoid publishing mixed evidence from overlapping targeted reproduction; final unified check/coverage must run after fix.

## Extended campaign and final strict run
Initial campaign passed20m29s: 1000HTTPchaos,200random singleprocesskills,50fixed+50adaptivekills,120renewal-boundarykillsplusconstantpublication/core/media/playlistkills; scheduler10klease+10kwindow/stealtrials. Initial coverageengine96.08%line/store97.04%. Final ordinary strict/offlinecheck repeats stress afterfinishedsources, no dependency metadata writeflags. Rootlock had toberegenerated after the original pre-locking configuration's write-locks removed it; noJava changes. Verified8modulejars ineachCLI/UIzip, archivechecksums and packagedresumeconnectionshelp, UI modulelauncher. Documentation122locallinksvalid. Do notfinalizeevidenceuntilstrictcheckcomplete; fullstressrepeat isintentional ratherthancounting retries asnewacceptance.

## Resource baseline
Isolated freshly extracted packagedCLI daemon on disposableprivateDB/socket sampled12seconds at84.61MiBpeakRSS and0.802sreadinessincludingseparateCLIlist. Warmfilecache/noAppCDS/noUI/nopayload. ControlledSIGTERMremovedsocket. Firstobservationharnessexpectedexit0incorrectly; JVMsignalexit143isnormal, correctedharnessvalidatedcleanupratherthan treatingexitcodeasproductbug. NoformalUIcoldstart/32responseRSS/24hqualification. Finalstrictrepeatallengineclassesunchanged;200randomkillsandfixed/adaptivecompletedagain,1000HTTPcampaigninprogress.

## Strict pass and scheduler evidence strengthening
Strict wholecheck/distributions passed20m39s,49tasks(19executed30up-to-date),458Java cases zero failures/errors/skips;14Pythoncasespass. The full finalcampaign repeats1000HTTPfaults,448forcedkills (447bit-identical completions +1intentional source-changepause),10k-per-matrixscheduler and100itemplaylist202.184s. Engine96.08/store97.04linecoverage. Strengthened only scheduler test assertions before publishing: per-trial worker seeds, sampled atomic partition snapshots (no overlap/gaps), current lease write bounds, position progress/endshrink and cancellation cleanup/timeouts. ProductionJava andpackage hashes unchanged; finalcombinedcachedcheck reruns affectedengine evidence, otherfullstressinputs unchanged. Oldfreeze archived, newtesthashrecorded.

## Backend verification pack delivered
Finalstrengthenedengine suite+wholecheckpassed1m2s. RecomputedXMLaggregate458Java tests,0failures/errors/skips;14Pythonpass. Finalengine95.90%line/store97.04%line; initial96.08%enginecoverage superseded by varied seeded final schedulerexecution,stillabove80%floor. Snapshot231source/config/referencedPRDhashes, frozen197Java/build/dependencyhashes,2distributionhashes/16module-jarcomparisons,142localdoc/evidencelinksverified. Reportrecords448forcedkills447completions+1expectedsourcepause,1000HTTPfaults,two10kmatrixtrials,100itemrate202.184s,allunimplemented/physical/live/remotegatesopen. Originalinstallations/usercore/downloadDBfilesuntouched. NoJavaeditsafterfinalcombinedpass.

2026-10-04 quality-analysis UI completed. Reproduced missing ordinary Add download action with failing real JavaFX smoke. Added Analyse qualities beside Paste; preserves URL, folder, filename, connections and triggers metadata-only inventory in existing media picker. Renamed sidebar Video qualities and picker action Analyse qualities. Analysis no longer requires a preset selection; URL changes invalidate inventory and disable Start. Nine unit tests and expanded graphical smoke pass; final44s pass includes restoring clipboard fixture contents. installDist/distZip built; screenshots/evidence in artifacts/quality-analysis-ui. Reopened only UI: old163233 replaced283757. Existing backend281605 still responds at same socket; no user job controls issued.

2026-10-04 readable-quality picker complete: MediaChoices presentation utility with13 total UI tests; every extracted row remains visible and sorted by resolution/fps, readable HD/Full HD/QHD/4K/8K/HDR/audio language/bitrate/codecs. Default chooser requires explicit row selection, video-only rows auto-pair best available audio; audio dropdown, Include sound and audio-only/combined rows select exact backend IDs internally. IDs/preset codes hidden in collapsed details. Preview preserves explicit audio/silent choice and invalidates stale output hints when selections change. Unit and full graphical check pass; build/package40s. Evidence artifacts/readable-quality-picker includes final log, source hashes, unit XML and screenshot. UI283757 replaced286016; backend281605 remains responding. No user job controls, no backend modifications.

2026-10-04 first full stream-controls check passed in7m31s. Store/core/IPC/UI/ArchUnit and graphical checks passed; user chose optional file deletion, checkbox off by default. Added final coordinator-child stop guard during that run, so rebuilding/rechecking final source before staging current backend. No further Java edits planned.

2026-10-04 stream controls completed: final frozen-source whole check + UI smoke + CLI/UI packages passed in7m18s;478Java zero failed/error/skipped,15UI,engine95.27%/store97.28% line. Live backend314862 stagedCLI786d5a07ff2b; UI314900 reopened. Two existing user jobs and output identity/size/mtime preserved; zero user removals. Private backup saved; .runtime/ajdm points at verified current CLI. Evidence artifacts/stream-controls/report.md. No further Java edits after qualification.

## Explicit adaptive media wiring (2026-10-04)
New parameterized real HTTPS CLI regression reproduced missing --adaptive for both single and paired streams. Fixed-mode controls initially also flagged expected credential-free reopen probes; corrected the fixture by explicitly reattaching its cookie/User-Agent, then all four scheduling/pause/reopen cases and updated graphical smoke passed (38s). Added optional media CoreService/IPC/CLI flag and existing UI adaptive checkbox in media flow, preserving choice through Analyse and saved scheduling on resume. Old callers, playlists and jobs stay fixed unless explicitly chosen. Documentation patch had an unmatched context and applied no changes; reapplied with exact file content. User supplied J70bt3I_1i4; metadata-only discovery confirms format313 VP9 4K,717861088bytes. Existing user job15650ab0 was active then became PAUSED before read-only30s baseline; no controls sent. Independent AJDM-engine sampling now runs only while user core remains idle.

## Window refinement focused green (2026-10-04)
Twenty old requests reproduced excess churn on2MiB/256KiB/s/150msfixture. Floor1MiBboundedbycallerwindow andconfiguredmaxwindows passed all focused AdaptiveIntegrationTest21cases plusengine suite in1m9s, including bitexact faults/shared-caprollback/pushback/tailstealing/pause/reopen. Added safe result telemetry to the regression after focused run; nextfullcheckwillrunfinaltestsource. Immutable old-engine three-round matrix finished54SHAverifiedoutputs acrosssixconditions, benchmarkconstruction/rehash excluded; simulation only. Running secondnine-sample real comparison using newenginejarfirst and previously verified dependencies, while coreidle and no concurrentfixture/Gradleload. No final rollout of window policy yet.

## Window regression verified in final run
Final AdaptiveIntegrationTest21cases passed; WINDOW_CHURN_RESULT:8payloadrequests,2097152verifiedbytes,9.207seconds,versusold20requests. Engine/bench correctness/crash checks finished; currentCLIintegration/ratechecks stillrunning. No Java changes since the final freeze. Sourcepackagingchecks match eightAJDMmodules for finalCLI/UI; finalCLIzip1c258c496589 staged only, notloaded.

## Final engine/window qualification passed
Finalfullcheck+GUI+CLI/UI/benchpackagespassed6m48s:483Java tests,0failures/errors/skips,80%engine/storelinegate. NewCLIzip1c258c496589 andUIzip1de01083fa80 haveeightAJDMjarsexactlymatchingbuilds;178Javasourcesstillmatchfreeze. Loaded finalbackendPID338415 whileusercoreidle, preservingbothpublicsnapshotsandallpartial/outputinode/size/mtime, privateonlinebackupretained. Keptalready-updatedUI325750open. No userpause/resume/cancel/delete commands orautoresumes. Finalnewwindowthree-roundsixconditionmatrixstartedafteralltestsended, independentprivatefixturestate.

## Completion notifications focused green
Both strengthened fixed/adaptive last-lease tests passed8s; each proves two already-waiting observers wake promptly and completion-before-wait returns without losing notification. Normal sample intervals remain100/250ms with monotonicdeadline/spurious-wake loop; lastleasecomplete/release broadcastsdone. Third/finalfullcheck+packagesrunning with179Javafilesfrozen. Livepolicyremainswindow-only untilfinalverifiedrollout; sourcecomparison samples remain earlierwindowpolicy observations, no newYouTubepayloadtestsneeded for completed-small-file waitingfix. Finalbenchmarkwillusefreshisolatedfixturestate afterfullcheckfinishes.

## Speed milestone complete
Finalfullcheck+GUI/packagespassed6m47s,485cases0failures/errors/skips,engine95.32/store97.28line. FinalCLI3130f67a4c63/UI51839ef3518b eightjarhashesmatchbuilt modules;179Java/source-configmanifeststillvalid. Finalbackend350437 loadedidle; UI325750keptopen; userpublicrows/filespreserved, paused videofourfixed withcompleteaudio untouched. Finalmatrix54SHAverifiedoutputs(noextra received bytes) completes162localoutputsacrossthreepolicy stages; median8MiBadaptiveloopback.3125->.3106->.1241seconds. Delayedregression20->8requests. Two realnine-samplebatches68MiBapprox;fixed8bestmedian.373latest,adaptive.231. CDNoriginchangedbetweenbatches:donotclaimcausallivegain. Report/artifacts/adaptive-media-speed anddocs/SPEED_TESTING completed. All authorizedbounded implementation, qualification and currentUI/backend delivery done; no pending tests or edits. Next iteration needslongsteadyfull-source and physicalmobile/IDMacceptance, remainsunclaimed.

2026-10-04 platform/details implementation: Details now applies to selected files/media/groups, live updates file/media info and group status, supports native folder/completed-file opening and browser credential retry off FXthread. Direct file form exposes optional validated browser request fields; media cookiefile configuration remains explicit/transient and copies bounded Netscape files privately. Fixed omitted scoped cookie metadata, top-level direct single source selection and exact collection child extraction/refresh. Eight new platform/direct/collection integration cases pass, core/IPC/store/architecture/UI tests pass, realX andInstagram files completed with fingerprints. Full initial run7m14s had one older bench handler-count assertion failure; fixed metric interpretation and final full run is underway. TikTok source retrieval remainsintermittent; FB supplied/canonical links all fail latest-stable metadata extractor. Existing user's two jobs are nowcompleted by user activity, not controlled by agent.

Platform/details final qualification: BUILD SUCCESSFUL7m10s,501Java tests0failures/errors/skips, GUI pass, engine95.32/store97.28 line coverage,80%gate+ArchUnit pass.185source/resource hashes unchanged;8modulejars in each packagedZIPmatchedbuiltbytes. Idle rollout with privateSQLitebackup preserved both existing completed jobs' everyoriginalpublicfield and output device/inode/size/mtime. Backend397681 immutableCLIa4475e6b2a1a, UI397781 matchingUId210ccf09aad. Stable.runtime/ajdm pointsnewbackend. No usercontrols/resumes sent. X/IG realfiles complete+H264/AACdecode; anonymous suppliedTikTokboth540prepresentations return403evenwithfreshheaders/cookies andno redirects (audioGET206), FB lateststablemetadata Cannotparsedata. Theseexternalcasesremainexplicitlyunqualified, no universalplatformsupportclaim. See artifacts/platform-downloads/report.md androllout.json.

2026-10-04 ArDownload port: initial new11resolver/facade cases fail compilation before implementation; Java metadata resolver/parser and inventory-preserving facade added. Resolver format labeling test fails before UI fix. Production CLI/defaultcore wired tofacade, originalYtDlpExtractor isolationunchanged. StrictHTTPS/CDNdomainboundaryvalidation, numericpostidentity, bounded2MiB/5secmetadataattempts, cancellation/close, no sessionheadersandno resolverforcookiesconfiguredlinks. Added resolver tier text/disclosure inexistingpicker. Payloadengineunchanged.

2026-10-04 TikTok live qualification completed41767003bytes after resuming30875593durable bytes, SHA2565cad6ba878b43ca69203494ed092ac5bbe1a7f1b0238a10ef28dd3fd66214496. ffprobe reports720pH264+AAC/233sec, fullffmpegdecode0errors. NewHEAD casesgreen; added exactresolverchoice expired/reopen HTTPS integration test plus sessionconfigurationrace/DRM/watermark semantics regressions. Fullcheck/packages/UI smoke ongoing, userjobs untouched.

2026-10-04 ArDownload port finalcheck BUILD SUCCESSFUL7m12s,521Java tests0failure/error/skip, actualgraphicalUIcheckpasses, engine95.48/store97.28linecoverage and80%gatepass. CLI/UIpackages8modulejarsbyteidenticalqualifiedbuild. Idle backend417437/UI417549 updated; stablelauncher.runtime/backend-verified/69fd89b751fa; backupbefore-ar-download-1791113368504746717.db. Both originalCOMPLETEDjobs/publicfieldsandfileidentitiespreserved; usercontrols0. Fullsources/resources190hashesunchanged. Evidence artifacts/ar-download-port/report.md and rollout.json. NativeytTikTokCDNs/provideravailability/privateIGsessions remaincase-specific; noallformats orIDMbenchmarkclaim.

Finalproductionmetadatarecheckreturnednative10formatsbutnoresolverchoices. Directpackagedresolverprobe succeeded4.15sec; 5-secondbudgetleaveslittleheadroomandlegacymetadatawaitoccurred17.2secfirst. Addedtwoactual red regressions:6secvalidresponsefailsold5seconddefault;selectedresolverrefreshstillinvokesprimarybeforefix. Revisedresolver10sec matchingArDownload, publicanonymousTikTokprimarycap45sec preserves75secIPCbudget, newextractSelected SPI routeschosenresolverformatdirectly duringadmission/renewal (samepost/exacttierchecked). Otherplatformandcookiedextraction keep60sec. New finalchecksneeded; existingrunningpackageremainspre-reliabilityfixuntilverifiedidleupdate.

Reliabilitytargetedgreen passed59sec:6-secondvalidmetadata accepted;exactresolver admission/renewal skipsprimary andpreservesidentity; actualHTTPS core-reopen/resume regressiongreen;alltargetedextractor/process testsandGUI smoke pass. Finalfull523-casecandidategate started plus ownsmallTikTokMP3live transfer forfreshselected-providerpath. Finalsource190filesfrozen; production69fdbackendstillrunninguntilfinalgatepasses.

Finalreliabilitycandidate actualTikTok MP3 completed3,728,658bytes in14.12sec payloadtime, SHA2568d684c370ca2b6f95b774ed5264614f829d786acff5430305e95c7bd8df6a810. ffprobe MP3/233.04sec, fulldecodepassed; this exercises directselectedresolveradmission withoutprimary extraction. Userjobsremaincompletedanduntouched. Finalfullcandidate gate ongoing.

Finalreliabilitycandidate fullgate BUILD SUCCESSFUL7m22s:523Java tests0failure/error/skip;GUI/ArchUnit/80%linegategreen(engine95.48/store97.28). Finalbackend426887/UI426989live; immutableCLIstagebbbd0fe4f2d2, backupbefore-ar-download-final-1791114600560974673.db. Rolloutagainpreserved2COMPLETEDjobs/publicfields/outputidentity-size-mtime; usercontrols0. Finalpackagedmodulesbyte-matchnewbuild/source190hashesunchanged. Finalproductionmetadatachoicecheckpending.

Finalproductioncoreformats succeeds18.38sec, exactpost7692398611304303902,14formats(10native+4namedresolverchoices). Final190source/resourcehashesstillmatch; workcompleteforpublicsample fixes. Currentbackend426887/UI426989, usertransfers/fileidentitypreserved. Externalresolveravailability/privateIGsessions/allnativeCDNqualityvariants remainexplicitlimits; fullreport artifacts/ar-download-port/report.md.

FB/IG: metadata-only reproductions completed for originalFB, oldheaders andcanonicalvariants; none returnsstreams. OldIGdiagnostic emptyresponse. Added regression-firstexplicitbrowsersession surface, atomicfile/browserconfig, privatecookiecopies, resolvercredentialboundary andcatalogsafe4006/4007siteerrors; focusedtests running. No browser wasread, no userdownload modified.

NewactiveuserFILE632df350-4ba3-4f72-ad1d-79760e5b52db total146768256, observed78.7MB; blockedrolloutuntilidle. Donotinterrupt. Package/sourceconsistencychecks passed; fullgate running.

Finalfullgatepassed7m16s,530tests,0failures/errors/skips,UIgraphicalchecks,ArchUnit/coverage,packagesbyte-identical. Readyforidleupdate; waitingforactiveuserFILEtofinish. No activejob waspaused/canceled.

Correctedfinalgate531tests,0failures/errors/skips in6m55s,UI/ArchUnit/coveragepass; verifiedfinalpackages andsourcehashes. DesktopDBus/XDG context ispresentinlauncher environment withoutreadingbrowserstores. Onlypendinglocalrolloutwaitsforactiveuserfile137579582/146768256. BlockedMeta posts stillneed user-selectedsessiontoverifyactualaccess.

Idleupdatecomplete: activeuserFILEcompleted146768256bytes; preservedall5completedjobs/outputs,0usercontrols,privatebackup. Backend451632/UI451742fromimmutablefinalstages, stableCLI/runtimeUIlaunchersupdated. Noactualbrowserwasreadbyagent; user-selectedsignedsessionqualificationremainspending. Runningmetadata-onlyFB/publicIGcheck inprogress.

Finalrunningbackendconfirmed6publicIGformats(720/1080p)andcatalogsafe4006fororiginalanonymousFBlink. Backend451632/UI451742alive;5completedjobs/outputs preserved,0pause/cancel/delete actions. Implementationandlocalrolloutcomplete; usermustselecttheirsigned-inbrowserforblockedpostaccessqualification.
