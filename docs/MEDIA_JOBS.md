# Paired downloads and local copy mux

AJDM now accepts two-stream presets and custom video/audio pairs. Its own engine downloads both streams in parallel; ffmpeg reads only completed local files and copies their encoded streams into one output. This implements the PRD MED-05 foundation. [Durable playlists](PLAYLISTS.md) can now orchestrate these jobs; live-platform/20 GB SSD/native-OS acceptance gates remain open.

Build and start a daemon with trusted tools:

```bash
./gradlew :ajdm-cli:installDist
ajdm-cli/build/install/ajdm/bin/ajdm daemon --yt-dlp /absolute/path/to/yt-dlp --ffmpeg /absolute/path/to/ffmpeg
```

The default mux executable is `bin/ffmpeg` beside the selected SQLite file (`ffmpeg.exe` on Windows). Explicit overrides apply to that daemon invocation. Tool bundling, verified updates and fallback copies remain pending; a missing mux tool fails safely with AJDM-4003 and keeps completed streams. The locally installed test binary is not a shipped LGPL tool distribution.

```bash
ajdm formats 'https://example.com/video' --preset BEST
ajdm add-media 'https://example.com/video' --preset BEST --directory /tmp/downloads --wait
ajdm add-media 'https://example.com/video' --format VIDEO_ID --audio AUDIO_ID --directory /tmp/downloads
ajdm list --json
ajdm pause JOB_ID
ajdm resume JOB_ID
ajdm cancel JOB_ID
```

`--audio` requires `--format` with a video-only ID and an audio-only ID. One-format and AUDIO presets keep their existing individual-file behavior. Presets requiring two streams now return a parent job UUID; `--wait` follows that parent through downloading, merging, verification and publication. Lists show summed child progress and the final target, while hiding internal child rows. Normal controls operate on the parent and coordinate both children. `pause JOB_ID` followed by `resume JOB_ID --connections 8` retains the same parent and saved ranges, atomically updates unfinished children, and leaves completed siblings untouched. The override requires stopped work and retains other transfer options. Child IDs are private orchestration details. `resume JOB_ID --conflict RENAME|OVERWRITE` resolves a late Ask collision using the saved output. Canceled/completed parents cannot resume. A SOURCE_CHANGED pause alone permits `restart JOB_ID`, which re-extracts the same format IDs into a new parent and children, retaining the old workspace; paired restart obtains fresh extracted credentials and rejects a supplied header override.

Protocol `addMedia` now accepts optional `audioId` alongside `formatId`. `preset` and explicit `formatId` remain mutually exclusive. Existing directory, filename, conflict, connections and codec-preference fields apply. Its safe response returns `downloadId`, selected format IDs, kind and size estimate. Lists and error responses omit page/source URLs, cookies, authorization, stderr and raw exception text.

SQLite schema v7 introduced `media_job` (preserved by current v8): parent UUID, child UUIDs, workspace, final target/container, conflict policy, state, estimated size and input/output hashes. Parent, both child downloads, their origins and safe headers commit in one transaction before workers start. Existing v6 media origins migrate without invented parents. Interrupted parents are presented as paused after reopening; nothing auto-starts. Resume attaches each unfinished child's extraction refresher and uses the engine's existing coverage/size/strong-validator/range checks. A finished sibling makes no new HTTP request.

Each parent owns a private `.ajdm-media-JOB_ID` directory beside its target, containing video/audio files, their normal engine partials and a mux output stage. Two children count against the existing three-active-transfer admission guard and share engine host/global connection limits. At most three parents can be active; excess work is rejected, with no persistent queue yet. One ffmpeg mux runs at a time per core.

Admission checks available disk space against twice the declared combined stream size plus 64 MiB: inputs and output. Estimated values remain estimates; with unknown sizes only the reserve can be checked at creation. Before muxing, AJDM checks the actual input-size sum plus the reserve against currently free space. This is admission checking, not physical disk reservation; competing writes, mux overhead and actually full volumes remain qualification work.

Before muxing, AJDM checks completed input sizes and saves SHA-256 hashes. Retry compares retained inputs against those saved hashes. ffmpeg receives absolute local paths, fixed MOV or Matroska demuxers, a `file`-only protocol whitelist for every input/output, disabled MOV external data references, explicit video/audio maps and `-c copy`. MP4 uses faststart; other paired plans use MKV. Metadata/chapters are not copied. There is no shell, network input, transcode or downloader fallback. The process has a thirty-minute deadline, bounded 256 KiB diagnostic pipes, a small environment allowlist and cancellation/shutdown cleanup. Diagnostics become a safe AJDM-4003 rather than exposed stderr. Observed descendant cleanup is not native process-group containment for an instantly detached process.

On mux success AJDM forces the staging file, saves its size/hash and the chosen destination, then publishes atomically. Rename/Ask use a no-clobber hard link; explicit Overwrite uses an atomic replacement move on the same filesystem, with no copy fallback. Numbered rename decisions are saved before publication. A crash after publication is resolved by verifying the final file against the saved hash. Mismatched output is never accepted as complete. Only after COMPLETED is saved are owned stream/stage files deleted; failure, pause and cancel retain useful inputs. A crash during final cleanup can leave workspace files for later cleanup. Directory/power-loss and native no-clobber support remain separate gates.

Tests cover both real MP4 and MKV output decoding and equal copied stream hashes; subprocess faults; input tampering; shared admission; paired pause/cancel/expiry/reopen; late collision; explicit overwrite/source-change restart; transactional rollback; and process kills at parent and paired-checkpoint boundaries. [Current evidence](../artifacts/prd-alpha-media-jobs/report.md). Synthetic media is small: this is not the PRD's 20 GB under-two-minute SSD result or real YouTube 4K corpus.

Primary option references: [FFmpeg stream copy/maps](https://ffmpeg.org/ffmpeg.html), [protocol whitelist](https://ffmpeg.org/ffmpeg-protocols.html), [MOV external references](https://ffmpeg.org/ffmpeg-formats.html#mov_002fmp4_002f3gp).
