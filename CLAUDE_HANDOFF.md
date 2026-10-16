> **Moved to Git (16 Oct 2026):** this handoff, `AJDM_PRD.pdf`, the agent notes (`docs/agent-notes/`) and lab result tables (`docs/netlab-results/`) now live in the private repo on branch `feat/dynamic-splitting`. Paths below that start with `/home/ariga/Documents/AJDM/artifacts/` are on the Linux machine only (large evidence and downloads stay out of Git). `dahl-credentials.txt` was never committed and must not be.

# AJDM handoff to Claude

Updated 16 October 2026. Continue the existing project; do not restart the implementation or assume it is production ready.

## 1. Product goal and governing documents

The two core objectives are:
1. Discover all available supported, non-DRM formats/qualities from platforms such as YouTube and let the user choose the desired video/audio combination.
2. Download that choice through AJDM's own engine, with resilient resume and a measured ambition to outperform IDM on physical 4G/5G networks.

The priorities are adaptive segment rebalancing, durable resume after interruption/process death, and strict separation between media extraction and actual downloading. Faster than IDM is a goal, not an achieved result.

Read these local files first:
- `/home/ariga/Documents/AJDM/AJDM_PRD.pdf` — governs product scope and acceptance.
- `/home/ariga/Documents/AJDM/architecture.md`
- `/home/ariga/Documents/AJDM/ENGINE_PLAN.md`
- `/home/ariga/Documents/AJDM/docs/PRD_ALIGNMENT.md` — contains an older audit table plus later updates; reconcile it with current code/evidence.
- `/home/ariga/Documents/AJDM/docs/SPEED_MILESTONE.md`
- `/home/ariga/Documents/AJDM/docs/WINDOWS_PREVIEW.md`
- `/home/ariga/Documents/AJDM/.agent-notes/windows-v1/{task_plan,findings,progress}.md`

`yt-dlp` is extraction-only: formats, media URLs and necessary headers. It must not download media as a fallback. AJDM owns transfers, scheduling, writing and recovery. FFmpeg muxes completed local streams with stream copy. DRM bypass is outside scope. HLS/DASH fragment downloading is deferred in the PRD; do not claim universal platform coverage.

## 2. Workspace and Git — important

Active Java project: `/home/ariga/Documents/AJDM`.
Older project: `/home/ariga/Desktop/ArDownload`; preserve it. The conversation originally covered a Node/web application and an ADM migration, but AJDM and its architecture/PRD are now the active direction.

Private repository: `https://github.com/arigavincent/AJDM`.
Configured GitHub account used for the build: `arigavincent`.
Remote main checked on 16 October: `ba14ceac9f29f1a58857b9400cc75b5f8ae64532` (`Assert adaptive worker bounds independently of server cleanup timing`).

The active AJDM directory currently has NO `.git`. A clean source checkout used for pushes was `/tmp/ajdm-windows-repo`, but that temporary directory is now gone. Clone the private repository into a durable adjacent directory and compare it with the working AJDM tree before reconciling changes. Preserve local PRD material, artifacts, databases and user downloads. Do not blindly overwrite either tree or confuse ArDownload's Git repository with AJDM.

This handoff file is local and has not been pushed.

## 3. Implemented foundation

Java 25 / Gradle multi-module application with a JavaFX desktop UI. This is a local desktop/core architecture, not a Spring web-server migration.

Modules:
- `ajdm-model`: shared values/options/errors and private-file permissions.
- `ajdm-net`: HTTP probing, transport, response validation, cancellation.
- `ajdm-store`: SQLite persistence, migrations and recovery metadata.
- `ajdm-engine`: range scheduler, bounded positional file writes, checkpoints, adaptive control, connection budgets and host learning.
- `ajdm-core`: job orchestration, including media and playlists.
- `ajdm-ipc`: local command transport and dispatcher.
- `ajdm-cli`: command-line client/core tooling.
- `ajdm-media`: extraction, selection, paired-stream planning and local muxing.
- `ajdm-ui`: JavaFX desktop, progress, controls, direct/media/playlist dialogs, settings and themes.
- `ajdm-bench`: deterministic HTTP fault fixtures and performance/recovery checks.
- `ajdm-verify`: architecture rules.

Implemented behavior includes fixed parallel ranges; adaptive trials, rollback and server pushback; protected tail stealing; isolated transport-pool recycling; persistent expiring host hints; flush-before-metadata-commit recovery; source validation/refresh; single-stream fallbacks; checksums; safe naming/publication; direct/media/paired/playlist orchestration and controls. Implementation does not by itself establish every PRD acceptance criterion.

## 4. Speed milestone and honest limits

Evidence: `/home/ariga/Documents/AJDM/artifacts/speed-milestone-20261013/REPORT.md` and its `results/` directory. `sources-before/` preserves an earlier source snapshot; `launch-preview.sh` and `packages/` preserve the Linux preview.

At that milestone, the local full check passed 539 tests, with engine line coverage 94.43% and store 97.18%. These are historical results, not the latest branch status.

The final bounded Wi-Fi comparison measured median useful throughput:
- Old adaptive: 0.407 MiB/s.
- New adaptive: 0.373 MiB/s.
- Fixed eight connections: 0.438 MiB/s.

These were partial samples, not complete-file hash comparisons. They do NOT establish an adaptive speed advantage or superiority over IDM. Fixed remains the preview default. Physical 4G/5G, matched IDM, cold/warm connection and TCP-count qualification remain outstanding. Logical worker/active-response limits are not proof of physical socket counts.

## 5. Windows preview completed

Local installer:
`/home/ariga/Documents/AJDM/dist/windows-v1/AJDM-1.0.0-preview.1-windows-x64-unsigned.exe`

SHA-256, verified locally on 16 October:
`05fa6642d72b36c19966688913013716e25c66b90261f46f31da790bbda5959f`

Companion files in that folder: `README-WINDOWS.md`, `SHA256SUMS.txt`, `build-status.json`, `package-smoke.json`, `installed-smoke.json`.

Successful Windows workflow:
`https://github.com/arigavincent/AJDM/actions/runs/38386426914`
Artifact: `AJDM-v1-preview-windows-x64`; 30-day retention. Installer was built from commit `460102b`. Subsequent commits changed CI/tests, not the application's production Java source.

It bundles Java 25, JavaFX, yt-dlp, Node and FFmpeg. The target is Windows 10/11 x64; automated testing ran on a Windows Server 2022 runner. 59 Windows checks passed, including byte-identical pause/reopen/resume. Both the app image and actual installed EXE passed JavaFX-window, core ping, CLI query and graceful-shutdown checks. A physical Windows user acceptance session has not been performed.

Key packaging files:
- `packaging/windows/Build-Windows.ps1`
- `packaging/windows/Test-WindowsPackage.ps1`
- `packaging/windows/Finalize-Windows.ps1`
- `packaging/windows/tools-lock.json`
- `packaging/windows/THIRD-PARTY-NOTICES.md`
- `.github/workflows/windows-preview.yml`

Windows uses IPv4 loopback with a fresh 256-bit capability held in an owner-only ACL descriptor and an exclusive endpoint lock. It is not an HTTP API. Unix systems keep their Unix-domain-socket transport. `PrivateFiles` checks owner and permissions; Windows descriptor cleanup uses capability identity because its basic file key can be null. Cookie copies receive private initial permissions.

The packaged desktop starts a bundled core process and requests authenticated shutdown so checkpoints can settle. Preview state is `%LOCALAPPDATA%\AJDM\v1-preview`; default downloads are `%USERPROFILE%\Downloads\AJDM-v1-preview`.

Packaging pitfalls already resolved:
- Pass core-mode arguments explicitly: jpackage default arguments are replaced by supplied command-line arguments.
- Link explicit runtime modules; broad service binding pulled build-time `jdk.jlink` into a runtime built from Temurin without packaged jmods.
- jpackage can restart into a child process to configure DLL loading; the JavaFX window belongs to that child. Smoke checks inspect the exact executable path.

## 6. FIRST task: fix the actual remaining CI failure

Latest full backend run:
`https://github.com/arigavincent/AJDM/actions/runs/38388849762`

Status verified on 16 October: Linux FAILED; portable Windows and macOS checks PASSED. Downloaded XML evidence is in:
`/home/ariga/Documents/AJDM/artifacts/windows-v1/latest-linux-ci/`

The XML totals are **544 tests, 1 failure, 0 errors, 0 skipped**. The remaining failure is:
`BackendArchitectureTest.desktopClientCannotOwnDownloadsOrTheDatabase()`

Violation:
`io.ajdm.ui.PackagedLauncher.serve(...)` constructs `CoreService` from the UI package (`PackagedLauncher.java:63`).

Fix the responsibility boundary, not the architecture rule. Move packaged core bootstrap into an appropriate non-UI module/entry point (the CLI module is a candidate because it already depends on IPC/core). Keep the desktop launcher a process supervisor and IPC client. Update the `AJDM-core` jpackage entry point/main JAR and argument handling. Avoid introducing a core-to-IPC dependency cycle. Rebuild and rerun Windows packaging after this production-code change.

Earlier hosted failures were addressed separately:
- Fresh Linux runners lacked FFmpeg: CI now installs it.
- Gradle configuration cache allowed parallel execution despite `--no-parallel`: full hosted checks now also use `--no-configuration-cache --max-workers=1`.
- One adaptive test equated server handlers with live engine workers. A fully received response can leave a handler closing while its client worker starts another request. The test now checks worker-slot/admission bounds while retaining trial acceptance and byte coverage. Targeted adaptive checks passed locally after that change. The latest full run's sole failure is the architecture violation above.

Use the existing workflow and test configuration; do not weaken assertions or suppress failing architecture rules to make CI green. After the bootstrap fix, rerun:
`./gradlew --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --console=plain check backendCoverageGate :ajdm-cli:distZip :ajdm-ui:distZip`

Use Java 25 and ensure FFmpeg is available. Old `/tmp` Gradle caches/checkout paths may no longer exist. Update the validation report and PRD audit with actual new results. The final full coverage gate is NOT currently confirmed green.

## 7. Trusted signing: still blocked on personal enrollment

The user requested a publicly trusted signature, chose an individual publisher, and asked us to find a provider. We selected SSL.com IV Code Signing with eSigner integration:
- `https://www.ssl.com/products/software-integrity/code-signing/iv/`
- `https://www.ssl.com/products/software-integrity/signing-service/`

No certificate has been purchased, identity verified, signing account enrolled, or signing secrets supplied. The current EXE is **NotSigned**, not publicly trusted. No self-signed substitute was used. Do not claim otherwise.

The user must complete provider payment/identity verification in their own account. Confirm current pricing and eligibility before purchase. Never request passwords, signing secrets or government ID in chat.

The prepared workflow takes `trusted_signing=true` and uses these GitHub Actions secrets:
`ESIGNER_USERNAME`, `ESIGNER_PASSWORD`, `ESIGNER_CREDENTIAL_ID`, `ESIGNER_TOTP_SECRET`.

It signs the three native launchers, builds the installer, signs the installer, and requires valid timestamped Authenticode signatures. This signed path has not been exercised with an enrolled identity. No guarantee of immediate SmartScreen reputation should be made.

## 8. Recommended order after CI is repaired

1. Rebuild the Windows EXE, verify its checksum and repeat installation/startup/shutdown checks after the bootstrap move. Give the user the new artifact and guide for testing.
2. Run physical Windows tests: direct file and YouTube selection, paired audio/video playback, pause/restart/resume, network loss, expired URL renewal, cancellation and path/disk errors. Preserve existing downloads/state.
3. Complete trusted signing only after the user's verified individual enrollment; verify the signed installed app as well as the installer.
4. Complete the PRD's live media corpus and format-picker checks, including authentication, unavailable content and graceful errors. Never promise every platform/every scenario is supported.
5. Run controlled, matched IDM comparisons on physical 4G/5G with complete-file hashes, repeated trials, useful throughput, duplicate bytes, startup/resume latency and actual connection observations. Optimize measured bottlenecks while preserving recovery guarantees.
6. Reconcile the PRD audit and prioritize remaining release requirements such as product queues/scheduling, proxy support, rate limiting, browser integration, updater/distribution and native platform qualification according to the PRD. Do not start another rewrite.

## 9. Working expectations

The user prefers concrete implementation, continuity and honest progress over repeated permission questions. Preserve the previous project and existing data. Use the available engineering, JavaFX, frontend and testing skills where applicable. Do not claim production readiness, universal platform support, trusted signing or speed superiority without evidence. Keep secrets, cookies, signed media URLs, databases and actual downloads out of Git and public logs.

First response to the user: summarize the verified state, identify the packaged-core architecture failure and signing enrollment blocker, then proceed with the bootstrap fix and validation. The user is transferring work to Claude; no further implementation was performed after that handoff request.

## 10. Update after Claude session (16 October 2026)

- Git checkout now lives at `/home/ariga/Documents/AJDM-repo` (branch `fix/packaged-core-boundary`, PR https://github.com/arigavincent/AJDM/pull/1, not merged). Changed files were copied into this folder too, so the source trees match.
- §6 is fixed: `AJDM-core` runs `io.ajdm.cli.PackagedCore`, and the UI no longer constructs `CoreService`. Hosted backend 38855430800 is green (Linux 547/0, Windows/macOS portable). Local Java 25 run: 547 passed, coverage gate passed.
- Fixed a fixture race in `FaultyRangeServer` peak counting. One hosted adaptive NORMAL 20 s hang is still unexplained and could not be reproduced locally. The test now dumps threads when it times out (look for `TRANSFER_TIMEOUT` in the test XML).
- New unsigned installer: `dist/windows-v1-core-boundary/AJDM-v1-preview-windows-x64/` with SHA-256 `9bbdaf29…7699b`. 59 Windows checks and the installed-EXE smoke test passed.
- Still open: physical Windows testing, SSL.com enrollment and signing, the 4G/5G and IDM comparisons, and the rest of §8.

## 11. Status 16 Oct 2026 (late) — moving to Windows

- Branch `feat/dynamic-splitting` pushed at 334c2a1 (PR #2). Lab results (emulated, IDM under Wine) are in `docs/NETLAB.md`.
- Real-network test on the user's own link was paused after round 1 (OVH `https://proof.ovh.net/files/10Mb.dat`, 10 MiB, SHA-256 e5b844cc57f57094ea4585e235f36c78c1cd222262bb89d53c94dcb4d6b3e55d): curl ×1 0.098 MiB/s, aria2 ×16 0.259, AJDM fixed 16 0.282, IDM ×16 (Wine) 0.214. One trial only; not conclusive. Saved in `artifacts/realnet-20261016/` with the script.
- Next on Windows: native IDM vs AJDM (and aria2 for Windows) on the same real file, alternating order, 3+ rounds, SHA verified. The user approved real-network use; the link is roughly 100–300 KB/s, so keep files small (10 MiB) and say how much data a run will use.
- AJDM on Windows: build with Java 25 (`gradlew :ajdm-bench:installDist`) and run `io.ajdm.bench.LabDownload fixed 16 URL OUT SHA256`, or use the packaged app. IDM: set Connections to 16 (Options → Connection) and time from queueing to completed file.
- Open engine work: size request windows by measured rate in fixed mode (16 MiB windows helped 5G ~2% and cost typical-4G ~1%).
