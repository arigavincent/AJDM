# Public download errors

`ErrorCode.publicCode()`, `message()` and `action()` expose catalog-owned text. `DownloadException.userMessage()` formats the public code, one plain sentence and a next action; it never includes the exception's own message or cause. The development runner uses that presentation for download exceptions. Internal enum names remain the SQLite persistence contract; no schema migration is needed for this addition.

HTTP 401/407 exceptions stored internally as `ACCESS_DENIED` are presented as AJDM-2010 when their HTTP status is available; other access failures use AJDM-2002. New HTTP 401/407 stops persist AUTH_REQUIRED with PAUSED state, so history retains AJDM-2010 after reopening; completion keeps ACCESS_DENIED plus HTTP status for compatibility. Legacy rows without HTTP status retain their old enum presentation. Credential prompting and production UI remain future integration work.

| Public code | Internal enum | Meaning / action |
|---|---|---|
| AJDM-1001 | UNSUPPORTED_SCHEME | Only HTTP/HTTPS supported; check link |
| AJDM-1002 | INVALID_URL | Invalid link; check link |
| AJDM-2001 | NOT_FOUND | File not found; verify link |
| AJDM-2002 | ACCESS_DENIED | Access denied; sign in/provide credentials |
| AJDM-2010 | AUTH_REQUIRED | Credentials needed; sign in/provide credentials |
| AJDM-2003 | INSUFFICIENT_DISK_SPACE | Insufficient free space; free space/choose folder |
| AJDM-2004 | DISK_WRITE_ERROR | Disk operation failed; choose folder/check disk |
| AJDM-3001 | SOURCE_CHANGED | Server file changed; restart/keep partial |
| AJDM-3002 | RETRIES_EXHAUSTED | Retry limit; check connection/retry |
| AJDM-3003 | RANGE_UNSUPPORTED | Resume unsupported; interruption restarts |
| AJDM-3004 | PROTOCOL_VIOLATION | Inconsistent server data; retry/report host |
| AJDM-3005 | CHECKSUM_MISMATCH | Verification failed; download again |
| AJDM-4001 | EXTRACTION_FAILED | Format extraction failed; update tools/retry |
| AJDM-4002 | REFRESH_FAILED / REFRESH_TIMEOUT / REFRESH_EXHAUSTED | Link renewal failed; retry from page link |
| AJDM-4002 | REFRESH_IDENTITY_UNVERIFIED / REFRESH_UNSUPPORTED | Cannot safely retain old bytes; keep partial/start new transfer |
| AJDM-4003 | MUX_FAILED | Combining streams failed; open log, keep streams |
| AJDM-4004 | DRM_PROTECTED | Protected media; unsupported |
| AJDM-5001 | IPC_UNAVAILABLE | Core unavailable; start AJDM |

Additional engine codes retain distinctions outside PRD Appendix B:

| Public code | Internal enum | Meaning / action |
|---|---|---|
| AJDM-1003 | INVALID_REQUEST | Invalid command/payload; check CLI help |
| AJDM-1004 | INVALID_STATE | Action unavailable in this state; check list |
| AJDM-5003 | FEATURE_UNAVAILABLE | Feature absent from this build; check supported commands |
| AJDM-2005 | PART_FILE_MISSING | Partial missing; start new download |
| AJDM-2006 | PART_FILE_TRUNCATED | Saved bytes lost; start new download |
| AJDM-2007 | TARGET_EXISTS | Destination/owned-stage collision; caller chooses Rename/explicit Overwrite or another filename |
| AJDM-2011 | TLS_VERIFICATION_FAILED | Certificate/path/peer verification failed; verify link/contact site administrator; no TLS bypass |
| AJDM-6001 | STATE_STORE_ERROR | State unavailable; check state folder/retry |
| AJDM-6002 | STATE_IN_USE | State owned by another instance; use/close that instance |
| AJDM-9001 | PAUSED | Paused control outcome; resume when ready |
| AJDM-9002 | CANCELED | Canceled control outcome; start new transfer if needed |

Defining catalog entries does not implement media/IPC adapters, authentication prompts or the complete validation flow. Core filename/conflict policies are now implemented; Ask reports AJDM-2007 for a caller decision rather than showing a GUI prompt (see [policy contract](FILENAMES_AND_CONFLICTS.md)). Invalid development-runner arguments currently retain its generic usage error and exit status 2. Transfer failure exits remain 1; production CLI exit mapping is outstanding. Explicit pause/cancel are control outcomes. Automatic source-change, disk and credential pauses retain their specific reason and action; see [retry/pause contract](RETRY_AND_PAUSE.md).

## Disk-space admission

Before starting payload transfer, including resume, AJDM reads `FileStore.getUsableSpace()` for the destination directory. A known source requires its full size plus 64 MiB free, following PRD ENG-18 literally. Comparisons use subtraction to avoid overflowing for a near-`Long.MAX_VALUE` source size. Even an empty file requires the reserve. An unknown length requires at least 64 MiB, with no guarantee of capacity for the eventual complete file.

The check precedes coverage restoration/truncation. Rejection retains the saved partial and ledger, persists `INSUFFICIENT_DISK_SPACE`, and requests no payload body beyond the metadata range probe. A later resume can retry after freeing space. Validated refreshed sources are checked before committing the replacement URL or requesting replacement payload. A verified ready-to-publish file bypasses admission because it downloads no further bytes.

This is a capacity snapshot, not a reservation. Other transfers/applications and unknown-length bodies can exhaust space afterward. Capacity-read errors fail closed as disk-operation failures; a real full-volume/mid-transfer ENOSPC test remains outstanding. Core disk errors now pause with a persisted reason; the product UI remains pending. Known-size logical preallocation follows admission; it may be sparse and does not reserve physical blocks. Explicit Overwrite supports journaled atomic publication when links fail, while portable no-clobber support remains open. See [file layout](FILE_LAYOUT_AND_PUBLICATION.md). Tests inject the narrow `DiskSpaceProbe` interface for deterministic capacity/failure cases; ordinary engine constructors always use the destination FileStore.
