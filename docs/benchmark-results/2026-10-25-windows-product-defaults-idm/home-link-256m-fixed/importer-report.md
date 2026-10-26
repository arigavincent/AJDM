# External AJDM/IDM comparisons

Only matched, verified, balanced comparisons with at least ten samples per tool are included.

| Host / network / source / cache / comparison | Mode | n | Median s | p10 s | p90 s | IDM/tool median time ratio |
|---|---|---:|---:|---:|---:|---:|
| desktop-0vfmb1i / loopback / fixture-home-link-256m / product-fixed-idm / warm | fixed | 10 | 36.817 | 34.824 | 37.678 | 1.146x |
| desktop-0vfmb1i / loopback / fixture-home-link-256m / product-fixed-idm / warm | idm | 10 | 42.209 | 40.114 | 42.649 | 1.000x |

Ratios above 1 mean that tool had a lower median completion time than IDM. Missing resource metrics remain null in summary.json.
Conditions and hash verification are supplied by the operator; this importer cannot verify radio state, timers, cache flushing, or independence of runs.
Local synthetic importer tests are not measurements of IDM or a mobile network.
