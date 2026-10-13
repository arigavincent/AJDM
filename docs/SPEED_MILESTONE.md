# Adaptive speed milestone

This milestone improves AJDM's own transfer policy. The extractor remains metadata-only, and checkpoints still force acknowledged writes before committing completed ranges. It does not establish a throughput advantage over IDM or physical cellular-link saturation.

## Policy and ownership

- New adaptive requests carry distinct initial and maximum counts: initial four by default, maximum selected through the existing connection control, hard maximum 32. A caller can use `withInitialConnections(1..8)` independently of `withAdaptiveParallelism(maximum, minimumBytes)`.
- The initial count is clamped to the actual maximum, remaining work and shared response limits. New shared limits are 16 per host and 64 globally; new default minimum segments are 512 KiB. Fixed remains the product default and comparison control.
- Two-second baseline measurements precede controlled growth. Slow-start trials double admissions, settle for one second, and require a 15% useful-throughput gain over two measured seconds. Inconclusive non-plateau doubling can switch to one-at-a-time growth, requiring 5%. Harmful trials roll back; strong regressions lower admissions by one. Plateau detection requires an actual growth measurement; uneven initial TLS/connection startup cannot classify the link as saturated. Cooldown prevents repeated blind increases. Intentional pool recycling is excluded from server-reset backoff.
- HTTP 429/503 and three socket failures (including wrapped causes) within ten seconds trigger multiplicative admission decrease and a ten-second cooldown. Existing retry classification and Retry-After remain authoritative.
- Adaptive windows target approximately two seconds of measured lane throughput, with a 1 MiB floor and the caller's configured cap. Smaller explicit caps and final tails are respected. Active windows and queued writes remain protected from stealing.
- A busy lane below 35% of the busy-lane median for three seconds can have its upper unrequested tail queued for reassignment. Below 10% for five seconds, its independent pool can be recycled. Both actions are limited to once per twenty seconds per lane. Recycling is capped at eight per generation; it never cancels another lane.
- After an aborted response, the owner settles acknowledged writes and resumes from its accepted position. Ambiguous fully received windows retain the existing conservative rewind/validation path. No overlapping writers or speculative duplicate transfers are introduced.

## Host learning and migration

Schema v9 adds a persisted initial count and a bounded host-hint table. Existing downloads migrate with their previous initial count of two; their connection maximum, mode, window, coverage and destination are preserved. New jobs use four. The previous engine cannot open a v9 database: retain a backup before switching application versions, and upgrade core and client distributions together.

Hints are keyed by a hash of origin and current local interface addresses. They contain only connections, useful throughput and observation time. URLs, IP addresses and credentials are not stored in hints. Entries expire after thirty days, future timestamps are ignored, and the table retains at most 512 hints. Address changes produce a different key. Capacity changes that keep the same addresses are checked against a fresh baseline; material differences reset startup to four and re-enter measurement. This is conservative revalidation, not complete cellular-network identification.

Hints are saved only after a successful ranged generation with at least eight seconds and 1 MiB of useful progress. Only settled observations with sufficient active lanes establish an estimate; short completions, startup and final-tail samples do not train it. Hint persistence errors cannot fail a transfer. Range support and entity validators are always freshly probed; learning never substitutes for resume identity checks.

For cold comparisons, `-Dajdm.hostLearning.disabled=true` bypasses lookups and training. Controller decisions expose hint use/revalidation, laggard splits and pool recycling without source URLs or headers.

## Transport rationale

JDK HttpClient manages its own pools; a shared client does not promise worker-to-socket affinity. Adaptive lanes therefore create independent, persistent clients lazily. Recycling aborts one pool, and the owning worker later closes retired resources. This requests a fresh flow; no promise is made of a different CDN edge or path. See [official JDK 25 HttpClient documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html).

## Usage

Use the matching new core/client distributions with Java 25. The existing desktop **Adaptive connections** checkbox applies the policy to newly admitted files and single/paired media; its connection value is the maximum. Existing fixed jobs retain fixed mode.

For example, a new adaptive direct transfer through the CLI can use `add URL --directory PATH --connections 16 --adaptive`. Consult `add --help` for filename, conflict and checksum options. No running user job is automatically changed or resumed by this milestone.

## Evidence and limitations

The local comparison alternates all six orders of baseline fixed, baseline adaptive and candidate adaptive across eight HTTP fixtures. Each output is independently SHA-256 verified after timing ends. Host learning is disabled for those cold comparisons. Detailed results and reproduction instructions live in the milestone artifact report.

These fixtures use application-level pacing, delayed responses and interrupted HTTP bodies on loopback. They do not model physical TCP loss, real radio conditions, TLS handshake overhead, authenticated media extraction, muxing or IDM. Native transport qualification, extended crash qualification of the final source, real 4G/5G comparisons and Windows IDM acceptance remain separate gates.

## Preview and measured results

The [milestone report](../artifacts/speed-milestone-20261013/REPORT.md) contains the original 144-run local matrix, final-build calibration, migration evidence, and both bounded Wi-Fi batches. It records regressions as well as gains. Final adaptive Wi-Fi median was 0.373 MiB/s versus 0.438 MiB/s for fixed eight; no Wi-Fi or IDM superiority is established.

Launch the separate profile with `artifacts/speed-milestone-20261013/launch-preview.sh`. It uses matching packaged core/UI binaries and a private preview database; closing the window saves active work and stops that preview core. Source backups and a change manifest accompany the report.

## Automated qualification

The final `./gradlew --offline --no-daemon --console=plain --no-parallel check` passes: 539 tests, zero failures/errors/skips. The existing 80% package line gate passes at 94.43% for engine and 97.18% for store. This includes the default crash/resume matrices, not extended PRD-scale stress. New checks cover startup burst misclassification, throughput rollback, hint expiry/size/reopening, schema v8 migration and protected laggard splitting. Old migration fixtures explicitly remove the new column and assert preserved legacy options. The packaged core also passed an isolated ping/list/graceful-shutdown smoke check; interactive desktop acceptance is for the preview session.
