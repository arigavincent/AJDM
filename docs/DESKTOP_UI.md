# AJDM desktop preview

The `ajdm-ui` module is a JavaFX 25.0.4 client of the existing AJDM core. It does not open SQLite, create another engine, or run extraction/download subprocesses. Closing the window stops only its polling and action workers; the separate core continues downloads.

## Run

Use Java 25 and a Linux graphical desktop. Build the independent UI distribution:

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-25.0.1-oracle-x64
./gradlew :ajdm-ui:installDist
./ajdm-ui/build/install/ajdm-ui/bin/ajdm-ui \
  --socket /tmp/ajdm-real-test-1000/ajdm.sock \
  --directory /home/ariga/Documents/AJDM/manual-test/downloads
```

The UI reconnects automatically once a core starts at that socket. A missing core shows `AJDM-5001`; the window does not launch or resume downloads automatically. Settings can change the socket, default folder, default connections and light/dark theme for this UI session.

For the existing manual-test database, run this in a separate terminal **only when its previous core has exited**:

```bash
JAVA_HOME=/usr/lib/jvm/jdk-25.0.1-oracle-x64 \
/home/ariga/Documents/AJDM/ajdm-cli/build/install/ajdm-resume8/bin/ajdm \
  --socket /tmp/ajdm-real-test-1000/ajdm.sock \
  daemon --state /home/ariga/Documents/AJDM/manual-test/state/ajdm.db \
  --yt-dlp /home/ariga/.local/bin/yt-dlp --ffmpeg /usr/bin/ffmpeg
```

Keep the same database to recover previous jobs. Startup recovers interrupted transfers as paused. The existing staged `ajdm-resume8` CLI supports connection overrides; building this UI never replaces that distribution.

The older `ajdm-resume8` backend does not implement removal. For the current controls, build `./gradlew :ajdm-cli:installDist` and use `ajdm-cli/build/install/ajdm/bin/ajdm` with the same daemon flags, after the previous core exits. The live development session stages the verified current CLI separately under `.runtime/backend-verified/`.

## Live test

1. Select the download. The table shows percentage, size, MiB/s, remaining time and state. Details separate written bytes from checkpointed bytes saved for recovery.
2. Click **Pause** and wait for **Paused**. Enable **Set connections on resume**, choose `8`, then click **Resume**. The same job retains saved coverage; the core still validates source identity before fetching gaps. Unchecked preserves its existing connection setting. Playlist parents do not support this override; use their ordinary Resume.
3. Observe speed for at least 30–60 seconds after reconnect/resume. Compare four and eight connections on the same source and network. More connections can help a server-limited transfer but cannot guarantee better throughput or an advantage over IDM.
4. Close and reopen the UI to confirm the core keeps downloading. Do not stop the daemon when testing window close.

Speed uses recent changes in acknowledged written bytes over about five seconds, sampled once per second. The first sample has no rate. Pause, counter rewind, disconnection and polling gaps over 15 seconds reset the window. ETA is absent when rate/size is unknown or the job is merging/verifying. The chart covers up to 60 recent samples for the whole job; it is not per-connection telemetry.

Selecting a download shows its current state and enables the appropriate controls. Active streams offer **Pause** and **Cancel**; paused streams offer **Resume**, **Cancel** and **Delete**; failed streams offer **Retry**, **Cancel** and **Delete**. A source-change pause offers **Restart**, which starts a new job while retaining the previous files. Completed/canceled streams offer **Delete**. The same actions appear in the right-click menu, and Delete on a focused download table opens confirmation. Cancel, Restart and Delete confirmations remain bound to the originally selected job.

**Delete** removes the saved entry; **Also delete downloaded and partial files from disk** is unchecked by default. Checking it explicitly deletes that job's owned files. Pause a running job or its playlist before deletion. Paired removal includes both internal streams; playlist removal includes its child entries. Directories and unrelated files are retained, and paused/failed jobs do not delete pre-existing destination files they never completed. Symlink/non-regular file paths are refused. If another job uses the same output file, delete the entry with the file checkbox unchecked to keep that shared file. On file-deletion failure the entry remains for retry or entry-only removal.

**Add download** supports a direct HTTP(S) URL, explicit clipboard paste, folder selection, optional safe filename/checksum and 1–32 connections. For a video page, paste its URL and click **Analyse qualities** beside Paste. This opens the media picker, preserves the URL/folder/filename/connections and adaptive choice, and starts metadata analysis without adding a download. Fixed connections are the default; **Adaptive connections (experimental)** is available for both files and media. Checked, the connection value is a maximum and AJDM measures whether increases help. Shared host limits still apply. The selected mode persists on pause/resume; old jobs keep their saved mode. Existing filenames use Rename. Cancel requires confirmation and leaves partial files on disk; canceled jobs are terminal.

**Video qualities** in the sidebar opens the media picker directly. Click **Analyse qualities** to inspect every extracted, non-DRM direct HTTPS format, including qualities above 2160p, before choosing a download. Every format remains a separate row, sorted by resolution and frame rate; labels include HD, Full HD, QHD, 4K, 8K, frame rate and HDR where available. Nothing is selected automatically. Click the exact quality you want: a video-only row automatically pairs the best available audio by bitrate, using compatibility to break ties. Change the readable audio dropdown to choose another language/encoding/bitrate, or uncheck **Include sound** for silent video. Clicking an audio row chooses an audio-only download; combined streams keep their included sound. The summary shows the selected quality, sound and combined stream size. Technical stream IDs and optional automatic choices live in collapsed **Advanced format details**; they are not required for ordinary selection.

**Preview selection** shows the resolved output file type/size and preserves the selected video, audio and sound toggle. Changing any choice clears an outdated preview; changing the URL clears old formats and disables Start download until the new URL is analysed. Discovery and preview are metadata only; AJDM transfers admitted streams after **Start download**. Fragmented and unknown stream types remain visible as **Not supported yet** and cannot be started. Unknown sizes stay unknown; estimates say **About**. Paired stream sizes describe video plus audio before local muxing.

**Playlists** loads 100 entries per page (core limit 1000), retains checked entries across pages, accepts ranges, and offers per-entry preset overrides. A typed range takes precedence over checkboxes. Playlist details shows child states and completed/failed/skipped counts for a selected playlist parent. Discovery does not download payloads.

`Ctrl+N` opens Add download; `Ctrl+R` refreshes. `F11` or **Expand window** switches to full screen, useful when a tiling desktop makes the window too narrow; `Esc` exits full screen. Native controls support keyboard focus. Theme defaults are session-only. About uses the original, unaltered logo embedded in the provided PRD and its exact dedication.

## Verification

```bash
./gradlew :ajdm-ui:test :ajdm-ui:installDist
./gradlew :ajdm-ui:uiSmoke  # Requires DISPLAY; independent disposable core/data.
```

The graphical check starts a local rate-limited HTTP fixture, adds a real download through the dialog, observes progress, clicks Pause/Resume with eight connections, and verifies every final byte. It exercises Paste → Analyse qualities from Add download, verifies preserved inputs and metadata-only discovery, inspects all six synthetic quality/audio/unsupported rows including 4320p HDR, and checks automatic/explicit audio pairing, audio-only/combined/silent-video selection, preview preservation/invalidation and source URL invalidation. It also covers playlist discovery, About and dark theme, then verifies that a second download continues after UI-client shutdown. Clipboard contents are restored after the Paste fixture. Screenshots go to `ajdm-ui/build/ui-smoke/`. The preview launcher supports `--snapshot /path/image.png --close-after 5`; it closes only the UI.

The installed launcher places OpenJFX on its module path. Dependencies have pinned locks and SHA-256 verification. This distribution was qualified on Linux x64 with Java 25; Windows/macOS IPC, other-platform native checksums, bundled runtimes and installers remain unqualified.

## Scope and provenance

This is a usable preview, not full PRD UI acceptance. Persistent queues/scheduling, tray/notifications, proxy/rate-limit settings, segment maps, per-connection telemetry, system-theme tracking, persistent UI preferences, full accessibility qualification and installers remain pending. Polling is 1 Hz; architecture's 4 Hz event coalescing needs a future supported event subscription.

Guidance applied:

- [JavaFX skills](https://github.com/JohannesRabauer/javafx-skills/tree/0a6b8f197f52941d6bcbe799aa148e0942b5a4c4/skills): inspected architecture, concurrency, FXML/CSS, testing/packaging skill files at this immutable commit and installed their markdown only. Community guidance, verified against OpenJFX APIs; no hosted MCP or plugin code enabled.
- [Anthropic frontend design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md): PRD palette, intentional transfer-focused layout, plain action names, visible focus and screenshot critique. Used the existing installed `anthropic-frontend-design` skill.
- [OpenJFX documentation](https://openjfx.io/openjfx-docs/) and [official samples](https://github.com/openjfx/samples) for native JavaFX startup conventions.

Newly installed skills are discoverable by Codex after restarting its session; their inspected instructions were applied during this implementation.

OpenJFX is distributed under [GPLv2 with Classpath Exception](https://github.com/openjdk/jfx/blob/master/LICENSE). The preview also contains existing Gson, SQLite JDBC and Error Prone annotations dependencies; consult their packaged notices and upstream terms. The project's release license decisions and complete redistributable notices remain pending in the PRD audit. yt-dlp and ffmpeg are separate user-provided core tools and are not bundled in the UI distribution.

Validation history (4 October 2026): the initial desktop preview's full backend run found an intermittent playlist rate gate failure. Subsequent backend verification reproduced and fixed delayed-dispatch spacing, and the extended full check passed; see [backend verification](BACKEND_VERIFICATION.md) for the current evidence and remaining release qualification. The quality picker and stream controls pass fifteen display-independent UI tests and the expanded graphical interaction check. This preview remains short of full PRD release acceptance.

Selected downloads now have a **Details** action (also in the context menu). File/media details update live and show MiB progress, speed, ETA, saved bytes, resume capability, output path and catalog errors. **Open folder** opens the destination in the native file manager; **Open file** is enabled only after completion. File opening runs on the bounded UI worker pool. Playlist rows show their actual title, and Details requests paginated playlist status only for playlist groups. Group connection overrides are disabled; child transfers retain their own controls.

Use **Add download** → **Start download** for an HTTP(S) file link (ISO, movie, archive, etc.). The optional **Browser request details** section accepts a Referrer, User-Agent, Cookie and Authorization header when the server requires the browser request context. Password fields mask credentials; AJDM does not obtain them from Chrome automatically. The URL must identify the actual file, not a blob:, filesystem:, or download-page URL. After reopening the daemon, supply transient credentials again via the paused file's Details → Update browser request details.

For public YouTube, TikTok, Facebook, Instagram and Twitter/X media pages, use **Analyse qualities** and choose a returned direct format. Unknown codec/dimension metadata stays explicitly unknown but a direct media file can be selected. Multi-video results offer a playlist-picker action. **Settings → Media site access → Choose file → Use for media** explicitly configures an exported Netscape cookies.txt file for extraction, playlists and future link renewal until the backend restarts. Clear media cookies removes that configuration. Extraction receives a private temporary copy, not the original file; scoped cookies returned separately by yt-dlp are applied only to matching payload hosts/paths. Cookie-copy permissions are qualified on POSIX systems; other platforms fail closed pending ACL qualification.

Site support depends on actual extraction and the returned delivery protocol. HLS/DASH fragments, DRM, inaccessible/private content without valid credentials and extractor site breakages are not made downloadable by these UI changes. See [platform qualification](../artifacts/platform-downloads/report.md) for the live URLs actually tested.

Public TikTok analysis also offers named resolver tiers (HD/Standard/With watermark) beside the original extractor formats. Select the row you want; “Resolution unknown” stays visible when the service does not report dimensions. Public links may be sent to TikWM/Douyin metadata resolvers, as explained in the picker. Configured media cookies disable that third-party route. Payload downloading remains AJDM Java. See [live qualification](../artifacts/ar-download-port/report.md).

For Facebook/Instagram posts that do not expose qualities anonymously, first open the post while signed in to your browser. In **Settings → Media site access**, select that browser and click **Use browser session**. Close Settings and analyse the link again. An exported cookie file is an alternative; selecting either source replaces the other. **Clear media cookies** clears both. No browser session is read automatically. A browser database/decryption error offers the exported-file alternative. Analysis failures display a safe reason and clear old qualities; selecting a session does not guarantee access to a removed or restricted post.
