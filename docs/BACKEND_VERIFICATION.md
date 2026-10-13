# Backend verification pack

This pack qualifies the implemented AJDM backend on disposable local data. It preserves the user's database, downloads and running daemon. The [PRD audit](PRD_ALIGNMENT.md) remains the authority for missing features and external release acceptance; passing this pack does not establish a speed advantage over IDM.

## Skills and provenance

The inspected workflows used are [Test Master](https://github.com/Jeffallan/claude-skills/tree/1be15d8064f88fc25216442406d40add8fd23b53/skills/test-master), pinned to `1be15d8064f88fc25216442406d40add8fd23b53`, and [Trail of Bits property-based testing](https://github.com/trailofbits/skills/tree/82fe8226252622fa807643bdca1710901198553a/plugins/property-based-testing/skills/property-based-testing), pinned to `82fe8226252622fa807643bdca1710901198553a`. Both are installed under `~/.codex/skills` and become available in subsequent Codex sessions; their inspected guidance was applied here alongside the existing engineering/security skills. They guide testing rather than replace AJDM's Java code. Seeded JUnit generators use independent oracles; no additional property-testing framework was introduced.

## Repeatable checks

Set `JAVA_HOME` to a Java 25 JDK. On this workstation:

```bash
cd /home/ariga/Documents/AJDM
export JAVA_HOME=/usr/lib/jvm/jdk-25.0.1-oracle-x64
export GRADLE_USER_HOME=/home/ariga/.gradle
./gradlew --no-daemon --console=plain check backendCoverageGate
python3 -m unittest discover -s tools/tests -v
python3 tools/summarize_backend_checks.py
```

Use `--offline` once verified dependencies have been cached. The wrapper pins Gradle 9.3.1. Locked dependencies and SHA-256 verification stay enabled in ordinary runs. When intentionally changing dependencies, regenerate metadata/locks separately, review those changes, then run checks without write flags.

The extended campaign is:

```bash
./gradlew --no-daemon --console=plain check backendCoverageGate \
  -PcrashRuns=200 -PparallelCrashRuns=50 -PadaptiveCrashRuns=50 \
  -PrefreshCrashRuns=20 -PchaosRuns=1000 -PschedulerRuns=10000
python3 -m unittest discover -s tools/tests -v
python3 tools/summarize_backend_checks.py
```

The default suite uses 10 random single-worker kills, 10 fixed and 10 adaptive kills, three trials per refresh boundary, 25 HTTP fault cases and 100 trials per scheduler matrix. Extended counts are deliberate Gradle inputs, not additional JUnit method counts. A green up-to-date task reuses verified results for unchanged inputs; use `--rerun-tasks` to execute every case afresh when a new environment requires it.

## Coverage and architecture

JaCoCo 0.8.15 instruments test JVMs. `backendCoverage` aggregates all project test execution data over the main bytecode of model, HTTP, store, engine, media, core, IPC and CLI. UI and benchmark code are outside this backend denominator, while their integration tests contribute exercised backend paths. HTML/XML/CSV reports live under `build/reports/jacoco/backendCoverage/`. The `backendCoverageGate` requires at least **80% line coverage separately for engine and store**, matching the PRD. Coverage is a floor, not a correctness proof. Forcibly killed child JVMs are not instrumented; their recovery assertions are separate evidence.

Five ArchUnit 1.5.0 cases enforce the dependency direction, keep JavaFX out of the backend, keep the desktop from owning the engine/store or constructing CoreService, and prohibit payload HTTP/engine classes from launching external downloaders. CLI daemon startup is allowed to construct the sole core; that is its existing ownership boundary. Test code is excluded from imported architecture classes. Existing extractor subprocess tests require simulation, isolated configuration and literal URL arguments; ffmpeg tests require local copy mux. AJDM owns all payload HTTP.

## Fault and concurrency checks

The HTTP campaign randomizes file length, buffer capacity, in-flight queue size, request window, connection count and fixed/adaptive mode across ten recoverable server behaviors: normal, HEAD rejection, absent ranges, unknown length, absent validator, one truncated body, one stall, one throttle response, ranges ignored midway and one truncated non-range body. Every successful case checks output bytes, SHA-256, persisted terminal state, durable byte count and partial-file cleanup. Existing negative tests reject corrupt bodies, inconsistent ranges/totals, persistent truncation/stalls/throttling and changed identity without publishing an invalid file. These are loopback HTTP fault simulations, not Linux `tc netem` or cellular-network measurements.

Two scheduler matrices check exact-once coverage under concurrent virtual-thread lease acknowledgements and window/tail-steal handoffs. The extended setting executes **10,000 complete trials in each matrix**, not a relabeled count of assertions. Each trial has its own worker seeds. During handoffs, sampled atomic snapshots check that completed and unfinished intervals partition the entire representation without overlap or gaps; each write checks the current lease end, acknowledged position progression and non-increasing lease end. Boundary cases reject stale/wrong-owner acknowledgements and stealing protected request windows or queued writes. Timeouts and cancellation cleanup keep failed campaigns bounded. The range mutation oracle independently compares each add/remove operation, normalized ranges, complements, counts and prefix against a bitmap across 50,000 operations, plus `Long.MAX_VALUE` edges.

Kill fixtures forcibly terminate separate Java processes at random progress offsets and committed durability/publication/source-renewal boundaries. Recovery verifies SHA-256, persisted coverage and resumed request offsets rather than assuming that preallocated file length represents completed data. Forced process termination does not model loss of OS caches or physical power failure.

## Regressions closed

A delayed-executor fixture reproduced playlist metadata calls starting 0 ms apart despite two-second submission reservations. Workers now enforce monotonic spacing immediately before invoking extraction; the durable wall-clock reservation remains, and reopened runs wait conservatively to account for a late pre-crash dispatch. Long extractions still overlap within the three-task bound. Pause/cancel/reopen and the real two-second 100-item fixture remain regression checks.

Both single and paired media plans accepted explicit filenames that could escape the chosen directory. CoreService now rejects unsafe filenames before metadata extraction or persistence. The regression exercises parent/nested/backslash/absolute paths, control characters and Windows reserved names for both plans, requiring no metadata calls, no jobs and no destination artifacts.

## Evidence and CI

[Local evidence](../artifacts/backend-verification/report.md) records exact outcomes, durations, coverage and known limits. `summarize_backend_checks.py` rejects missing modules, contradictory XML counts, failures, errors, skipped cases and coverage below the PRD floor. It copies JUnit and coverage XML and hashes source/config files into `artifacts/backend-verification`. Seven Python contracts cover false-positive reporting paths; existing external-benchmark/matrix audits run alongside them.

The [GitHub workflow](../.github/workflows/backend-checks.yml) has a Linux full regression/coverage job, Windows/macOS portable domain/HTTP checks plus backend compilation, and an explicitly triggered extended Linux campaign. Actions are pinned to inspected release commits. This workspace has no Git repository or remote CI run, so the workflow is prepared, not remotely proven. Portable subset checks are not complete Windows/macOS IPC/filesystem/install acceptance. The ordinary Linux job has a 15-minute budget; the extended campaign has a 45-minute budget and does not stand in for routine CI latency acceptance.

## Remaining backend/product requirements

Persistent general queues and priority/reorder controls; scheduled start/stop and post-download actions; global/per-download speed limiting and data saver; proxy/auth settings; persistent host learning and the remaining PRD adaptive-controller defaults/policies; fragmented media fetching; and bundled tool update/hash/rollback lifecycle remain implementation gaps. The current three-transfer admission guard and bounded playlist runner are not a persistent product queue. Existing media support remains direct HTTPS streams with optional local lossless mux.

An isolated packaged core with empty state was sampled for12seconds: peak RSS84.61MiB, readiness including a separate `list` client0.80s, and socket removal after SIGTERM. File caches were warm and noUI/downloads were active; this is a short backend baseline, not UI cold-start or leak acceptance. See [sample evidence](../artifacts/backend-verification/isolated-core-idle.json).

Remaining qualification includes kernel-level network faults, a 24-hour resource soak, physical power loss/full-volume trials, native three-OS publication/IPC/installers, live formats/site/playlist corpus, a large real mux and matched Windows IDM trials on the required networks. Keep those gates open until their own evidence exists.
