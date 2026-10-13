# Durable playlists through AJDM

PRD MED-06 and architecture §9.3 now have a CLI/core foundation: flat inventory, all/range/per-entry choices, bounded lazy extraction and durable item orchestration. yt-dlp supplies metadata only. Each selected item uses the existing AJDM transfer engine, or its [paired-stream job](MEDIA_JOBS.md) when local copy mux is needed. Live-site acceptance and the JavaFX playlist picker remain open.

## List, preview and download

Start the daemon with explicitly trusted tools:

```bash
ajdm-cli/build/install/ajdm/bin/ajdm daemon --yt-dlp /path/to/yt-dlp --ffmpeg /path/to/ffmpeg
```

In another terminal:

```bash
ajdm-cli/build/install/ajdm/bin/ajdm playlist 'https://example.com/playlist' --limit 100
ajdm-cli/build/install/ajdm/bin/ajdm formats 'https://example.com/playlist' --playlist-item 5 --show-all --preset P1080
ajdm-cli/build/install/ajdm/bin/ajdm add-playlist 'https://example.com/playlist' --items '1-25,30' --preset P1080 --choice '5=format:VIDEO_ID+AUDIO_ID' --choice '30=AUDIO' --directory /tmp/videos --wait
ajdm-cli/build/install/ajdm/bin/ajdm playlist-status JOB_ID --json
ajdm-cli/build/install/ajdm/bin/ajdm pause JOB_ID
ajdm-cli/build/install/ajdm/bin/ajdm resume JOB_ID
```

`playlist` performs one flat extraction without full per-entry extraction or payload requests. Positions are one-based and follow inventory order, including duplicate videos and unavailable/null entries. Lists are bounded to 1,000 entries and reject oversize inventories explicitly. Listing/status responses default to 100 entries per page; use `--offset 100 --limit 100` for the next page. Offsets are zero-based, bounded to 0–1,000, and limits to 1–100. Pagination limits the response, not the flat extractor's work.

`formats --playlist-item INDEX` lists the inventory and fully extracts that one entry. The existing format filters and custom/preset preview options apply. It creates no download and exposes no source URLs or headers. An unavailable entry returns its catalog-owned error immediately.

`add-playlist` accepts `--items all` (default) or comma-separated positions/inclusive ranges. Overlapping selections are deduplicated and processed in inventory order. A shared `--preset` defaults to `BEST`; supported values are `BEST`, `P1080`, `P720` and `AUDIO`. Repeat `--choice INDEX=PRESET` or `INDEX=format:VIDEO_ID[+AUDIO_ID]` to override a selected entry. Format IDs are exact identifiers, never yt-dlp selector expressions. Unselected positions, duplicate overrides and invalid choices fail before creating a job. `--compatible-codecs`, `--connections 1..32` and `--conflict RENAME|ASK|OVERWRITE` use the existing media/engine policy.

The admitted playlist UUID is printed first. Without `--wait`, exit 0 means admission. With `--wait`, the client polls status; all settled items yield exit 0 unless any item FAILED, which yields exit 6. SKIPPED entries are reported with reasons and do not abort others. A paused/canceled group returns the existing safe CLI error/exit. Closing the client leaves the daemon working.

## Scheduling and item outcomes

One playlist runs at a time; another active playlist request is rejected without creating a record. Paused groups can coexist and be resumed explicitly. Full extraction is lazy, with at most three outstanding metadata tasks/results and a persisted two-second interval between item extraction submissions. A restart retains the next allowed timestamp. Network/extractor timing can delay starts. Existing per-transfer expired-source renewal has its own bounded policy; it is not an additional playlist discovery task.

Items wait for the shared core's three-transfer admission limit. A paired item consumes two slots; ordinary jobs and other media jobs share the same guard. Metadata results waiting for a slot are retained in bounded memory instead of repeatedly extracted. This provides playlist item scheduling; general persistent priorities, scheduled windows and start-later downloads remain separate work.

Outputs use a sanitized `0005-Title.container` name in the chosen directory. The original inventory position prevents duplicate titles from silently sharing a target. Existing collision, staging, checkpoint, source-identity and publication rules apply.

Item states are `QUEUED`, `EXTRACTING`, `DOWNLOADING`, `PAUSED`, `COMPLETED`, `SKIPPED`, `FAILED` and `CANCELED`. Explicit private/auth-required, DRM, unsafe-page and nested-playlist entries retain safe reasons; unknown availability is resolved by full extraction. Extraction or unsupported-format failures mark that item SKIPPED. Payload failures mark it FAILED while other entries proceed. Disk admission, target collisions and a paused child pause the group for a caller decision. A group becomes COMPLETED when all items have terminal outcomes; inspect the item counts to distinguish successes, skips and failures.

## Durable control and recovery

SQLite schema v8 adds `playlist_job` and `playlist_item`, preserving existing downloads, media origins and paired parents. The parent and every selected item's immutable choice commit in one transaction. Before child admission, the item commits a planned child UUID. On reopening, an existing ordinary child or paired parent with that UUID is reused; a committed intent without a child can safely be admitted once. Errors after child creation preserve that child for recovery rather than classifying it as skipped.

`pause JOB_ID` stops further admissions, interrupts metadata tasks and durably pauses unfinished children. `cancel JOB_ID` cancels unfinished work and retains completed outputs/terminal history and existing partials. Metadata cleanup is bounded by the extraction adapter; late results cannot launch payloads after the runner stops. Daemon shutdown pauses active work. Reopening presents interrupted groups as PAUSED and starts nothing automatically. `resume JOB_ID` reuses completed entries and saved unfinished children, extracting only entries not yet admitted. Headers are obtained from the media extraction/renewal adapter; group resume does not accept caller headers.

`resume JOB_ID --conflict RENAME|OVERWRITE` can resolve a saved child's Ask collision using the existing media/engine behavior. Previously FAILED/SKIPPED/CANCELED entries remain terminal; group resume does not silently retry them. Child UUIDs are available in JSON status for diagnostics and individual controls. File/media children support `resume CHILD_ID --connections 8` after pausing; a playlist parent does not accept a connection override. Source-change restart creates a separate download under the existing child API; adopting that new UUID into the playlist and editing/retrying individual choices are future work. A completed/canceled group cannot resume.

General `list` includes the playlist parent and admitted public child jobs; private paired-stream children stay hidden. Parent byte totals are unknown; use `playlist-status` for item counts and the child rows for byte progress. Status snapshots can race with completion. After an interrupted daemon, nonterminal entry rows are displayed PAUSED; aggregate counters describe stored item states until resume/control reconciles them.

## Private IPC and validation

Protocol v1 adds:

| Type | Payload | Result |
|---|---|---|
| `playlist` | `url`, optional `offset`, `limit` | id/title, totalEntries, offset, entries(index/id/title/duration/error) |
| `playlistFormats` | Existing `formats` fields plus `index` | Existing safe format table/selection preview |
| `addPlaylist` | `url`, optional `items`, `preset`, `choices`, `directory`, `conflict`, `connections`, `preferModernCodecs` | downloadId |
| `playlistStatus` | `downloadId`, optional `offset`, `limit` | group state, selected count, item-state counts, safe reasons and paged entries/child IDs |

Each `choices` element has `index` and either `preset` or `formatId` with optional `audioId`. Bound SQL, strict JSON shape/types and range validation apply. Listing/status omit page/signed URLs and headers; errors use the public catalog. Human output escapes terminal controls. Recovery URLs remain in private SQLite state. Existing 1 MiB frame, peer and POSIX endpoint permission limits apply. Listing/add responses allow 75 seconds; entry previews allow 150 seconds for two sequential extractions. Losing an admission reply does not authorize automatic mutation replay.

Flat extraction retains the existing `--simulate --skip-download --no-check-formats`, config/plugin/cache isolation, bounded pipes and timeout, adding `--flat-playlist --yes-playlist --ignore-errors`. Full per-entry extraction retains `--no-playlist`. Arguments go directly to the executable without a shell, with the page URL after `--`. See [extraction security](MEDIA_EXTRACTION.md).

## Evidence and remaining acceptance

[Playlist evidence](../artifacts/prd-alpha-playlists/report.md) records the full regression and XML-derived counts. Tests cover unavailable positions/reasons, custom paired choices, safe preview/pagination, failed-item continuation, cancellation during extraction, pause/reopen reuse, post-admission error recovery and four independent JVM kills at parent creation, child intent, child admission and item completion. The 100-item local fixture runs the real two-second interval, enforces a rate gate and verifies every output byte; the overlapping extractor fixture verifies a maximum of three concurrent tasks.

This local fixture does not establish a live 100-item YouTube/site rate-limit pass. Live multi-site formats, 4K integrity, large mux timing, bundled tools/update lifecycle, JavaFX, general queues and native OS qualification remain open in the [PRD audit](PRD_ALIGNMENT.md). No throughput advantage over IDM on actual 4G/5G has been measured.

Metadata start protection uses both persisted wall-clock reservations and a monotonic gate at worker entry. Delayed executor dispatch cannot bunch starts; reopened runs conservatively allow two seconds before their first metadata start. Long extractions can still overlap within the three-task limit. See the [backend verification guide](BACKEND_VERIFICATION.md).
