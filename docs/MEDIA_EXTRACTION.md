# Media discovery and individual-format downloads

`ajdm-media` adds the PRD MED-01/02/03/04/07/11 foundation to the existing core and CLI. yt-dlp reads metadata; AJDM's own engine performs every payload request, write, retry and checkpoint. This slice supports individual direct HTTPS formats. [Paired streams and local ffmpeg mux jobs](MEDIA_JOBS.md) are implemented in the next slice; [durable playlists](PLAYLISTS.md) are also available; bundled updates remain separate work.

Build with Java 25 and start the core with an explicit trusted executable:

```bash
./gradlew :ajdm-cli:installDist
ajdm-cli/build/install/ajdm/bin/ajdm daemon --yt-dlp /absolute/path/to/yt-dlp
```

The daemon's default extractor path is `bin/yt-dlp` beside its selected SQLite file (`yt-dlp.exe` on Windows). A missing executable affects media commands with AJDM-4001; ordinary file downloads remain available. No implicit PATH discovery, bundled tool installation or updater is implemented yet. The explicit override persists only for that daemon invocation; provide it again after reopening.

In another terminal, using the installed `ajdm` executable:

```bash
ajdm formats 'https://example.com/video'
ajdm formats 'https://example.com/video' --show-all --json
ajdm formats 'https://example.com/video' --max-height 4320
ajdm formats 'https://example.com/video' --preset P1080
ajdm formats 'https://example.com/video' --format VIDEO_ID --audio AUDIO_ID
ajdm add-media 'https://example.com/video' --format FORMAT_ID --directory /tmp/downloads --wait
ajdm add-media 'https://example.com/video' --preset AUDIO --directory /tmp/downloads
```

`formats` lists all eligible non-DRM HTTPS formats before applying its presentation ceiling. It exposes format ID, kind, container, resolution, fps, video/audio codecs, video/audio bitrates, exact or `~` estimated size, HDR, note and language. Default height is 2160; `--show-all` includes higher formats, while `--max-height` accepts a ceiling up to 4320. Unknown codec/size metadata remains unknown and visible. Raw direct URLs, page URLs and HTTP headers are omitted from the IPC response and CLI output. HLS/DASH protocol entries are excluded from the v1 table; fragment-bearing HTTPS entries remain visible with a `fragmented` JSON flag, but cannot be admitted as direct files.

Preset names are `BEST` (up to 2160p), `P1080`, `P720` and `AUDIO`. Video ordering is height, fps, codec preference, bitrate, then a stable format-ID tie break. Modern preference is AV1, VP9, AVC; `--compatible-codecs` reverses that preference. Audio orders by abr, then Opus for WebM or AAC for MP4, then stable ID. A muxed format does not add audio twice. Custom preview supports one individual format, or one video-only plus one audio-only format. Unknown codec combinations remain explicitly selectable as individual files but do not enter automatic paired presets. Fragment formats do not enter presets. Selection results include format IDs, output-container plan, whether mux is needed and exact/estimated/unknown combined size; unknown/overflowing totals remain unknown.

`add-media` requires exactly one `--format` or `--preset`, with optional `--audio` for a custom pair. It re-extracts fresh URLs before admission. An individual video-only or audio-only selection remains that individual stream, as the printed kind states. A preset needing two streams creates the durable parent described in the [paired-job guide](MEDIA_JOBS.md). Custom pairs use `--format VIDEO_ID --audio AUDIO_ID`. Options include `--filename`, `--directory`, `--connections` (default four), `--adaptive`, `--conflict RENAME|ASK|OVERWRITE` and `--wait`. Default name is the sanitized title plus the actual format extension. Normal list/pause/resume/cancel/restart commands use the returned UUID.

`--adaptive` enables measured concurrency and tail stealing for every selected stream; `--connections` then specifies the maximum worker count. It starts at up to two workers, tests increases, and rolls back trials that fail to improve useful throughput. The configured shared limit is eight active responses per host and 32 overall; video/audio on the same host share that allowance. Omitting the flag retains fixed scheduling, including older API callers and playlist admission. IPC `addMedia` accepts optional boolean `adaptive`, default false. Scheduling mode persists across pause/reopen and connection overrides; existing downloads are not changed. This exposes the tested adaptive engine but does not complete every ENG-03/04 criterion or establish a live speed advantage. See [fair speed comparisons](SPEED_TESTING.md).

SQLite schema v6 introduced `media_origin` (preserved by subsequent v7 paired-job and current v8 playlist migrations): private page URL, media ID, exact format ID, extension and codec identity. Origin, download and safe headers are inserted in one transaction before workers start; failed origin insertion rolls everything back. Existing ordinary-file records migrate without inventing media identities. Cookies and authorization are still transient. Page/signed URLs are necessarily private recovery metadata, protected by the existing state directory/database permissions.

On an expired source, the core re-extracts the full table and selects the exact saved ID in Java; IDs are never evaluated as yt-dlp selector expressions. Media ID, extension and codec identity must still match. The engine then verifies replacement size, ranges and strong validator before retaining saved coverage and atomically replacing the source. The next daemon reattaches this adapter from persisted origin when a user resumes. An old credential-free probe can fail first; fresh extraction restores its transient headers. Extraction/identity rejection preserves old ranges and becomes a safe renewal failure. Existing engine renewal limits remain three attempts with a thirty-second callback deadline; explicit ten-second renewal spacing and physical network-change detection remain open.

Process boundary:

- A dedicated four-platform-thread executor admits at most three extraction jobs; admission is released after cleanup. Pipe readers run on virtual threads under that bound.
- Commands use argument lists with a literal URL after `--`: `--ignore-config --no-plugin-dirs --simulate --skip-download --no-check-formats --no-cache-dir --no-playlist --no-warnings --no-progress --encoding utf-8 --dump-single-json`.
- User configuration/plugins, downloader probes and implicit cache writes are disabled. Only a small OS/runtime environment allowlist is inherited; credentials, Python injection variables and proxy environment are excluded.
- Extraction has a sixty-second process/pipe deadline, 8 MiB stdout and 256 KiB stderr limits, strict UTF-8/JSON, duplicate-key/depth checks and a 4096-format bound. Unknown extractor fields are ignored when mapping metadata. Existing pinned Gson is reused for this behavior instead of adding Jackson solely for a second JSON mapper.
- Cancellation/shutdown terminates the process and observed descendants, closes pipes and suppresses raw diagnostic text. Java process-tree observation is not a native process-group guarantee for an instantly detached child; native cross-OS process qualification remains open.
- IPC media requests have a seventy-five-second response deadline. Losing a reply does not replay admission or cancel an accepted operation automatically. Initial discovery can finish or time out after a client disconnect; daemon shutdown cancels it.

DRM-marked media/formats are refused with AJDM-4004. Invalid or unsupported extraction results use safe catalog errors; stderr, URL tokens and cookies do not appear in user messages. No insecure TLS option is offered. Automatic browser-store import, remembered credentials, subtitle/thumbnail embedding, fragment downloading and failure-triggered tool updates are not implemented by this adapter. Explicit exported cookie-file and browser-session selection are described below.

Tests use a subprocess metadata fixture and real AJDM HTTPS transfers with a deliberately trusted local certificate and valid SAN. They verify no payload request during discovery, exact completed bytes, forwarded transient headers, expiry during transfer, expiry after reopening and production-daemon forced-kill recovery. The installed yt-dlp 2026.08.19 accepted the complete flag set in a non-network version smoke check. This does not establish the PRD's >=30 videos across >=5 sites corpus, typical <=6 s discovery, real YouTube 4K acceptance or large-file mux timing, Windows/macOS qualification or IDM/4G/5G throughput. [Current evidence](../artifacts/prd-alpha-media-extraction/report.md).

Primary references: [yt-dlp simulation/configuration options](https://github.com/yt-dlp/yt-dlp#usage-and-options), [format metadata/selection](https://github.com/yt-dlp/yt-dlp#format-selection), [extractor schema](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/common.py). AJDM's PRD and architecture §9 control product scope and acceptance.

Platform path additions: metadata may contain one top-level direct HTTPS URL without a formats array; it becomes the stable `source` format while missing codecs/size remain unknown. BEST can fall back to an unknown-resolution direct media file when no known-height video fits; height-limited presets still require known resolution. Multi-video pages produce AJDM-4005 and are handled by the playlist flow. A collection child may share its parent page with other children: extraction and source refresh select its saved media ID from that collection, keeping the original page available for renewal without persisting expired CDN URLs as its identity.

IPC `list` adds backward-compatible `kind` (FILE, MEDIA, PLAYLIST) and `title` fields. IPC `mediaCookies` accepts either an explicit local `file` path or an allowlisted `browser` name; an empty request clears both session sources. Combining sources is rejected. The caller-selected Netscape cookie file is bounded to2MiB, must be a regular non-symlink file, and is copied with0600 permissions for each metadata subprocess and removed after cleanup. Cookies are not automatically imported from a browser. yt-dlp's scoped `cookies` metadata is converted to a Cookie request header after matching domain, path, secure scheme and expiry. Cookie/Authorization still remain transient and absent from IPC diagnostics. No payload downloader is enabled.

Paired jobs also accept MP3 audio (for example a TikTok video-only representation plus its audio track), copied into MKV. ffmpeg is forced to the local `mp3` demuxer for that input and retains its file-only protocol whitelist. Real HTTPS/CLI integration verifies unchanged per-stream hashes and successful decoding for this pairing.

### Public TikTok resolver alternatives

Production extraction now wraps yt-dlp with `PlatformMediaExtractor`. Following the local ArDownload implementation (`/home/ariga/Desktop/ArDownload/backend/src/common/utils/tiktok-media.adapter.ts` and `yt-dlp.util.ts`), public TikTok post/share links also request metadata from TikWM and, if needed, the legacy Douyin resolver. Only the validated public post URL is sent; tracking query parameters are removed. These are external metadata services and availability is not guaranteed. Configuring a media cookie file or browser session disables this route; authenticated links remain with the primary extractor. Instagram uses the primary extractor and explicit cookie-file or browser-session handoff, with no third-party resolver.

Successful yt-dlp formats remain in the inventory. Resolver alternatives have stable IDs `resolver-tiktok-hd`, `resolver-tiktok-standard`, `resolver-tiktok-watermarked` and `resolver-tiktok-audio`. Exact duplicate resolver URLs are omitted. Tier names do not invent pixel dimensions, codecs or byte estimates. Selection is explicit, and changing HD/standard/watermarked tier is never an automatic resume fallback. A missing choice or changed post ID is rejected. Existing engine size/range/validator checks still govern partial reuse.

Resolver HTTP is implemented with Java25 HttpClient, three admitted extraction calls, fixed HTTPS service endpoints, no redirects, a2MiB response cap and a10-second full-response deadline per endpoint, with cancellation/shutdown. Returned sources must use HTTPS, no credentials/fragment or unusual port, and known TikTok/TikWM CDN domain boundaries. No Cookie or Authorization header is sent to a resolver. The Java engine owns every media body, range, retry and file write. yt-dlp remains simulation-only; ffmpeg remains local copy mux only.

Java probes now test the authoritative one-byte GET if HEAD returns a5xx status without Retry-After, or a response timeout. Explicit Retry-After, TLS/connect failures and malformed GET ranges remain errors. This addresses observed TikTok CDN HEAD503/timeout despite successful GET206; it does not bypass access-denied GET responses.

See [ArDownload port qualification](../artifacts/ar-download-port/report.md) for real-source results and limitations.

Chosen resolver IDs route directly to that Java metadata provider at admission and renewal, avoiding a preliminary yt-dlp call and keeping a resolver refresh within the existing30-second engine callback budget. Public anonymous TikTok discovery caps the primary subprocess at45seconds before resolver alternatives, reserving up to20seconds for the two providers within the75-second IPC window. Other pages and cookie-configured extraction retain the60-second subprocess limit. A six-second valid metadata-response regression covers slow resolution; exact post/tier checks still refuse substitution.

## Facebook and Instagram session access

ArDownload's shared metadata adapter supports user-selected browser cookies; AJDM now supports that route too. In Settings > Media site access, choose the browser in which the post opens while signed in and click **Use browser session**, then analyse again. Supported names are Firefox, Chrome, Chromium, Brave, Edge, Opera, Vivaldi, Whale and Safari. Names cannot contain profile paths or extra extractor arguments. Availability and cookie decryption depend on the installed browser and OS; no browser is read until explicitly selected and a metadata request is made. Exported Netscape cookie files remain an alternative. The session choice lasts until cleared/replaced or the backend restarts.

The extractor uses an atomic file-or-browser session snapshot. Browser cookie export is directed into a temporary 0600 file, deleted in subprocess cleanup. Cookie stores and encrypted keys are handled by yt-dlp for metadata only; scoped returned cookies still follow AJDM's existing domain/path/expiry checks before Java payload requests. Authenticated TikTok links never use public third-party metadata resolvers. Other operating systems need permission/decryption qualification; temporary copies fail closed without POSIX permissions.

Bounded stderr is classified into catalog-owned errors: AJDM-4006 when Meta does not expose media (which may mean login, unavailable content or a changed extractor), AJDM-4007 for explicit login requirements, and AJDM-4008 when the selected browser cookie database/decryption is inaccessible. Raw diagnostics, private paths and signed URLs are not shown. Failed re-analysis clears stale choices and disables Start download.

On 2026-10-04, the supplied Facebook share 19t6ZfBjt6 resolved to media1060875123305557 but anonymous extraction failed, including ArDownload's legacy UA/Referer and watch/video/reel variants. ArDownload's diagnostic Instagram post DbFkmpuMLPl also returned an empty media response, while public NASA reel DbL6n0ggXDZ returned six direct formats. No signed-in browser was accessed during qualification. Browser-session fixture coverage verifies routing and cleanup; actual access to those blocked posts still needs the user's session.
