# Task: finish AJDM backend verification and remaining backend pack

## Active task: details, platform media, playlists and direct URLs
- [x] Reproduce and fix the details action and qualify its actual GUI interaction.
- [x] Trace metadata, selection, download, resume and playlist flows for TikTok, Facebook, Instagram, Twitter/X and direct HTTP(S) files; implement missing supported paths (single-source/unknown metadata, scoped cookies, collection identity and MP3 pairs).
- [x] Cover successful and rejected sources with meaningful isolated integration tests and document site-specific login/stream limitations. X/Instagram live files complete and decode; supplied Facebook/TikTok anonymous sources remain externally blocked/unqualified.
- [x] Run relevant/full checks, package and update idle live services without interrupting user downloads; report verified capabilities and external gaps. Final501tests/graphicalchecks pass; idle rollout preserved both completed jobs/files.
Preserve AJDM-only payload downloading, yt-dlp metadata-only use, local ffmpeg copy mux, existing jobs and user files. Do not collect browser credentials automatically or claim live-site qualification from fixture tests.

User requests best suitable backend testing skills and immediate work toward finishing the backend. PRD controls completion; local passing tests do not prove physical power-loss, cross-OS, live-site or actual IDM/mobile-network acceptance.

- [x] Inspect/install appropriate Java/JUnit, property/state testing and debugging skills with pinned provenance; map backend gaps to executable checks and implementation/external acceptance.
- [x] Reproduce and fix delayed-start playlist spacing without relaxing rate or losing durable restart protection; verify pause/reopen/cancel.
- [x] Extend backend invariants, concurrency/fault coverage and repeatable automated test pack with safe isolated fixtures.
- [x] Run the full suite, extended crash/chaos/interleaving gates and security/architecture checks; fix failures and preserve concrete evidence.
- [x] Record remaining backend implementation requirements in PRD order and explicit physical/external gate status. Implementation remains future work; this pass completes verification/fixes of the backend already built.
- [x] Package verified outputs and document reproducible tests/current limitations.

Constraints: Java25, AJDM alone owns payload HTTP/download scheduling. yt-dlp metadata only; ffmpeg local copy mux only. Preserve user database/files/running daemon and staged resume8 distribution; use disposable state. No speed claims without matched IDM evidence. No second SQLite owner, no automatic resume of user jobs. Avoid dahl-credentials.txt.

First issue: previous full447-case run had one unchanged100-item rate gate failure (99 actual extractions), isolated retry passed. Hypothesis: durable start reservation precedes SQLite writes/executor dispatch, so a delayed previous invocation shortens actual spacing. Need deterministic failing reproduction before fix.

Scope assumption while optional clarification remains unanswered: complete qualification and fixes of the implemented backend now; retain missing product/backend features as explicit next implementation work, not passed or implemented. Added a second real backend filename-escape regression and fix. The extended campaigns and final source-consistent checks are complete.

Finished implemented-backend verification: strictfullpass20m39s, finalstrengthened-schedulercombinedpass1m2s,458Java+14Pythonpassing,80%linefloorenforced(engine95.90/store97.04),1000HTTPfaults,448forcedkills(447completed/1expectedpause),10k-per-matrixsampledpartition/writebounds. ProductionJava/packagesunchangedafterfullstrictpass. FuturebackendfeaturesstillopeninPRDaudit; do notclaimwholeproductcomplete.

## Current task: make quality analysis discoverable (2026-10-04)
- [x] Trace ordinary Add download vs Media picker: analysis already exists only in Media picker under Discover formats.
- [x] Add Analyse qualities to ordinary URL entry; carry URL/folder/filename/connections into media picker and start metadata analysis only.
- [x] Verify real JavaFX interaction using disposable fixtures, package and reopen updated UI without touching running core or user transfers.

## Current task: restore human-readable all-quality picker
- [x] Show every extracted format as a readable quality, with no IDs required for selection.
- [x] Pair chosen video with audio automatically; offer readable audio choices and audio-only selection; keep unsupported streams visible and blocked.
- [x] Test exact format selection, unknown sizes, audio pairing, URL invalidation, package and reopen UI while preserving the running backend.

## Current task: state-aware stream controls and deletion
- [x] Implement durable removal of stopped direct/paired/playlist jobs without deleting files; reject active jobs, hidden paired children and unsafe playlist races.
- [x] Add Delete/Restart/context actions and explicit state/available controls to selected download UI; keep confirmation bound to chosen ID.
- [x] Cover store graph atomicity, IPC policy, UI actions and cancellation/deletion on disposable fixtures.
- [x] Qualify builds/packages and update running UI/backend only when no user jobs are active; preserve user jobs/files.
User selected optional file deletion. Entry-only remains the unchecked default; explicit checkbox enables guarded file deletion.

## Current task: adaptive speed on slow and fast networks
- [x] Trace real media scheduling, shared resource limits and available benchmark diagnostics.
- [x] Reproduce media bypass of adaptive tuning and enable measured tuning for newly admitted media without changing existing jobs.
- [x] Reproduce and fix adaptive request-window churn and completion polling delay observed in slow/delayed and fast fixtures; verify slow, varying, server-limited and fast conditions with byte correctness and resume guarantees.
- [x] Package verified outputs and document a fair real-network comparison, with measured claims only.
Constraints: preserve live jobs/files; no automatic real-network payload transfers without a supplied comparison source. Do not equate simulated throughput with IDM/4G/5G acceptance. Keep AJDM as the sole payload downloader. User URL question is pending; independent local qualification proceeds.

2026-10-04 first full stream-controls check passed in7m31s. Store/core/IPC/UI/ArchUnit and graphical checks passed; user chose optional file deletion, checkbox off by default. Added final coordinator-child stop guard during that run, so rebuilding/rechecking final source before staging current backend. No further Java edits planned.

## Active task: port ArDownload TikTok/Instagram fixes while preserving the Java engine
- [x] Locate and trace the previous project fixes and reproduce the relevant live source failures.
- [x] Implement safe extraction/request fixes in Java with exact-selection/renewal regression tests.
- [x] Verify real TikTok and Instagram payloads through AJDM, run required checks, package and update idle services. Video/resume, MP3 and Instagram pair complete/decode; final523Java tests plus graphical checks pass. Finalrunningcore14formats includes4resolverchoices. Idle rollout preserved2completed userjobs/files.
Constraints: AJDM Java owns every media payload request. yt-dlp metadata only; no external payload downloader. Preserve existing downloads/state, scoped secrets and resume identity. Resolver metadata must never receive cookies or authorization.

## Active task: Facebook and Instagram qualities in the user's UI
- [x] Inspect all ArDownload runtimes and diagnostic code, compare real extraction and reproduce missing qualities.
- [x] Port explicit browser-session support and correct hidden analysis failures with regression coverage. Anonymous blocked Meta posts still need a user session to qualify.
- [x] Verify public analysis and qualified packages; update idle services preserving5completedjobs/files. Signed-in access to the blocked Facebook/Instagram examples still needs the user to select a session.
Constraints: Java owns payloads; yt-dlp metadata only. No automatic browser credential reads. Preserve existing jobs/files and TikTok improvements. Don't equate one public Instagram sample with solving user links.
