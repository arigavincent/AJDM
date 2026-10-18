# Progress
- Read JavaFX packaging, planning, and security guidance. Inspected source/CI and configured GitHub account metadata. Researching trusted signing providers.

- Implemented capability-authenticated Windows loopback, ACL-private endpoint/cookies, native packaged UI/core launch and graceful shutdown. Linux IPC/media/model checks pass, including pause/reopen/resume through the new transport.
- Added pinned JavaFX Windows checksums, pinned media tool downloads, jpackage image/installer workflow, installed UI/core smoke checks and optional trusted SSL.com launcher/installer signing (disabled without enrolled secrets).
- Prepared a clean source-only checkout; created private arigavincent/AJDM repository and pushed it. Dispatched Windows preview with trusted_signing=false; artifacts will be explicitly unsigned. No certificate purchase or identity verification was attempted.

- Windows run 38384798090: model/net pass; 4/5 loopback tests pass (resume byte equality included); cleanup fails on null Windows fileKey. Fixed descriptor identity cleanup and ACL owner checks in 2d8c8c9. Local targeted suite/UI compilation passes. Additional launcher argument fix 9ba4f3a pushed; latest Windows run 38385610802.

- Run 38385901266 builds the actual Windows EXE and self-contained image. Startup smoke sees successful core ping but needs to observe the jpackage child window; fixed in 460102b and dispatched 38386426914. Local targeted regression totals 138 passing tests (33 model,64 media,41 IPC). Full Linux hosted check 38386061709 runs with FFmpeg installed and serialized project tests.

- Windows preview run 38386426914 completed successfully at commit 460102b: 59 native Windows checks pass; image and actual EXE installation both pass JavaFX window, core ping, CLI and graceful shutdown checks. Artifact AJDM-v1-preview-windows-x64 is 195586789 bytes, id 11806916370. Publicly trusted signing intentionally not performed because enrollment is absent.
- Standard gh artifact fetch timed out; using an authenticated API redirect and resumable storage transfer without forwarding GitHub credentials to artifact storage. Full Linux rerun 38387903810 uses no configuration cache and exactly one worker; prior overlap caused one remaining adaptive timeout.

- 16 October handoff: Windows EXE is present under dist/windows-v1 and sha256sum -c passes. Latest full backend run 38388849762 has 544 tests, one architecture failure: UI PackagedLauncher.serve constructs CoreService. Windows/macOS portable checks pass. Downloaded latest XML under artifacts/windows-v1/latest-linux-ci. Temporary source checkout no longer exists; AJDM workspace still has no .git. Wrote CLAUDE_HANDOFF.md at user's request; stopped implementation for transfer.
