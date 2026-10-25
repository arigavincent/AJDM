# External AJDM/IDM comparisons

Only matched, verified, balanced comparisons with at least ten samples per tool are included.

| Host / network / source / cache / comparison | Mode | n | Median s | p10 s | p90 s | IDM/tool median time ratio |
|---|---|---:|---:|---:|---:|---:|
| desktop-0vfmb1i / loopback / fixture-home-link-256m / product-adaptive-idm / warm | adaptive | 10 | 37.604 | 36.619 | 38.404 | 1.160x |
| desktop-0vfmb1i / loopback / fixture-home-link-256m / product-adaptive-idm / warm | idm | 10 | 43.602 | 41.170 | 44.649 | 1.000x |

Ratios above 1 mean that tool had a lower median completion time than IDM. Missing resource metrics remain null in summary.json.
Conditions and hash verification are supplied by the operator; this importer cannot verify radio state, timers, cache flushing, or independence of runs.
Local synthetic importer tests are not measurements of IDM or a mobile network.
