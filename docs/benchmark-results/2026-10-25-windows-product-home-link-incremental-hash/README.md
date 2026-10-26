# Home link after incremental verification (25 October 2026)

This repeats the `home-link-256m`, product-defaults comparison from the [earlier run](../2026-10-25-windows-product-defaults-idm/README.md) after commit `3a6511f`, which hashes the saved prefix during the download. The setup was unchanged:

- **Source:** 256 MiB over a shared 8 MiB/s link (about 67 Mbit/s); the ideal time is 32 s.
- **AJDM:** product defaults, 16 connections, fixed mode.
- **IDM:** 6.43 build 16 trial at its default of 8 connections.
- **Method:** 10 alternating pairs with warm caches, SHA-256-verified completion for both tools, on the same Windows 11 i5-8265U laptop. The operator ran it from their own PowerShell.

## Result (`run/`, AJDM `9f4d58f`)

AJDM won all 10 pairs. All 20 downloads were verified, with no rejections.

| | Median s | p10 s | p90 s | Share of 32 s ideal |
|---|---:|---:|---:|---:|
| AJDM, product fixed | **33.48** | 33.09 | 34.56 | 96% |
| IDM, defaults | 39.06 | 38.38 | 40.74 | 82% |

- **IDM/AJDM time ratio:** 1.166. AJDM's median is 0.857 × IDM's, which meets the PRD's per-scenario G1 bar of ≤ 0.95. G1 as a whole still needs the B1–B9 set.
- **Before incremental verification:** AJDM's median was 36.82 s and IDM's 42.21 s, a ratio of 1.146. IDM was about 3 s faster this time as well, so compare the ratios rather than the absolute times across runs.
- **After the last byte**, AJDM now spends **0.82–1.17 s** (traced: the final flush plus hashing the unhashed tail). Before this change it spent 1.9–4.8 s. The last byte itself arrived at 32.25–33.93 s.

## Aborted run (`aborted-run-idm-stopped/`, AJDM `3a6511f`)

Before this run, an attempt was stopped by the operator after IDM silently stopped accepting downloads. IDM completed 6 trials, then trials #14 and #15 produced no file and each waited the old 30-minute timeout. Both are recorded in `rejections.csv`.

In the 6 complete pairs, AJDM (33.5–35.8 s) beat IDM (38.1–41.9 s). The run is kept as evidence but is excluded from the result because it is incomplete and unbalanced. The harness now gives a stuck tool at most 3 × its slowest verified time and stops after two consecutive rejections (`9f4d58f`).

## Scope

This is a local loopback fixture with an application-level shared cap and warm caches. Real CDN (B7), large-file (B1) and 4G/5G (B9) comparisons remain open.
