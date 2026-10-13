# AJDM core, CLI and local IPC

PRD §10/CLI-01 and architecture §12 now have a direct-file alpha implementation: `ajdm-core` owns one engine and SQLite store, `ajdm-ipc` carries commands over a private Unix socket, and `ajdm-cli` provides picocli clients. The benchmark runner remains development tooling. Clients never open a database, create transfer workers or download payloads.

Build with Java 25:

```bash
./gradlew :ajdm-cli:installDist
ajdm-cli/build/install/ajdm/bin/ajdm daemon
```

In another terminal:

```bash
ajdm-cli/build/install/ajdm/bin/ajdm add 'https://example.com/file.zip' --directory /tmp/downloads --wait
ajdm-cli/build/install/ajdm/bin/ajdm list
ajdm-cli/build/install/ajdm/bin/ajdm pause DOWNLOAD_ID
ajdm-cli/build/install/ajdm/bin/ajdm resume DOWNLOAD_ID
ajdm-cli/build/install/ajdm/bin/ajdm cancel DOWNLOAD_ID
```

`add` prints the new UUID and returns after admission. `--wait` polls the core until completion or a stopped state; exiting the client leaves the transfer running. Default destination is `~/Downloads`, with a safe name from the URL path. `--filename` supplies an explicit portable leaf name. This CLI does not yet select names from Content-Disposition; the engine's existing probe metadata supports that future add-dialog flow. Options include `--sha256`, `--connections`, `--adaptive`, `--header Name:value` and `--conflict RENAME|ASK|OVERWRITE`. Adaptive scheduling remains opt-in, with the existing measured policy/default gaps recorded in the alignment audit.

`resume ID --conflict RENAME` or `OVERWRITE` resolves an Ask collision using already verified bytes. After `pause ID`, `resume ID --connections 8` changes the saved file/media worker count (1–32) while preserving UUID, partials, coverage, source identity, scheduling mode and other options. Paired parents update unfinished children atomically before launching; completed siblings are reused. Changing an active generation is rejected, and omitting the option retains the saved count. Playlist parent overrides remain unavailable; configure an individual paused child. Existing per-host/global response limits still apply. See [connection-change details](RESUME_CONNECTIONS.md). `resume ID --header 'Authorization:Bearer ...'` reattaches transient credentials after reopening. `restart ID` is an explicit source-change decision: it creates a new UUID and partial, preserving the old partial, reason and record. `cancel` waits for the active attempt to stop durably; an inactive cancellation performs no network request and retains the partial. Completed and canceled downloads cannot resume.

The core admits at most three active transfers. A fourth is rejected with AJDM-1004 and creates no record. This guard does not provide a persistent queue; priority, scheduling windows and start-later support remain pending. The [media layer](MEDIA_EXTRACTION.md) provides `formats URL` and individual/paired `add-media` through extraction-only yt-dlp and AJDM payload transfers. [Playlist jobs](PLAYLISTS.md) schedule bounded lazy extraction and wait for these shared transfer slots. Subscription streams and speed limits remain unavailable with AJDM-5003.

`ajdm remove ID` removes a stopped job from AJDM and keeps its files. `ajdm remove ID --delete-files` explicitly deletes its owned downloaded/partial files too. Pause an active download or its owning playlist first. Paired removal includes both internal children; playlist removal includes its admitted child jobs. Removing a stopped playlist child marks its playlist item canceled so it will not be downloaded again. Completed/canceled jobs can be removed, but cannot be resumed. Unknown IDs and fields are rejected; clients cannot supply arbitrary deletion paths. Related metadata removal is one SQLite transaction, with no schema change. File deletion is separate: it preflights regular files, refuses symlink paths, never recursively removes a folder, and preserves metadata on failure so deletion can be retried. It keeps pre-existing output destinations that a paused/failed job never completed. File deletion is refused when another job still uses the same path; entry-only removal remains available. If interruption happens between file deletion and metadata commit, retrying removal clears the retained entry; deletion is not a disk/SQLite atomic transaction.

Daemon lifecycle:

- `daemon` runs in the foreground. Ctrl+C/SIGTERM closes IPC, pauses active transfers, saves progress and releases SQLite ownership. The next daemon leaves interrupted transfers paused until explicit resume; it does not auto-start.
- A second daemon forwards a health check and exits when the current core responds. Database and endpoint locks prevent competing owners during startup races.
- `--socket PATH` is a global option accepted before or after a command. `daemon --state PATH` selects the database for that daemon only. For isolated instances, configure both paths. A client cannot change the daemon's state path.
- Linux defaults to `$XDG_RUNTIME_DIR/ajdm/ajdm.sock` when configured, otherwise the AJDM data directory. macOS uses its Application Support data directory. Paths must fit the native Unix-socket path limit. No TCP fallback is created.

Protocol v1 uses one request and response per connection: a four-byte big-endian byte count followed by strict UTF-8 JSON, maximum 1 MiB per frame. The envelope is `{ "v": 1, "id": "correlation-id", "type": "list", "payload": {} }`. Replies repeat version/id with `ok` and either `payload` or an error's public `code`, catalog-owned `message`, and `action`. Download controls identify the UUID using `payload.downloadId`; resume additionally accepts optional integer `connections` (1–32), alongside headers/conflict. Add supports url, directory, filename, headers, referer, cookies, checksum, connections, adaptive and conflict fields. Unknown fields, coercion, invalid IDs, duplicate keys, excessive nesting and invalid UTF-8 are rejected. Formats and addMedia payloads are documented in the [media guide](MEDIA_EXTRACTION.md); playlist/playlistFormats/addPlaylist/playlistStatus in the [playlist guide](PLAYLISTS.md). Queued/start-later requests explicitly return unavailable.

Directories must be owned by the current OS user with mode 0700; socket and advisory lock use 0600. Symlink paths, foreign/insecure permissions and non-socket files fail closed. The endpoint lock is acquired before stale cleanup, and only a refused connection authorizes removing a stale socket. A live but unresponsive endpoint is kept. Clean shutdown removes only the socket inode this instance created; the lock file remains. At most 16 peers are admitted, with a five-second total frame deadline. Clients have two-second connect, five-second write and forty-second response deadlines (seventy-five seconds for media/listing/add commands; 150 seconds for a playlist entry preview); durable controls wait at most thirty seconds. Losing a reply can leave an accepted operation running: clients do not replay mutating commands automatically.

POSIX socket access control is implemented and tested on Linux. Providers without POSIX permission support fail closed; Windows ACL support and Windows/macOS qualification remain release gates. Short explicit private socket paths can work around native path-length limits. List snapshots expose UUID, state, local destination, written/durable/total bytes, resume capability and safe error/action; URLs, cookies, authorization and raw exceptions are excluded. Human-readable paths escape terminal control characters. Signed source URLs remain in the engine's private SQLite state for recovery.

Numeric CLI exits:

| Exit | Meaning |
|---|---|
| 0 | Command accepted, query succeeded, or waited file/media/playlist work completed without FAILED items |
| 2 | Invalid command, argument, URL or scheme |
| 3 | Invalid download state, paused/canceled result, source change or destination collision |
| 4 | Core unavailable or another instance owns the state/endpoint |
| 5 | Disk, partial-file or state-store error |
| 6 | Network, server, TLS, retry, checksum or renewal error |
| 7 | Feature/media unavailable or media processing error |

Without `--wait`, successful `add` confirms admission, not eventual transfer success. Use `list`/`list --json` for progress and retained errors. Browser shim, installer, JavaFX UI, persistent queue, push subscriptions, production log pipeline and IDM/real-network acceptance remain open. Evidence: [core/CLI baseline](../artifacts/prd-alpha-core-cli/report.md) and [paired-media report](../artifacts/prd-alpha-media-jobs/report.md) and [current playlist report](../artifacts/prd-alpha-playlists/report.md).

Dependency/API references: [picocli 4.7.7](https://picocli.info/index.html), [Gson releases](https://github.com/google/gson/releases), [Java 25 Unix sockets and path limits](https://docs.oracle.com/en/java/javase/25/core/java-networking.html). Dependencies are pinned, locked and SHA-256 verified by Gradle.
