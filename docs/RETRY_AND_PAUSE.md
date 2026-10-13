# Retry decisions, pauses and source-change recovery

This contract implements the engine portion of PRD ENG-07/09 and architecture §8.10. AJDM owns all retries, worker coordination, HTTP requests, writes and checkpoints. No extraction tool downloads payload. Product prompts and Waiting for network UI remain integration work.

## Decision table

| Condition | Engine action | End state / public reason |
|---|---|---|
| Connect/read timeout, reset, premature EOF, body stall, transient TLS negotiation | Bounded per-window/full-stream/probe retry with exponential full jitter; failed single streams rewind to zero | FAILED / AJDM-3002 after the configured ordinary retry budget |
| Certificate/path/peer verification failure | Fail immediately by cause type; never relax TLS verification | FAILED / AJDM-2011, verify link/contact site administrator |
| HTTP 200 ignoring a range with matching validator | Join old workers, retain only a valid contiguous prefix, switch to one full stream; it must confirm retained bytes | Complete, or PAUSED / AJDM-3001 if identity cannot be confirmed |
| Changed validator/size, including If-Range mismatch | Stop and join workers, force accepted old-version writes, checkpoint and retain old metadata/partial | PAUSED / AJDM-3001, caller chooses Restart or Keep partial |
| Invalid 206 Content-Range | Discard the entire owned window, including any earlier short-response bytes, and retry once; invalid metadata ranges also retry once | FAILED / AJDM-3004 if repeated, preserving other valid payload windows |
| Payload HTTP 416 | Join workers/checkpoint, re-probe once and compare size/validator before committing metadata | PAUSED / AJDM-3001 if changed/unproven; unchanged identity retries once; another 416 is FAILED / AJDM-3004 |
| HTTP 401/407 without media renewal | Stop once for a caller credential decision; no automatic credential guessing | PAUSED, persisted AUTH_REQUIRED / AJDM-2010; completion retains ACCESS_DENIED plus HTTP status for compatibility |
| File HTTP 403/410 | One fresh probe and another transfer generation, preserving bytes only under validated identity | Repeated denial FAILED / AJDM-2002; changed identity PAUSED / AJDM-3001 |
| Media HTTP 401/403/404/410 with a SourceRefresher | Existing bounded callback/cross-URL identity protocol, after old workers settle | Existing renewal failures / AJDM-4002, or source-change pause; no file re-probe substitute for extraction |
| File HTTP 404 | Fail immediately | FAILED / AJDM-2001 |
| HTTP 429/503 | Count adaptive throttle signal, honor Retry-After, apply bounded retry | FAILED / AJDM-3002 if exhausted |
| Other HTTP 5xx | Ordinary bounded retry; do not misclassify as a 429/503 throttle signal | FAILED / AJDM-3002 if exhausted |
| Disk write/force/publication error or insufficient-space admission | Pause, retain original error and last safe ledger; no silent retry | PAUSED / AJDM-2004 or AJDM-2003 |
| Explicit caller pause / cancel | Settle writers and save the control state | PAUSED with no error / CANCELED |

An ordinary retry budget of N permits N retries after the first request. The one malformed-range retry has a separate counter and works even when ordinary retries are disabled. The file-denial and 416 re-probe allowances are each bounded once per attempt, shared across its worker generations, rather than multiplied by worker count. Reopening/resuming creates another caller-authorized attempt with fresh budgets.

Retry-After accepts seconds and HTTP-date in RFC1123 form, ignores malformed/negative/past values and caps at one day. The wait is at least the header delay even when jitter is zero. Cancellation interrupts waits. The 30-second backoff cap does not override a longer valid Retry-After. Certificate failures remain fatal; uncertain/transient negotiations still use a bounded retry with normal JDK verification on every attempt. HTTP/range semantics follow [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.17); [SSLHandshakeException](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLHandshakeException.html) does not by itself identify a certificate failure.

## Source identity and checkpoints

Re-probes run on the coordinator after old network workers stop and accepted disk jobs drain. Before metadata changes or new payload requests, compare known total size and validator. Retained bytes require the same known size and usable validator. Redirecting to a different final URL uses the stricter existing strong ETag/range identity proof. Unchanged same-endpoint Last-Modified identity remains supported. Failed identity/capacity checks preserve the previous metadata and coverage.

A source-change stop commits forced coverage, PAUSED state and its error reason in one SQLite checkpoint transaction. A concurrent caller pause cannot erase that decision reason. Disk failures preserve the last safe ledger; failed writes/forces never gain durable coverage. The completion stage still ends exceptionally with the specific error; `handle.pause()` completes successfully once the paused attempt is durably stopped. Startup keeps saved paused reasons and does not issue network requests automatically.

## Explicit caller choices

Keep partial requires no mutation: retain the paused record and file. Ordinary `resume(id)` rechecks source identity and refuses a changed version again; it never silently splices or resets that record.

`restart(id)` or `restart(id, headers)` is available only for an inactive PAUSED/SOURCE_CHANGED download. It starts a new UUID and unique partial with the same URL, destination, options and conflict policy. The old record, source metadata, intervals and bytes remain intact. Same-engine credentials/refresh registration are reused; after reopening, supply needed credentials explicitly. They remain excluded from persistent sensitive headers. Previously supplied expected SHA-256 is retained; if the new version needs a different checksum or destination, construct a new TransferRequest explicitly rather than silently disabling verification.

Disk/authentication pauses can use ordinary resume after the caller fixes the condition; authentication accepts headers through the existing resume API. No engine thread displays a dialog or waits for human input. Product UI must offer the corresponding prompt/actions and credential retry limits. The development runner reports errors safely but still lacks the production CLI's complete command/exit mapping.

## Evidence and limits

Controlled HTTP fixtures cover initial and payload denial, same/changed 416, metadata/payload malformed ranges, combined malformed/network faults, temporary status budgets, real Retry-After waiting, retained good windows and fixed/adaptive re-probes. Process kills cover re-probe commits and the source-pause checkpoint boundary. A temporary self-signed HTTPS fixture verifies real certificate rejection without any HTTP payload. Cause tests cover transient negotiation versus certificate/path/peer failures and cyclic causes.

Real mobile disconnections/network switching, actual full-volume ENOSPC, native three-OS certificate/disk behavior, physical power loss, GUI credential/source-change prompts and release-scale chaos/soak acceptance remain separate gates. Existing correct byte recovery and synthetic fault evidence do not establish an IDM throughput advantage.
