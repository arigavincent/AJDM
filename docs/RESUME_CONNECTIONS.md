# Change connections while retaining a download

Pause a file, individual media stream or paired parent before changing its worker count:

```bash
ajdm pause DOWNLOAD_ID
ajdm resume DOWNLOAD_ID --connections 8
```

The count must be1–32. The existing UUID, partials, completed ranges, validators, media origin, scheduling mode and other transfer settings are retained. Paired parents configure unfinished children in one transaction before launch; completed siblings remain unchanged and make no new payload request. Omitting the option preserves the saved count. Active or terminal changes are rejected. Existing per-host/global response limits apply, so this is a throughput experiment rather than a guaranteed speed increase. Playlist parent overrides are unavailable; individual paused file/media children support the option.

Both client and daemon must contain the new resume handler. A newer client talking to an older daemon will have its connections field rejected. Gracefully stop the old daemon after pausing, then restart the updated executable on the same database/socket/tool paths. Do not start a replacement add-media job to reconfigure existing work.

For the current development session, a separate distribution was staged at `ajdm-cli/build/install/ajdm-resume8/bin/ajdm`. The existing installed daemon files were kept intact while it was running. Use that staged executable for the restarted daemon and resume client; setting Java25 JAVA_HOME is still required.

Implementation uses validated/bound SQLite updates and engine synchronization with retained in-memory requests. It changes worker counts only after the old generation has stopped, saves the counts before launching new workers, and leaves existing source-identity/checkpoint/publication checks intact. No schema migration or dependency was added. AJDM's Java engine owns all payload requests; yt-dlp only extracts metadata and URLs, ffmpeg only copy-muxes completed local streams, and aria2 is not used.

[Validation evidence](../artifacts/prd-alpha-resume-connections/report.md) covers atomic rollback/persistence/coverage, invalid counts and active-state rejection, and real HTTPS/private-IPC CLI resume for individual and paired media both in-process and after reopening. Successful output bytes match the fixture, saved prefixes are not fetched again, completed audio makes no further HTTP request and parallel payload ranges actually run.
