# Native Windows AJDM vs IDM, re-run after fixes (25 October 2026)

This repeats the [first comparison](../2026-10-25-windows-local-fixed-idm/README.md) after two changes:

- **`79dca5f`:** the engine warms the HTTP stack at construction, not inside the first download.
- **`1bb97b0`:** the fixture now delivers its stated per-response cap on Windows. The fixture change applies to both tools.

The host, fixture profile, connection counts (4 each), IDM version (6.43 build 16 trial) and harness ([`tools/Compare-Idm.ps1`](../../../tools/Compare-Idm.ps1)) are the same as in the first run. The AJDM build is a clean `1bb97b0`. The operator ran the harness from their own PowerShell.

**Result: a tie.** AJDM's median was 4.917 s and IDM's 4.891 s, an IDM/AJDM time ratio of 0.995. Each tool won 5 of the 10 pairs. All 20 downloads were SHA-256-verified, with no rejections.

| | n | Median s | p10 s | p90 s | Fastest s | Slowest s |
|---|---:|---:|---:|---:|---:|---:|
| AJDM `fixed` (4 workers) | 10 | 4.917 | 4.815 | 4.949 | 4.762 | 4.964 |
| IDM (4 connections) | 10 | 4.891 | 4.516 | 5.865 | 4.396 | 11.660 |

- **IDM had the faster best case.** It finished in as little as 4.40 s, against AJDM's best of 4.76 s.
- **AJDM was far more consistent.** All its trials fell within 0.20 s of each other. IDM's spread was 0.83 s, excluding its first trial.
- **IDM's first trial took 11.66 s.** It is kept, as the protocol requires. The median is barely affected by it: without that trial IDM's median would be 4.877 s.

This does **not** meet the PRD G1 bar (AJDM median ≤ 0.95 × IDM). On this scenario, that would require a median of about 4.65 s.

## Where AJDM's time goes now

The table below summarises `ajdm-telemetry/*.telemetry.csv`, comparing the first run with this one.

| Phase | First run | This run | Ideal |
|---|---|---|---|
| First payload byte | 0.93–1.24 s | 0.39–0.62 s | about 0.1 s |
| Transfer rate | 1.65–1.69 MiB/s | 1.90–1.94 MiB/s | 2.0 MiB/s (4 × 512 KiB/s) |
| Total | 5.80–6.08 s | 4.76–4.96 s | about 4.3 s |

The first-run figures were taken on the inaccurate fixture, so they are not directly comparable with this run. The remaining time to recover is about 0.3–0.5 s before the first byte, plus about 4% in the transfer and its tail.

## Scope

This is still one small synthetic loopback case: B2-like, 8 MiB, warm caches, 4 connections each. It says nothing yet about product defaults (16 connections, adaptive windows), large files, real RTT or loss, or the PRD B1–B9 set.
