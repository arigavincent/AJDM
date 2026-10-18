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
                      long totalBytes, String target, boolean resumeSupported, ErrorCode error, String kind, String title, String waiting) {
        public Row(UUID id, DownloadState state, long writtenBytes, long durableBytes, long totalBytes, String target,
                   boolean resumeSupported, ErrorCode error, String kind, String title) {
            this(id, state, writtenBytes, durableBytes, totalBytes, target, resumeSupported, error, kind, title, null);
        }
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
    /** Starts queued work when slots, the schedule and the data budget allow; also accounts today's received bytes. */
    private final ScheduledExecutorService queueTicker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ajdm-queue").factory());
    private final Map<UUID, Long> lastReceived = new HashMap<>();
    private volatile QueuePolicy.Wait holding;
    private final MediaCache mediaCache = new MediaCache();
    /** At most two background analyses of pages the browser saw, a few more waiting; extra requests are dropped. */
    private final ThreadPoolExecutor prefetches = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon().name("ajdm-prefetch-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

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
        queueTicker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }
    // ---- Segment map: which parts of a file are saved ----
    /** Saved coverage, plus each running connection's recent speed (bytes per second) while the download runs. */
    public record Segments(long total, List<ByteRange> saved, List<Double> connections) {
        public Segments(long total, List<ByteRange> saved) { this(total, saved, List.of()); }
    }
    /** Durable coverage of one file download (saved continuously while it runs); media jobs and playlists have none. */
    public synchronized Segments segments(UUID id) throws IOException {
        requireOpen();
        var record = engine.download(id).orElseThrow(() -> new IllegalArgumentException("Unknown download"));
        long total = record.probe() == null ? -1 : record.probe().contentLength();
        var handle = active.get(id);
        List<Double> connections = List.of();
        if (handle != null) {
            var adaptation = handle.adaptation();
            connections = adaptation.workerBytesPerSecond().stream().limit(Math.max(0, adaptation.targetConnections())).toList();
        }
        return new Segments(total, record.completed(), connections);
    }
    // ---- Links offered by the browser extension, collected by the desktop window ----
    /**
     * A link the browser offers: a file to download (with the size the browser already knows, or -1), or a video page
     * for the quality picker, optionally with the quality the user chose in the page's AJDM menu.
     */
    public record Offer(String url, String referer, String filename, boolean media, long sizeBytes, String formatId, String audioId) {
        public Offer(String url, String referer, String filename, boolean media) { this(url, referer, filename, media, -1, null, null); }
        public Offer {
            if (url == null || url.length() > 8192) throw new IllegalArgumentException("Invalid offered link");
            var uri = URI.create(url); new DownloadSource(uri, Map.of());
            if (referer != null && (referer.length() > 8192 || !referer.startsWith("http://") && !referer.startsWith("https://"))) referer = null;
            if (filename != null && (filename.isBlank() || !filename.equals(SafeFilename.sanitize(filename)))) filename = filename.isBlank() ? null : SafeFilename.sanitize(filename);
            if (sizeBytes < 0) sizeBytes = -1;
            if (!media || formatId == null || !formatId.matches("[A-Za-z0-9._+-]{1,128}")) { formatId = null; audioId = null; }
            if (audioId != null && !audioId.matches("[A-Za-z0-9._+-]{1,128}")) audioId = null;
        }
        @Override public String toString() { return "Offer[media=" + media + ", source=redacted]"; }
    }
    private final ArrayDeque<Offer> offers = new ArrayDeque<>();
    public synchronized void offer(Offer offer) {
        requireOpen();
        if (offers.size() >= 32) offers.removeFirst();
        offers.addLast(offer); notifyAll();
    }
    public synchronized List<Offer> takeOffers() {
        requireOpen();
        var taken = List.copyOf(offers); offers.clear(); return taken;
    }
    /** Like {@link #takeOffers()}, but waits up to {@code timeoutMillis} for a link so the window opens it at once. */
    public synchronized List<Offer> waitOffers(long timeoutMillis) throws InterruptedException {
        requireOpen();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(timeoutMillis, 30_000)));
        while (offers.isEmpty() && !closing) {
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return takeOffers();
    }
    // ---- Product queue, schedule and data budget ----
    static String today() { return java.time.LocalDate.now().toString(); }
    public synchronized QueueStatus queueStatus() throws IOException {
        requireOpen();
        return new QueueStatus(policy(), engine.usage(today()), holding == null ? null : holding.name().toLowerCase(Locale.ROOT));
    }
    public record QueueStatus(QueuePolicy policy, long usedToday, String holding) {}
    public synchronized void configureQueue(int maxActive, boolean scheduled, java.time.LocalTime start, java.time.LocalTime end,
                                            boolean budgeted, long dailyBytes) throws IOException {
        configureQueue(maxActive, scheduled, start, end, budgeted, dailyBytes, policy().speedLimit());
    }
    public synchronized void configureQueue(int maxActive, boolean scheduled, java.time.LocalTime start, java.time.LocalTime end,
                                            boolean budgeted, long dailyBytes, long speedLimit) throws IOException {
        requireOpen();
        var policy = new QueuePolicy(maxActive, scheduled, start, end, budgeted, dailyBytes, speedLimit);
        engine.saveCoreSettings(policy.toMap());
        engine.speedLimit(policy.speedLimit());
        queueTicker.execute(this::tick);
    }
    private QueuePolicy policy() throws IOException { return QueuePolicy.from(engine.coreSettings()); }
    private int busy() { reap(); return active.size() + mediaJobs.activeTransfers(); }
    /** Whether {@code needed} more transfers may start now under the queue policy. */
    private boolean mayStart(int needed) throws IOException {
        var policy = policy();
        return policy.blocked(java.time.LocalTime.now(), engine.usage(today())) == null && busy() + needed <= policy.maxActive()
                && (needed < 2 || mediaJobs.activeJobs() < policy.maxActive());
    }
    void tick() {
        try {
            synchronized (this) {
                if (closed || closing) return;
                account();
                var policy = policy();
                if (engine.speedLimit() != policy.speedLimit()) engine.speedLimit(policy.speedLimit());
                var wait = policy.blocked(java.time.LocalTime.now(), engine.usage(today()));
                holding = wait;
                if (wait != null) { hold(); return; }
                for (var entry : engine.queue()) {
                    boolean job = entry.kind() == io.ajdm.store.QueueEntry.Kind.MEDIA_JOB;
                    int needed = job ? mediaJobs.requiredTransfers(mediaJobs.find(entry.id())) : 1;
                    if (busy() + Math.max(1, needed) > policy.maxActive() || job && mediaJobs.activeJobs() >= policy.maxActive()) break;
                    engine.dequeue(entry.id());
                    try {
                        if (job) mediaJobs.resume(entry.id(), Map.of(), null);
                        else startDownload(entry.id(), Map.of(), null, null);
                    } catch (IOException | RuntimeException failed) { /* It stays stopped with its saved reason; the user can resume it. */ }
                }
            }
        } catch (Throwable unexpected) { /* The ticker must survive; the next tick retries. */ }
    }
    /** Adds the bytes each running transfer received since the last tick to today's usage. */
    private void account() throws IOException {
        var now = engine.receivedBytes(); long delta = 0;
        for (var entry : now.entrySet()) {
            long previous = lastReceived.getOrDefault(entry.getKey(), 0L), current = entry.getValue();
            delta += current >= previous ? current - previous : current; // A new attempt restarts its counter.
        }
        lastReceived.clear(); lastReceived.putAll(now);
        engine.addUsage(today(), delta);
    }
    /** The schedule closed or the budget ran out: running work pauses and goes back to the front of the queue. */
    private void hold() throws IOException {
        for (var id : List.copyOf(active.keySet())) {
            var handle = active.get(id);
            if (handle == null || handle.completion().toCompletableFuture().isDone()) continue;
            try { await(handle.pause()); engine.enqueue(new io.ajdm.store.QueueEntry(id, io.ajdm.store.QueueEntry.Kind.DOWNLOAD), true); }
            catch (IOException | RuntimeException stillStopping) { /* Retried next tick. */ }
        }
        for (var job : mediaJobs.saved()) {
            if (!mediaJobs.isRunning(job.id())) continue;
            try { mediaJobs.stop(job.id(), false); engine.enqueue(new io.ajdm.store.QueueEntry(job.id(), io.ajdm.store.QueueEntry.Kind.MEDIA_JOB), true); }
            catch (IOException | RuntimeException stillStopping) { /* Retried next tick. */ }
        }
        reap();
    }
    private UUID enqueue(UUID id, io.ajdm.store.QueueEntry.Kind kind) throws IOException {
        engine.enqueue(new io.ajdm.store.QueueEntry(id, kind), false);
        queueTicker.execute(this::tick);
        return id;
    }
    /** Starts (or resumes) one stopped download and attaches the stream remux when it is a single HLS stream. */
    private UUID startDownload(UUID id, Map<String, String> headers, ConflictPolicy policy, Integer connections) throws IOException {
        Optional<MediaOrigin> origin = engine.mediaOrigin(id);
        if (connections != null) engine.configureConnections(List.of(id), connections);
        var handle = origin.isPresent() ? engine.resumeMedia(id, headers, policy, refresher(origin.orElseThrow())) : engine.resume(id, headers, policy);
        return trackStream(handle, origin.orElse(null));
    }
    private UUID trackStream(TransferHandle handle, MediaOrigin origin) {
        UUID id = track(handle);
        if (origin != null && origin.hls() && origin.playlist() == null) {
            String container = remuxContainer(origin.vcodec(), origin.acodec());
            remuxing.add(id);
            handle.completion().whenComplete((result, failure) -> {
                if (failure != null) remuxing.remove(id);
                else remuxes.submit(() -> remux(id, result.target(), container));
            });
        }
        return id;
    }
    public MediaInfo formats(String page) throws IOException {
        return formats(page,null);
    }
    /**
     * The analysis of {@code page}: a recent one is reused (choosing a quality then clicking Download no longer runs
     * the extractor twice). A chosen format missing from it is re-resolved as before.
     */
    private MediaInfo formats(String page,String selectedFormat) throws IOException {
        synchronized (this) { requireOpen(); }
        URI uri;
        try { uri = URI.create(page); new DownloadSource(uri, Map.of()); }
        catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid page URL"); }
        if (selectedFormat != null) {
            var cached = mediaCache.peek(uri.toString());
            if (cached != null && cached.formats().stream().anyMatch(format -> format.formatId().equals(selectedFormat))) return cached;
        }
        try {
            MediaInfo info = selectedFormat==null?mediaCache.get(uri.toString(), () -> extract(uri))
                    :extractor.extractSelected(uri,null,selectedFormat,()->Thread.currentThread().isInterrupted());
            synchronized (this) { requireOpen(); } return info;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DownloadException(ErrorCode.CANCELED, "Extraction interrupted"); }
    }
    private MediaInfo extract(URI page) throws IOException {
        try { return extractor.extract(page, () -> Thread.currentThread().isInterrupted()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DownloadException(ErrorCode.CANCELED, "Extraction interrupted"); }
    }
    /** Starts analysing a video page in the background (the browser saw it) so its qualities are ready when asked for. */
    public void prefetch(String page) throws IOException {
        synchronized (this) { requireOpen(); }
        URI uri;
        try { uri = URI.create(page); new DownloadSource(uri, Map.of()); }
        catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid page URL"); }
        if (mediaCache.peek(uri.toString()) != null) return;
        try { prefetches.execute(() -> { try { mediaCache.get(uri.toString(), () -> extract(uri)); } catch (IOException | RuntimeException ignored) { /* Asked again when needed. */ } }); }
        catch (RejectedExecutionException busy) { /* Enough analyses running; the page is analysed when opened. */ }
    }
    public void mediaCookies(Path file) throws IOException {
        synchronized (this) { requireOpen(); }
        extractor.configureCookies(file); mediaCache.clear();
    }
    public void mediaBrowser(String browser) throws IOException {
        synchronized (this) { requireOpen(); }
        extractor.configureBrowser(MediaBrowser.parse(browser)); mediaCache.clear();
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
        return addMedia(page, formatId, audioId, preset, modern, directory, filename, policy, connections, adaptive, false);
    }
    public MediaAdded addMedia(String page, String formatId, String audioId, MediaSelection.Preset preset, boolean modern,
                               Path directory, String filename, ConflictPolicy policy, int connections, boolean adaptive, boolean hold) throws IOException {
        if (audioId != null && formatId == null) throw new IllegalArgumentException("Audio requires video format");
        if ((formatId == null) == (preset == null)) throw new IllegalArgumentException("Choose a format or preset");
        if (filename != null && !filename.equals(SafeFilename.sanitize(filename))) throw new IllegalArgumentException("Unsafe filename");
        MediaInfo info = formats(page,formatId);
        var plan = formatId == null ? MediaSelection.preset(info, preset, modern) : MediaSelection.custom(info, formatId, audioId);
        return admitMedia(info, plan, directory, filename, policy, connections, UUID.randomUUID(), adaptive, true, hold);
    }
    /** Playlists keep their own retry loop, so a busy core still refuses their entries (INVALID_STATE) instead of queueing. */
    MediaAdded admitMedia(MediaInfo info, MediaSelection.Plan plan, Path directory, String filename, ConflictPolicy policy, int connections, UUID plannedId) throws IOException {
        return admitMedia(info, plan, directory, filename, policy, connections, plannedId, false, false, false);
    }
    private MediaAdded admitMedia(MediaInfo info, MediaSelection.Plan plan, Path directory, String filename, ConflictPolicy policy, int connections, UUID plannedId,
                                  boolean adaptive, boolean queueWhenBusy, boolean hold) throws IOException {
        if (plan.formats().stream().anyMatch(MediaFormat::fragmented)) throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Fragment downloads pending");
        var options = TransferOptions.defaults();
        options = adaptive ? options.withAdaptiveParallelism(connections, options.minSegmentBytes()) : options.withConnections(connections);
        if (plan.requiresMux()) {
            UUID id = plannedId; Path target = directory.resolve(filename == null ? SafeFilename.sanitize(info.title() + "." + plan.container()) : filename).toAbsolutePath().normalize();
            Path workspace = target.getParent().resolve(".ajdm-media-" + id);
            var job = new MediaJob(id, UUID.randomUUID(), UUID.randomUUID(), target, workspace, plan.formats().getFirst().ext(), plan.formats().getLast().ext(),
                    plan.container(), policy, hold ? DownloadState.PAUSED : DownloadState.QUEUED, plan.sizeBytes(), plan.approximateSize(), null, null, null, null, -1);
            var requests = new ArrayList<TransferRequest>(); var origins = new ArrayList<MediaOrigin>();
            for (int i = 0; i < 2; i++) {
                var f = plan.formats().get(i); requests.add(new TransferRequest(i == 0 ? job.videoId() : job.audioId(), f.source().uri(), i == 0 ? job.videoPath() : job.audioPath(), f.source().headers(), options, ConflictPolicy.ASK));
                origins.add(new MediaOrigin(info.pageUri(), info.id(), f.formatId(), f.ext(), f.vcodec(), f.acodec(), f.segmented(), f.dashPlaylist()));
            }
            synchronized (this) {
                requireOpen(); reap();
                var added = new MediaAdded(id, String.join("+", plan.formats().stream().map(MediaFormat::formatId).toList()), "VIDEO_AUDIO", plan.sizeBytes(), plan.approximateSize());
                if (hold) { mediaJobs.prepare(job, requests, origins); return added; }
                if (!mayStart(2)) {
                    if (!queueWhenBusy) throw new DownloadException(ErrorCode.INVALID_STATE, "Media admission limit");
                    mediaJobs.prepare(job, requests, origins); enqueue(id, io.ajdm.store.QueueEntry.Kind.MEDIA_JOB); return added;
                }
                mediaJobs.start(job, requests, origins);
                return added;
            }
        }
        MediaFormat format = plan.formats().getFirst();
        if (format.fragmented()) throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Fragment downloads pending");
        String leaf = filename == null ? SafeFilename.sanitize(info.title() + "." + format.ext()) : filename;
        var origin = new MediaOrigin(info.pageUri(), info.id(), format.formatId(), format.ext(), format.vcodec(), format.acodec(), format.segmented(), format.dashPlaylist());
        var request = new TransferRequest(plannedId, format.source().uri(), directory.resolve(leaf), format.source().headers(), options, policy);
        request.requireSafeTarget();
        synchronized (this) {
            requireOpen(); reap();
            var added = new MediaAdded(plannedId, format.formatId(), format.kind().name(), plan.sizeBytes(), plan.approximateSize());
            if (hold) { engine.prepare(request, origin); return added; }
            if (!mayStart(1)) {
                if (!queueWhenBusy) throw new DownloadException(ErrorCode.INVALID_STATE, "Three transfers already active");
                engine.prepare(request, origin); enqueue(plannedId, io.ajdm.store.QueueEntry.Kind.DOWNLOAD); return added;
            }
            trackStream(engine.startMedia(request, origin, refresher(origin)), origin);
            return added;
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
        return add(url, directory, filename, headers, policy, checksum, connections, adaptive, false);
    }
    /** With {@code hold}, the download is saved stopped ("Download Later") and starts when resumed. */
    public synchronized UUID add(String url, Path directory, String filename, Map<String, String> headers,
                                 ConflictPolicy policy, String checksum, int connections, boolean adaptive, boolean hold) throws IOException {
        requireOpen();
        reap();
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
        if (hold) return engine.prepare(request, null);
        if (!mayStart(1)) { engine.prepare(request, null); return enqueue(request.id(), io.ajdm.store.QueueEntry.Kind.DOWNLOAD); }
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
        var queued = new HashSet<UUID>(); for (var entry : engine.queue()) queued.add(entry.id());
        String waitReason = holding == null ? "slot" : holding.name().toLowerCase(Locale.ROOT);
        for (var job : jobs) { children.add(job.videoId()); children.add(job.audioId()); }
        for (DownloadRecord saved : engine.downloads()) {
            if (children.contains(saved.request().id())) continue;
            var handle = active.get(saved.request().id());
            var p = handle == null ? null : handle.progress();
            DownloadState state = p == null ? saved.state() : p.state();
            if (state == DownloadState.COMPLETED && remuxing.contains(saved.request().id())) state = DownloadState.MERGING;
            String waiting = queued.contains(saved.request().id()) ? waitReason : null;
            // A killed daemon leaves running states in SQLite. Recovery is opt-in, never auto-start.
            if (p == null && Set.of(DownloadState.QUEUED, DownloadState.PROBING, DownloadState.DOWNLOADING,
                    DownloadState.VERIFYING).contains(state)) state = DownloadState.PAUSED;
            if (waiting != null) state = DownloadState.QUEUED;
            rows.add(new Row(saved.request().id(), state, p == null ? saved.durableBytes() : p.writtenBytes(),
                    p == null ? saved.durableBytes() : p.durableBytes(),
                    p == null ? saved.probe() == null ? -1 : saved.probe().contentLength() : p.totalBytes(),
                    saved.request().target().toString(), saved.resumeSupported(), saved.error(),
                    engine.mediaOrigin(saved.request().id()).isPresent() ? "MEDIA" : "FILE", saved.request().target().getFileName().toString(), waiting));
        }
        for (var job : jobs) {
            var r = mediaJobs.row(job);
            boolean waits = queued.contains(job.id());
            rows.add(new Row(r.id(), waits ? DownloadState.QUEUED : r.state(), r.writtenBytes(), r.durableBytes(), r.totalBytes(), r.target(), r.resumeSupported(), r.error(),
                    "MEDIA", job.target().getFileName().toString(), waits ? waitReason : null));
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
        if (engine.dequeue(id)) {
            // Taken out of the queue: it stays stopped until resumed.
            if (isMediaJob(id)) { var job = mediaJobs.find(id); if (!mediaJobs.isRunning(id)) engine.saveMediaJob(job.status(DownloadState.PAUSED, null)); }
            return;
        }
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
            if (!mayStart(Math.max(1, needed))) {
                if (connections != null || policy != null || !headers.isEmpty()) throw new DownloadException(ErrorCode.INVALID_STATE, "Media admission limit");
                return enqueue(id, io.ajdm.store.QueueEntry.Kind.MEDIA_JOB);
            }
            engine.dequeue(id);
            mediaJobs.resume(id, headers, policy, connections); return id;
        }
        Row row = row(id);
        reap(); if (active.containsKey(id)) { if (connections != null) throw new IllegalStateException("Pause before configuring connections"); return id; }
        if (row.state() == DownloadState.COMPLETED || row.state() == DownloadState.CANCELED) throw new IllegalStateException("Terminal download");
        if (!mayStart(1)) {
            // A plain resume waits its turn; explicit overrides apply now or not at all.
            if (connections != null || policy != null || !headers.isEmpty()) throw new DownloadException(ErrorCode.INVALID_STATE, "Three transfers already active");
            return enqueue(id, io.ajdm.store.QueueEntry.Kind.DOWNLOAD);
        }
        engine.dequeue(id);
        return startDownload(id, headers, policy, connections);
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
        engine.dequeue(id);
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
        synchronized (this) { if (closed || closing) return; closing = true; notifyAll(); }
        playlists.close();
        queueTicker.shutdownNow(); prefetches.shutdownNow();
        remuxes.shutdownNow();
        synchronized (this) { closed = true; }
        try { synchronized (this) { mediaJobs.close(); engine.close(); active.clear(); } }
        finally { extractor.close(); }
    }
}
