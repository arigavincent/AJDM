# Native Windows: AJDM product defaults vs IDM defaults (25 October 2026)

This page compares each tool as a user gets it out of the box:

- **AJDM:** 16 connections, the options the core gives a new direct download. Adaptive is off by default; it was measured as a separate group.
- **IDM:** 6.43 build 16 trial with its default of 8 connections.

The runs used [`tools/Compare-Idm.ps1`](../../../tools/Compare-Idm.ps1) `-Settings product`, with the AJDM build at a clean `5f6a109`. Each group is 10 adjacent pairs with alternating order, warm caches and SHA-256-verified completion for both tools. There were no rejections in any group. The host was the same Windows 11 i5-8265U laptop as the [earlier runs](../2026-10-25-windows-local-fixed-idm-rerun/README.md), and the operator ran the harness from their own PowerShell.

| Scenario | AJDM mode | AJDM median s (p10–p90) | IDM median s (p10–p90) | IDM/AJDM time | Pairs won |
|---|---|---|---|---|---|
| `capped-host-64m` | fixed (product default) | 9.61 (9.43–9.94) | 19.91 (19.28–20.05) | 2.07× | AJDM 10/10 |
| `capped-host-64m` | adaptive | 9.82 (9.55–10.91) | 19.78 (19.47–20.47) | 2.02× | AJDM 10/10 |
| `home-link-256m` | fixed (product default) | **36.82** (34.82–37.68) | 42.21 (40.11–42.65) | **1.15×** | AJDM 10/10 |
| `home-link-256m` | adaptive | 37.60 (36.62–38.40) | 43.60 (41.17–44.65) | 1.16× | AJDM 10/10 |

## How to read this

- **`capped-host-64m`** is a 64 MiB file with each response capped at 512 KiB/s. AJDM's 2× lead **comes from its default of 16 connections against IDM's 8, by design**. An IDM user who raises its limit would close most of that gap. It is the B2-like case: it shows what the defaults deliver on per-connection-throttled hosts, not that the engine is more efficient. Both tools reached about 80–83% of their own connection-limited ideal (8 s and 16 s).
- **`home-link-256m`** is a 256 MiB file over a shared 8 MiB/s link (about 67 Mbit/s), where extra connections cannot add bandwidth. **This is the efficiency comparison.** The ideal is 32 s. AJDM fixed took 36.8 s (87% of ideal), while IDM took 42.2 s (76%). AJDM's median was 0.872 × IDM's, which **meets the PRD G1 per-scenario bar of ≤ 0.95** for this one scenario. G1 itself needs the B1–B9 set.
- **Adaptive was no better than fixed** in either scenario and had a wider spread. It is correctly off by default.

## Where AJDM's remaining time goes (`home-link-256m`, fixed)

From `ajdm-telemetry/*.telemetry.csv`:

| Phase | Measured | Ideal |
|---|---|---|
| First payload byte | 0.5–0.7 s | about 0.1 s |
| Steady rate (10–90% of bytes) | **8.00 MiB/s**, the full link | 8 MiB/s |
| Last byte received | 32.5–33.2 s | 32.0 s |
| **After the last byte, until COMPLETED** | **1.9–4.8 s** | about 0.1 s plus verification |

The transfer is saturating the link. Nearly all the loss is the finish. Two traced runs split it like this:

- **Final flush to disk:** 0.04–0.08 s. Periodic checkpoints have already forced the data.
- **SHA-256 re-read of the whole file:** 3.0–3.2 s. Reading is not the bottleneck: this i5-8265U reads the file at 1.2–1.6 GB/s but computes SHA-256 at only about 100 MB/s, because it has no SHA hardware instructions.

Windows' `Get-FileHash`, which the harness uses for IDM's verification, ran at 62–80 MB/s on the same file, or 3.4–4.4 s. Both tools therefore pay a similar verification cost. Excluding it, AJDM's transfer took about 33.5 s and IDM's about 38.4 s. The engine hashes only when an expected SHA-256 is supplied, so ordinary downloads without one skip this step.

The next optimisation target is hashing the contiguous saved prefix *during* the download. The link-bound transfer leaves the CPU mostly idle, so this would leave only the unhashed tail after the last byte. The saving grows with file size: at 100 MB/s a 2 GB file spends about 21 s hashing after the download today.

## Scope

These are local loopback fixtures with application-level caps. They are not real RTT, packet loss, radio links or CDNs, and the caches were warm. Real large-file CDN (B7) and 4G/5G (B9) comparisons remain open.
