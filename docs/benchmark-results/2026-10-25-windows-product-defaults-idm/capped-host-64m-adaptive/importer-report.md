# External AJDM/IDM comparisons

Only matched, verified, balanced comparisons with at least ten samples per tool are included.

| Host / network / source / cache / comparison | Mode | n | Median s | p10 s | p90 s | IDM/tool median time ratio |
|---|---|---:|---:|---:|---:|---:|
| desktop-0vfmb1i / loopback / fixture-capped-host-64m / product-adaptive-idm / warm | adaptive | 10 | 9.815 | 9.550 | 10.905 | 2.015x |
| desktop-0vfmb1i / loopback / fixture-capped-host-64m / product-adaptive-idm / warm | idm | 10 | 19.782 | 19.473 | 20.469 | 1.000x |

Ratios above 1 mean that tool had a lower median completion time than IDM. Missing resource metrics remain null in summary.json.
Conditions and hash verification are supplied by the operator; this importer cannot verify radio state, timers, cache flushing, or independence of runs.
Local synthetic importer tests are not measurements of IDM or a mobile network.
