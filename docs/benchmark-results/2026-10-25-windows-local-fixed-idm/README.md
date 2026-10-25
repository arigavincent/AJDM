# Native Windows AJDM vs IDM, local capped fixture (25 October 2026)

The first same-host comparison against **native** IDM on Windows, run with [`tools/Compare-Idm.ps1`](../../../tools/Compare-Idm.ps1) as described in [BENCHMARK_PROTOCOL.md](../../BENCHMARK_PROTOCOL.md).

**Result: IDM was faster.** Median 4.80 s for IDM against 5.87 s for AJDM. That is an IDM/AJDM time ratio of 0.817, so AJDM took about 22% longer. IDM won every one of the 10 pairs. All 20 downloads were SHA-256-verified, with no rejections.

| | n | Median s | p10 s | p90 s |
|---|---:|---:|---:|---:|
| AJDM `fixed` (4 workers) | 10 | 5.873 | 5.801 | 6.015 |
| IDM 6.43 build 16 trial (4 connections) | 10 | 4.799 | 4.502 | 5.081 |

## Conditions

- Host: Windows 11 Home 10.0.26200, i5-8265U, 7.8 GB RAM, NTFS on C:. See `context.json`.
- Source: the `per-response-cap` fixture, 8 MiB + 17 bytes, each response capped at 512 KiB/s, on loopback. A fresh fixture was started for every trial.
- Pairs: 10 adjacent pairs, alternating which tool went first. Warm caches; no cache reset was applied to either tool.
- AJDM: engine at commit `cb7e717`. The `-dirty` tag refers only to an uncommitted harness counting fix, not engine code. Benchmark `fixed` mode: 4 workers, 256 KiB request windows, a fresh engine per trial; JVM start is excluded from the timer.
- IDM: resident in the tray, maximum connections set to 4, driven by `IDMan.exe /d … /n`. Its time runs from hand-off to a complete file plus an external SHA-256.
- The run was started by the operator from their own PowerShell. From the agent's shell, IDM accepted the command and then ignored it.

## Where AJDM lost the time

Phases from `ajdm-telemetry/*.telemetry.csv`, consistent across all 10 trials:

| Phase | AJDM measured | Ideal at this cap |
|---|---|---|
| Task start to first payload byte | 0.93–1.24 s | about 0.1 s |
| Transfer rate while downloading | 1.65–1.69 MiB/s | 2.0 MiB/s (4 × 512 KiB/s) |
| Verify and publish | about 0.15 s | — |

If both gaps were closed, the projected time is about 4.4 s, below IDM's 4.8 s median. This is a projection, not a measurement.

- **Startup.** Each trial runs a fresh engine. The second before the first byte covers engine construction, class loading and JIT, SQLite setup and the probe. The product core is a resident daemon, but the first download after it starts pays the same cost.
- **Transfer.** The rate stays at 84% of the cap. With 256 KiB windows, each worker opens a new capped response about every half second; IDM's open-ended range per connection avoids that.

## Follow-up investigation

A finer trace changed the explanation of both gaps above:

- **The "84%" transfer rate was mostly the fixture.** It paced each 4 KiB step with a sleep, and `max(deadline, now)` discarded every oversleep. On Windows' coarse timer each response got about 0.43 MiB/s instead of 0.5. IDM was held to the same rate, which roughly matches its 4.8 s. The fixture now catches up lags of up to 50 ms but still restarts after a real reader stall. This results set was recorded **before** that fix, so its absolute times are not comparable with later runs.
- **The startup second was the JDK HTTP stack loading cold inside the first download.** Building the HTTP client took 0.66–0.9 s and the first request about 0.4 s. AJDM's own setup (database, partial file, preallocation) took about 0.1 s in total. The engine now warms that stack in the background when it is constructed. `benchmark-download` waits for that warm-up before starting its timer, as a resident core would have finished it long before a user's first download. All per-download work stays inside the timer.

After both fixes, the same AJDM trial took 4.85–5.03 s, with data flowing about 0.38 s after the task starts. IDM must be re-measured on the corrected fixture before any conclusion is drawn.

## How this changes earlier conclusions

The 16 October lab ([NETLAB.md](../../NETLAB.md)) had AJDM ahead of IDM. That IDM ran under Wine and waited about 8.7 s before its first request. Native IDM shows no such delay here, so that advantage should be treated as largely a Wine artifact until native comparisons say otherwise.

This is one small synthetic scenario (B2-like, 8 MiB). It does not cover the PRD B1–B9 set, real RTT or loss, or product-default settings (16 connections, adaptive windows). See [PRD_ALIGNMENT.md](../../PRD_ALIGNMENT.md) for the open acceptance gates.
