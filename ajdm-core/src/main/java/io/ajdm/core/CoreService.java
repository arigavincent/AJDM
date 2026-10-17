package io.ajdm.core;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.TransferHandle;
import io.ajdm.engine.SourceRefresher;
import io.ajdm.engine.SourceRefreshContext;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** The sole engine/store owner. Client snapshots intentionally omit URLs and HTTP headers. */
public final class CoreService implements AutoCloseable {
    public record Row(UUID id, DownloadState state, long writtenBytes, long durableBytes,
                      long totalBytes, String target, boolean resumeSupported, ErrorCode error, String kind, String title) {
        public Row(UUID id, DownloadState state, long writtenBytes, long durableBytes, long totalBytes,
                   String target, boolean resumeSupported, ErrorCode error) {
            this(id, state, writtenBytes, durableBytes, totalBytes, target, resumeSupported, error, "FILE", Path.of(target).getFileName().toString());
        }
    }
    private final AjdmEngine engine;
    private final MediaExtractor extractor;
    private final MediaJobs mediaJobs;
    private final Playlists playlists;
    private final MediaMuxer muxer;
    /** Remuxes of completed single HLS streams; a failure keeps the playable transport stream. */
    private final ExecutorService remuxes = Executors.newVirtualThreadPerTaskExecutor();
    /** Single HLS downloads whose remux is still pending: listed as MERGING, never COMPLETED, until the final file exists. */
    private final Set<UUID> remuxing = ConcurrentHashMap.newKeySet();
    private boolean closing;
    private final Map<UUID, TransferHandle> active = new HashMap<>();
    private boolean closed;
    public CoreService(Path database) throws IOException { this(database, new PlatformMediaExtractor(new YtDlpExtractor(YtDlpExtractor.defaultBinary(database)))); }
    public CoreService(Path database, MediaExtractor extractor) throws IOException { this(database, extractor, new FfmpegMuxer(FfmpegMuxer.defaultBinary(database))); }
    public CoreService(Path database, MediaExtractor extractor, MediaMuxer muxer) throws IOException { this(database, extractor, muxer, MediaJobObserver.none()); }
    public CoreService(Path database, MediaExtractor extractor, MediaMuxer muxer, MediaJobObserver observer) throws IOException {
        this(database, extractor, muxer, observer, PlaylistObserver.none());
    }
    public CoreService(Path database, MediaExtractor extractor, MediaMuxer muxer, MediaJobObserver observer, PlaylistObserver playlistObserver) throws IOException {
        this(database, extractor, muxer, observer, playlistObserver,
                Executors.newFixedThreadPool(3, Thread.ofPlatform().name("ajdm-playlist-extract-", 0).factory()));
    }
    CoreService(Path database, MediaExtractor extractor, MediaMuxer muxer, MediaJobObserver observer,
                PlaylistObserver playlistObserver, ExecutorService extractionExecutor) throws IOException {
        this.extractor = Objects.requireNonNull(extractor); this.muxer = Objects.requireNonNull(muxer);
        Objects.requireNonNull(extractionExecutor);
        try { engine = new AjdmEngine(database); } catch (IOException | RuntimeException e) { extractionExecutor.shutdownNow(); extractor.close(); muxer.close(); throw e; }
        mediaJobs = new MediaJobs(engine, muxer, this::refresher, Objects.requireNonNull(observer));
        playlists = new Playlists(this, engine, extractor, Objects.requireNonNull(playlistObserver), Objects.requireNonNull(extractionExecutor));
        resumeRemuxes();
    }
    public MediaInfo formats(String page) throws IOException {
        return formats(page,null);
    }
    private MediaInfo formats(String page,String selectedFormat) throws IOException {
        synchronized (this) { requireOpen(); }
        URI uri;
        try { uri = URI.create(page); new DownloadSource(uri, Map.of()); }
        catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid page URL"); }
        try {
            MediaInfo info = selectedFormat==null?extractor.extract(uri, () -> Thread.currentThread().isInterrupted())
                    :extractor.extractSelected(uri,null,selectedFormat,()->Thread.currentThread().isInterrupted());
            synchronized (this) { requireOpen(); } return info;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DownloadException(ErrorCode.CANCELED, "Extraction interrupted"); }
    }
    public void mediaCookies(Path file) throws IOException {
        synchronized (this) { requireOpen(); }
        extractor.configureCookies(file);
    }
    public void mediaBrowser(String browser) throws IOException {
        synchronized (this) { requireOpen(); }
        extractor.configureBrowser(MediaBrowser.parse(browser));
    }
    public record MediaAdded(UUID id, String formatId, String kind, long sizeBytes, boolean approximateSize) {}
    public MediaAdded addMedia(String page, String formatId, MediaSelection.Preset preset, boolean modern,
                               Path directory, String filename, ConflictPolicy policy, int connections) throws IOException {
        return addMedia(page, formatId, null, preset, modern, directory, filename, policy, connections);
    }
    public MediaAdded addMedia(String page, String formatId, String audioId, MediaSelection.Preset preset, boolean modern,
                               Path directory, String filename, ConflictPolicy policy, int connections) throws IOException {
        return addMedia(page, formatId, audioId, preset, modern, directory, filename, policy, connections, false);
    }
    public MediaAdded addMedia(String page, String formatId, String audioId, MediaSelection.Preset preset, boolean modern,
                               Path directory, String filename, ConflictPolicy policy, int connections, boolean adaptive) throws IOException {
        if (audioId != null && formatId == null) throw new IllegalArgumentException("Audio requires video format");
        if ((formatId == null) == (preset == null)) throw new IllegalArgumentException("Choose a format or preset");
        if (filename != null && !filename.equals(SafeFilename.sanitize(filename))) throw new IllegalArgumentException("Unsafe filename");
        MediaInfo info = formats(page,formatId);
        var plan = formatId == null ? MediaSelection.preset(info, preset, modern) : MediaSelection.custom(info, formatId, audioId);
        return admitMedia(info, plan, directory, filename, policy, connections, UUID.randomUUID(), adaptive);
    }
    MediaAdded admitMedia(MediaInfo info, MediaSelection.Plan plan, Path directory, String filename, ConflictPolicy policy, int connections, UUID plannedId) throws IOException {
        return admitMedia(info, plan, directory, filename, policy, connections, plannedId, false);
    }
    private MediaAdded admitMedia(MediaInfo info, MediaSelection.Plan plan, Path directory, String filename, ConflictPolicy policy, int connections, UUID plannedId, boolean adaptive) throws IOException {
        if (plan.formats().stream().anyMatch(MediaFormat::fragmented)) throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Fragment downloads pending");
        var options = TransferOptions.defaults();
        options = adaptive ? options.withAdaptiveParallelism(connections, options.minSegmentBytes()) : options.withConnections(connections);
        if (plan.requiresMux()) {
            UUID id = plannedId; Path target = directory.resolve(filename == null ? SafeFilename.sanitize(info.title() + "." + plan.container()) : filename).toAbsolutePath().normalize();
            Path workspace = target.getParent().resolve(".ajdm-media-" + id);
            var job = new MediaJob(id, UUID.randomUUID(), UUID.randomUUID(), target, workspace, plan.formats().getFirst().ext(), plan.formats().getLast().ext(),
                    plan.container(), policy, DownloadState.QUEUED, plan.sizeBytes(), plan.approximateSize(), null, null, null, null, -1);
            var requests = new ArrayList<TransferRequest>(); var origins = new ArrayList<MediaOrigin>();
            for (int i = 0; i < 2; i++) {
                var f = plan.formats().get(i); requests.add(new TransferRequest(i == 0 ? job.videoId() : job.audioId(), f.source().uri(), i == 0 ? job.videoPath() : job.audioPath(), f.source().headers(), options, ConflictPolicy.ASK));
                origins.add(new MediaOrigin(info.pageUri(), info.id(), f.formatId(), f.ext(), f.vcodec(), f.acodec(), f.segmented(), f.dashPlaylist()));
            }
            synchronized (this) {
                requireOpen(); reap();
                if (active.size() + mediaJobs.activeTransfers() + 2 > 3 || mediaJobs.activeJobs() >= 3) throw new DownloadException(ErrorCode.INVALID_STATE, "Media admission limit");
                mediaJobs.start(job, requests, origins);
                return new MediaAdded(id, String.join("+", plan.formats().stream().map(MediaFormat::formatId).toList()), "VIDEO_AUDIO", plan.sizeBytes(), plan.approximateSize());
            }
        }
        MediaFormat format = plan.formats().getFirst();
        if (format.fragmented()) throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Fragment downloads pending");
        String leaf = filename == null ? SafeFilename.sanitize(info.title() + "." + format.ext()) : filename;
        var origin = new MediaOrigin(info.pageUri(), info.id(), format.formatId(), format.ext(), format.vcodec(), format.acodec(), format.segmented(), format.dashPlaylist());
        var request = new TransferRequest(plannedId, format.source().uri(), directory.resolve(leaf), format.source().headers(), options, policy);
        request.requireSafeTarget();
        synchronized (this) {
            requireOpen(); reap(); if (active.size() + mediaJobs.activeTransfers() >= 3) throw new DownloadException(ErrorCode.INVALID_STATE, "Three transfers already active");
            if (format.hls()) remuxing.add(plannedId);
            TransferHandle handle;
            try { handle = engine.startMedia(request, origin, refresher(origin)); }
            catch (IOException | RuntimeException e) { remuxing.remove(plannedId); throw e; }
            UUID id = track(handle);
            if (format.hls()) {
                String container = remuxContainer(format);
                handle.completion().whenComplete((result, failure) -> {
                    if (failure != null) remuxing.remove(id);
                    else remuxes.submit(() -> remux(id, result.target(), container));
                });
            }
            return new MediaAdded(id, format.formatId(), format.kind().name(), plan.sizeBytes(), plan.approximateSize());
        }
    }
    /** A joined HLS stream's everyday container: MP4/M4A when its codecs allow a stream copy into it, else Matroska. */
    static String remuxContainer(MediaFormat format) { return remuxContainer(format.vcodec(), format.acodec()); }
    static String remuxContainer(String vcodec, String acodec) {
        String video = vcodec.toLowerCase(java.util.Locale.ROOT), audio = acodec.toLowerCase(java.util.Locale.ROOT);
        boolean mp4Audio = audio.equals("none") || audio.startsWith("mp4a") || audio.startsWith("aac") || audio.startsWith("mp3")
                || audio.startsWith("ac-3") || audio.startsWith("ec-3");
        if (video.equals("none")) return audio.startsWith("mp4a") || audio.startsWith("aac") || audio.startsWith("mp3") ? "m4a" : "mkv";
        boolean mp4Video = video.startsWith("avc") || video.startsWith("h264") || video.startsWith("hvc") || video.startsWith("hev") || video.startsWith("h265");
        return mp4Video && mp4Audio ? "mp4" : "mkv";
    }
    /**
     * Finishes remuxes a crash or shutdown interrupted: a completed HLS download whose record still names its transport
     * stream is remuxed again (a stale hidden stage is discarded first). The record moves only once the new file exists.
     */
    private void resumeRemuxes() throws IOException {
        for (DownloadRecord saved : engine.downloads()) {
            Path stream = saved.request().target();
            if (saved.state() != DownloadState.COMPLETED || !stream.getFileName().toString().endsWith(".ts")
                    || !Files.isRegularFile(stream, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            var origin = engine.mediaOrigin(saved.request().id()).orElse(null);
            if (origin == null || !origin.hls() || origin.playlist() != null) continue;
            UUID id = saved.request().id();
            String container = remuxContainer(origin.vcodec(), origin.acodec());
            remuxing.add(id);
            remuxes.submit(() -> remux(id, stream, container));
        }
    }
    /**
     * Copies a completed single HLS download from its transport stream into {@code container} beside it, points the
     * download at the new file and only then removes the stream. Any failure leaves the playable stream in place.
     */
    private void remux(UUID id, Path stream, String container) {
        Path stage = stream.resolveSibling(".ajdm-remux-" + id + "." + container);
        try { remuxTo(id, stream, container, stage); }
        finally { remuxing.remove(id); }
    }
    private void remuxTo(UUID id, Path stream, String container, Path stage) {
        try {
            if (Files.isRegularFile(stage, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(stage); // An interrupted earlier attempt.
            muxer.remux(stream, "ts", stage, container, () -> closing || closed);
            // The remuxed file keeps the published stream's access (owner-only), not ffmpeg's default mode.
            try { Files.setPosixFilePermissions(stage, Files.getPosixFilePermissions(stream)); }
            catch (UnsupportedOperationException ignored) { /* Non-POSIX file systems keep their inherited ACLs. */ }
            String name = stream.getFileName().toString();
            String leaf = (name.endsWith(".ts") ? name.substring(0, name.length() - 3) : name) + "." + container;
            Path target = stream.resolveSibling(leaf);
            for (int number = 1; ; number++) {
                // No replace option: an existing file of that name is never overwritten (ATOMIC_MOVE may replace it).
                try { Files.move(stage, target); break; }
                catch (java.nio.file.FileAlreadyExistsException e) {
                    if (number > 10000) throw e;
                    target = stream.resolveSibling(SafeFilename.numbered(leaf, number));
                }
            }
            try { engine.relocateCompletedStream(id, target); }
            catch (IOException | RuntimeException e) { Files.deleteIfExists(target); throw e; }
            Files.deleteIfExists(stream);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(stage); } catch (IOException ignored) { /* A hidden leftover stage is harmless. */ }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { Files.deleteIfExists(stage); } catch (IOException ignored) { /* A hidden leftover stage is harmless. */ }
        }
    }
    public PlaylistInfo playlist(String page) throws IOException {
        synchronized (this) { requireOpen(); }
        URI uri;
        try { uri = URI.create(page); new DownloadSource(uri, Map.of()); } catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid playlist URL"); }
        try { var info = extractor.playlist(uri, () -> Thread.currentThread().isInterrupted()); synchronized (this) { requireOpen(); } return info; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DownloadException(ErrorCode.CANCELED, "Listing interrupted"); }
    }
    public MediaInfo playlistFormats(String page, int index) throws IOException {
        var info = playlist(page); if (index < 1 || index > info.entries().size()) throw new IllegalArgumentException("Unknown playlist entry");
        var entry = info.entries().get(index - 1); if (entry.unavailable() != null) throw new DownloadException(entry.unavailable(), "Entry unavailable");
        try { return extractor.extractEntry(entry.page(), entry.id(), () -> Thread.currentThread().isInterrupted()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DownloadException(ErrorCode.CANCELED, "Entry extraction canceled"); }
    }
    public UUID addPlaylist(String page, String indices, PlaylistSelection.Choice defaultChoice, Map<Integer, PlaylistSelection.Choice> choices,
                            Path directory, ConflictPolicy conflict, int connections, boolean modern) throws IOException {
        var info = playlist(page); var selected = PlaylistSelection.indices(indices, info.entries().size());
        if (!selected.containsAll(choices.keySet())) throw new IllegalArgumentException("Choice outside selection");
        UUID id = UUID.randomUUID(); var job = new PlaylistJob(id, info.title(), info.page(), directory, conflict, connections, modern, DownloadState.QUEUED, null, 0);
        var items = new ArrayList<PlaylistItem>();
        for (int index : selected) {
            var entry = info.entries().get(index - 1); var choice = choices.getOrDefault(index, defaultChoice);
            items.add(new PlaylistItem(id, index, entry.id(), entry.title(), entry.duration(), entry.page(), choice.preset(), choice.formatId(), choice.audioId(),
                    entry.unavailable() == null ? PlaylistItem.State.QUEUED : PlaylistItem.State.SKIPPED, null, entry.unavailable()));
        }
        synchronized (this) { requireOpen(); return playlists.create(job, items); }
    }
    public PlaylistJob playlistJob(UUID id) throws IOException { synchronized (this) { requireOpen(); } return playlists.find(id); }
    public List<PlaylistItem> playlistItems(UUID id) throws IOException { synchronized (this) { requireOpen(); } return playlists.items(id); }
    public DownloadState playlistState(PlaylistJob job) { return playlists.state(job); }
    /** Existing child snapshot only, used by the coordinator without recursing into playlist rows. */
    synchronized Row work(UUID id) throws IOException {
        var job = mediaJobs.saved().stream().filter(j -> j.id().equals(id)).findFirst();
        if (job.isPresent()) return mediaJobs.row(job.orElseThrow());
        var record = engine.download(id); if (record.isEmpty()) return null;
        var saved = record.orElseThrow(); var handle = active.get(id); var progress = handle == null ? null : handle.progress();
        DownloadState state = progress == null ? saved.state() : progress.state();
        if (progress == null && Set.of(DownloadState.QUEUED, DownloadState.PROBING, DownloadState.DOWNLOADING, DownloadState.VERIFYING).contains(state)) state = DownloadState.PAUSED;
        return new Row(id, state, progress == null ? saved.durableBytes() : progress.writtenBytes(), progress == null ? saved.durableBytes() : progress.durableBytes(),
                saved.probe() == null ? -1 : saved.probe().contentLength(), saved.request().target().toString(), saved.resumeSupported(), saved.error());
    }
    private SourceRefresher refresher(MediaOrigin origin) {
        return new SourceRefresher() {
            @Override public DownloadSource refresh(SourceRefreshContext context) throws IOException, InterruptedException {
                return renewed(context).source();
            }
            /** A DASH stream renews its fragment list with its source. */
            @Override public StreamSource refreshStream(SourceRefreshContext context) throws IOException, InterruptedException {
                MediaFormat format = renewed(context);
                return new StreamSource(format.source(), format.dashPlaylist());
            }
            private MediaFormat renewed(SourceRefreshContext context) throws IOException, InterruptedException {
                context.checkCanceled();
                MediaInfo info = extractor.extractSelected(origin.pageUri(), origin.mediaId(), origin.formatId(), context::isCanceled);
                MediaFormat format;
                try { format = info.format(origin.formatId()); }
                catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.REFRESH_FAILED, "Chosen format disappeared"); }
                if (!origin.mediaId().equals(info.id()) || !origin.ext().equals(format.ext())
                        || !origin.vcodec().equals(format.vcodec()) || !origin.acodec().equals(format.acodec())) {
                    throw new DownloadException(ErrorCode.SOURCE_CHANGED, "Chosen media identity changed");
                }
                context.checkCanceled(); return format;
            }
        };
    }
    public synchronized UUID add(String url, Path directory, String filename, Map<String, String> headers,
                                 ConflictPolicy policy, String checksum, int connections, boolean adaptive) throws IOException {
        requireOpen();
        reap();
        if (active.size() + mediaJobs.activeTransfers() >= 3) throw new DownloadException(ErrorCode.INVALID_STATE, "Three transfers already active");
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid source"); }
        if (uri.getScheme() != null && !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new DownloadException(ErrorCode.UNSUPPORTED_SCHEME, "Unsupported source");
        }
        String leaf = filename == null ? SafeFilename.fromUri(uri) : filename;
        if (!leaf.equals(SafeFilename.sanitize(leaf))) throw new IllegalArgumentException("Unsafe filename");
        var defaults = TransferOptions.defaults();
        var options = new TransferOptions(defaults.connectTimeout(), defaults.requestTimeout(), defaults.stallTimeout(),
                defaults.bufferBytes(), defaults.maxInflightBuffers(), defaults.windowBytes(), defaults.maxRetries(),
                defaults.retryBaseDelay(), defaults.retryMaxDelay(), checksum, defaults.checkpointInterval(),
                connections, defaults.minSegmentBytes(), adaptive);
        TransferRequest request;
        try { request = new TransferRequest(UUID.randomUUID(), uri, directory.resolve(leaf), headers, options, policy); }
        catch (IllegalArgumentException e) {
            // Validate the URL separately to distinguish invalid source from other arguments.
            try { new DownloadSource(uri, Map.of()); }
            catch (IllegalArgumentException invalid) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid source"); }
            throw e;
        }
        return track(engine.start(request));
    }
    private UUID track(TransferHandle handle) {
        active.put(handle.id(), handle);
        // Do not acquire the service monitor in the completion callback: controls wait for durable completion.
        return handle.id();
    }
    private void reap() { active.entrySet().removeIf(e -> e.getValue().completion().toCompletableFuture().isDone()); }
    public synchronized List<Row> list() throws IOException {
        requireOpen(); reap();
        List<Row> rows = new ArrayList<>();
        var jobs = mediaJobs.saved(); var children = new HashSet<UUID>();
        for (var job : jobs) { children.add(job.videoId()); children.add(job.audioId()); }
        for (DownloadRecord saved : engine.downloads()) {
            if (children.contains(saved.request().id())) continue;
            var handle = active.get(saved.request().id());
            var p = handle == null ? null : handle.progress();
            DownloadState state = p == null ? saved.state() : p.state();
            if (state == DownloadState.COMPLETED && remuxing.contains(saved.request().id())) state = DownloadState.MERGING;
            // A killed daemon leaves running states in SQLite. Recovery is opt-in, never auto-start.
            if (p == null && Set.of(DownloadState.QUEUED, DownloadState.PROBING, DownloadState.DOWNLOADING,
                    DownloadState.VERIFYING).contains(state)) state = DownloadState.PAUSED;
            rows.add(new Row(saved.request().id(), state, p == null ? saved.durableBytes() : p.writtenBytes(),
                    p == null ? saved.durableBytes() : p.durableBytes(),
                    p == null ? saved.probe() == null ? -1 : saved.probe().contentLength() : p.totalBytes(),
                    saved.request().target().toString(), saved.resumeSupported(), saved.error(),
                    engine.mediaOrigin(saved.request().id()).isPresent() ? "MEDIA" : "FILE", saved.request().target().getFileName().toString()));
        }
        for (var job : jobs) {
            var r = mediaJobs.row(job);
            rows.add(new Row(r.id(), r.state(), r.writtenBytes(), r.durableBytes(), r.totalBytes(), r.target(), r.resumeSupported(), r.error(), "MEDIA", job.target().getFileName().toString()));
        }
        for (var job : playlists.saved()) rows.add(new Row(job.id(), playlists.state(job), 0, 0, -1, job.directory().toString(), true, job.error(), "PLAYLIST", job.title()));
        return List.copyOf(rows);
    }
    private Row row(UUID id) throws IOException {
        return list().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown download"));
    }
    public void pause(UUID id) throws IOException {
        if (playlists.isPlaylist(id)) { playlists.stop(id, false); return; }
        pauseDownload(id);
    }
    private synchronized void pauseDownload(UUID id) throws IOException {
        requireOpen();
        if (isMediaJob(id)) { mediaJobs.stop(id, false); return; }
        Row row = row(id); var handle = active.get(id);
        if (handle == null) {
            if (row.state() == DownloadState.PAUSED) return;
            throw new IllegalStateException("Download is not running");
        }
        await(handle.pause()); reap();
    }
    public UUID resume(UUID id, Map<String, String> headers, ConflictPolicy policy) throws IOException {
        return resume(id, headers, policy, null);
    }
    public UUID resume(UUID id, Map<String, String> headers, ConflictPolicy policy, Integer connections) throws IOException {
        if (connections != null && (connections < 1 || connections > 32)) throw new IllegalArgumentException("Invalid connection count");
        if (playlists.isPlaylist(id)) {
            if (connections != null) throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Configure an individual playlist child");
            if (!headers.isEmpty()) throw new IllegalArgumentException("Playlist resumes extract fresh item credentials");
            playlists.resume(id, policy); return id;
        }
        return resumeDownload(id, headers, policy, connections);
    }
    private synchronized UUID resumeDownload(UUID id, Map<String, String> headers, ConflictPolicy policy, Integer connections) throws IOException {
        requireOpen();
        if (isMediaJob(id)) {
            reap(); if (mediaJobs.isRunning(id)) { if (connections != null) throw new IllegalStateException("Pause before configuring connections"); return id; }
            var job = mediaJobs.find(id);
            int needed = mediaJobs.requiredTransfers(job);
            if (active.size() + mediaJobs.activeTransfers() + needed > 3 || mediaJobs.activeJobs() >= 3) throw new DownloadException(ErrorCode.INVALID_STATE, "Media admission limit");
            mediaJobs.resume(id, headers, policy, connections); return id;
        }
        Row row = row(id);
        reap(); if (active.containsKey(id)) { if (connections != null) throw new IllegalStateException("Pause before configuring connections"); return id; }
        if (active.size() + mediaJobs.activeTransfers() >= 3) throw new DownloadException(ErrorCode.INVALID_STATE, "Three transfers already active");
        if (row.state() == DownloadState.COMPLETED || row.state() == DownloadState.CANCELED) throw new IllegalStateException("Terminal download");
        Optional<MediaOrigin> origin = engine.mediaOrigin(id);
        if (connections != null) engine.configureConnections(List.of(id), connections);
        return track(origin.isPresent() ? engine.resumeMedia(id, headers, policy, refresher(origin.orElseThrow())) : engine.resume(id, headers, policy));
    }
    public UUID restart(UUID id, Map<String, String> headers) throws IOException {
        MediaJob job; MediaOrigin video, audio;
        synchronized (this) {
            requireOpen();
            if (isMediaJob(id)) {
                if (mediaJobs.isRunning(id)) throw new IllegalStateException("Pause the media job before restart");
                job = mediaJobs.find(id);
                if (job.state() != DownloadState.PAUSED || job.error() != ErrorCode.SOURCE_CHANGED) throw new IllegalStateException("Only source-change pauses can restart");
                if (!headers.isEmpty()) throw new IllegalArgumentException("Paired restart obtains fresh extracted headers");
                video = engine.mediaOrigin(job.videoId()).orElseThrow(); audio = engine.mediaOrigin(job.audioId()).orElseThrow();
            } else {
                row(id);
                if (active.size() + mediaJobs.activeTransfers() >= 3) throw new DownloadException(ErrorCode.INVALID_STATE, "Three transfers already active");
                Optional<MediaOrigin> origin = engine.mediaOrigin(id);
                if (origin.isPresent()) return track(engine.restartMedia(id, headers, refresher(origin.orElseThrow())));
                return track(engine.restart(id, headers));
            }
        }
        // Extraction is outside the service monitor. Explicit restart preserves old parent and stream files.
        return addMedia(video.pageUri().toString(), video.formatId(), audio.formatId(), null, true, job.target().getParent(), job.target().getFileName().toString(), job.conflict(), 4).id();
    }
    public void cancel(UUID id) throws IOException {
        if (playlists.isPlaylist(id)) { playlists.stop(id, true); return; }
        cancelDownload(id);
    }
    public synchronized void remove(UUID id, boolean deleteFiles) throws IOException {
        requireOpen(); reap();
        synchronized (playlists) {
            playlists.requireRemovable(id);
            if (active.containsKey(id) || mediaJobs.isRunning(id)) throw new IllegalStateException("Pause before deleting");
            if (playlists.isPlaylist(id)) for (var item : playlists.items(id))
                if (item.childId() != null && (active.containsKey(item.childId()) || mediaJobs.isRunning(item.childId())))
                    throw new IllegalStateException("Playlist child is still stopping");
            engine.removeStopped(id, deleteFiles);
        }
    }
    private synchronized void cancelDownload(UUID id) throws IOException {
        requireOpen();
        if (isMediaJob(id)) { mediaJobs.stop(id, true); return; }
        Row row = row(id); var handle = active.get(id);
        if (row.state() == DownloadState.CANCELED) return;
        if (row.state() == DownloadState.COMPLETED) throw new IllegalStateException("Completed download");
        if (handle != null) {
            handle.cancel();
            // The result is exceptional on successful cancellation; inspect the durable state afterwards.
            await(handle.completion().handle((result, failure) -> null)); reap();
            if (row(id).state() != DownloadState.CANCELED) throw new IllegalStateException("Download could not be canceled");
        } else engine.cancelInactive(id);
    }
    private boolean isMediaJob(UUID id) throws IOException { return mediaJobs.saved().stream().anyMatch(j -> j.id().equals(id)); }
    private static void await(CompletionStage<?> stage) throws IOException {
        try { stage.toCompletableFuture().get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Control interrupted", e); }
        catch (TimeoutException e) { throw new DownloadException(ErrorCode.INVALID_STATE, "Control still stopping"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof DownloadException failure) throw failure;
            throw new DownloadException(ErrorCode.INVALID_STATE, "Control failed");
        }
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Core is closed"); }
    @Override public void close() {
        synchronized (this) { if (closed || closing) return; closing = true; }
        playlists.close();
        remuxes.shutdownNow();
        synchronized (this) { closed = true; }
        try { synchronized (this) { mediaJobs.close(); engine.close(); active.clear(); } }
        finally { extractor.close(); }
    }
}
