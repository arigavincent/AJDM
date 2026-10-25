# External AJDM/IDM comparisons

Only matched, verified, balanced comparisons with at least ten samples per tool are included.

| Host / network / source / cache / comparison | Mode | n | Median s | p10 s | p90 s | IDM/tool median time ratio |
|---|---|---:|---:|---:|---:|---:|
| desktop-0vfmb1i / loopback / fixture-per-response-cap / fixed-idm / warm | fixed | 10 | 4.917 | 4.815 | 4.949 | 0.995x |
| desktop-0vfmb1i / loopback / fixture-per-response-cap / fixed-idm / warm | idm | 10 | 4.891 | 4.516 | 5.865 | 1.000x |

Ratios above 1 mean that tool had a lower median completion time than IDM. Missing resource metrics remain null in summary.json.
Conditions and hash verification are supplied by the operator; this importer cannot verify radio state, timers, cache flushing, or independence of runs.
Local synthetic importer tests are not measurements of IDM or a mobile network.
