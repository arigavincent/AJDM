# Real CDN on home broadband: AJDM vs IDM (25–26 October 2026)

This is the first comparison over the real internet. It followed the protocol's B7 shape (a real CDN file), but the file is mid-sized rather than large.

## Setup

- **Source:** `linux-6.12.tar.xz` from `cdn.kernel.org` (Fastly; the response header named a Fujairah cache). The file is 147,906,904 bytes, its SHA-256 is `b1a2562b…54625eb` from kernel.org's published `sha256sums.asc`, and it supports byte ranges with a `Last-Modified` validator.
- **Network:** the operator's home broadband over Wi-Fi, about 1.1–1.7 MB/s (roughly 9–13 Mbit/s) during the run. There was no independent capacity measurement.
- **Tools:**
  - AJDM `d99199f` with product defaults (16 connections, fixed mode).
  - IDM 6.43 build 16 trial at its default of 8 connections.
- **Method:** 10 alternating pairs with SHA-256-verified completion for both tools. CDN caches were warm after the first fill. The operator ran [`tools/Compare-Idm.ps1`](../../../tools/Compare-Idm.ps1) from their own PowerShell, with AJDM's opt-in engine trace on (`ajdm-engine-trace.txt`, which records no URLs or headers).

## Result: a tie

| Pair | First | AJDM s | IDM s | AJDM/IDM | Faster |
|---:|---|---:|---:|---:|---|
| 1 | AJDM | 113.15 | 118.10 | 0.958 | AJDM |
| 2 | IDM | 118.10 | 111.74 | 1.057 | IDM |
| 3 | AJDM | 108.98 | 124.86 | 0.873 | AJDM |
| 4 | IDM | 113.90 | 110.75 | 1.028 | IDM |
| 5 | AJDM | 110.63 | 111.14 | 0.995 | AJDM |
| 6 | IDM | 100.05 | 98.18 | 1.019 | IDM |
| 7 | AJDM | 93.24 | 93.63 | 0.996 | AJDM |
| 8 | IDM | 89.40 | 89.21 | 1.002 | IDM |
| 9 | AJDM | 93.89 | 92.23 | 1.018 | IDM |
| 10 | IDM | 93.40 | — | — | IDM trial rejected |

- **Paired AJDM/IDM time ratio over the 9 complete pairs:** median 1.002, geometric mean 0.993. AJDM won 4 pairs and IDM won 5. There is no meaningful difference.
- **Order effect:** in 8 of 9 pairs **whichever tool went first was faster**. Both tools also sped up over the session, from about 113 s to about 90 s. Conditions changed between back-to-back downloads by more than the tools differ. The alternating order balances this out but does not remove it.
- **Rejected trial:** IDM trial #19 produced no file within 375 s. This is the same silent stall seen in earlier runs, and it is kept in `rejections.csv`.
- **Not an importer result:** with 10 AJDM samples and 9 IDM samples, `tools/summarize_external_benchmarks.py` declines to summarise this run. The table above is a manual paired analysis.

## What AJDM's trace shows

| Phase | All 10 AJDM runs |
|---|---|
| First request (connect, TLS, first response) | 0.6–2.8 s, and 7.9 s once |
| Retries | none in 9 runs, 2 in one |
| Slices finishing | within 1–3 s of each other |
| After the last byte (flush and SHA-256 tail) | 0.30–0.46 s |

The line was the bottleneck for both tools. With a single shared bottleneck at about 10 Mbit/s, AJDM's 16 connections add nothing over IDM's 8. By contrast, AJDM was 2.07× faster on a per-connection-capped host and 1.17× faster on a 67 Mbit/s shared link in the [local comparisons](../2026-10-25-windows-product-home-link-incremental-hash/README.md). The only AJDM-side cost visible here is the variable first request, which is mostly network and CDN latency that IDM also pays.

## Scope

This is one CDN, one home line, about 148 MB and one session. It is not the protocol's large-file (2 GB) case, it has no capacity denominator, and it does not cover real loss or RTT shaping or 4G/5G (B9).
