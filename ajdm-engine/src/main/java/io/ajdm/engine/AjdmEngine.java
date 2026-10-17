package io.ajdm.engine;

import io.ajdm.model.*;
import io.ajdm.net.*;
import io.ajdm.store.SqliteDownloadStore;
import java.io.UncheckedIOException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** AJDM's transfer worker with durable, acknowledged-write recovery. */
public final class AjdmEngine implements TransferEngine {
    private final SqliteDownloadStore store;
    private final CheckpointObserver observer;
    private final DiskSpaceProbe diskSpace;
    private final PublicationFiles publication;
    private final ConnectionBudget connections;
    private final BufferBudget buffers;
    private final int maximumConnections;
    private final boolean prewarm;
    private final ExecutorService disks = Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()),
            Thread.ofPlatform().name("ajdm-disk-", 0).factory());
    private final ScheduledExecutorService watchdogs = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().name("ajdm-stall-", 0).factory());
    private final ConcurrentMap<UUID, Handle> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, TransferRequest> liveRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RefreshRegistration> refreshers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService checkpointTimers = Executors.newScheduledThreadPool(1,
            Thread.ofPlatform().name("ajdm-checkpoint").factory());
    private boolean closed;

    public AjdmEngine() throws IOException { this(defaultDatabase()); }
    public AjdmEngine(Path database) throws IOException { this(database, CheckpointObserver.none()); }
    public AjdmEngine(Path database, CheckpointObserver observer) throws IOException { this(database, EngineLimits.defaults(), observer); }
    public AjdmEngine(Path database, EngineLimits limits) throws IOException { this(database, limits, CheckpointObserver.none()); }
    public AjdmEngine(Path database, EngineLimits limits, CheckpointObserver observer) throws IOException {
        this(database, limits, observer, DiskSpaceProbe.fileStore());
    }
    public AjdmEngine(Path database, EngineLimits limits, CheckpointObserver observer, DiskSpaceProbe diskSpace) throws IOException {
        this(database, limits, observer, diskSpace, PublicationFiles.system());
    }
    public AjdmEngine(Path database, EngineLimits limits, CheckpointObserver observer, DiskSpaceProbe diskSpace,
                      PublicationFiles publication) throws IOException {
        this.observer = java.util.Objects.requireNonNull(observer);
        this.diskSpace = java.util.Objects.requireNonNull(diskSpace);
        this.publication = java.util.Objects.requireNonNull(publication);
        maximumConnections = Math.min(limits.connectionsPerHost(), limits.connectionsTotal());
        prewarm = limits.prewarm() && !"false".equalsIgnoreCase(System.getProperty("ajdm.prewarm"));
        connections = new ConnectionBudget(limits); buffers = new BufferBudget(limits.inflightBytes());
        store = new SqliteDownloadStore(database);
    }
    public static Path defaultDatabase() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return (local == null ? home.resolve("AppData/Local") : Path.of(local)).resolve("AJDM/ajdm.db");
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/AJDM/ajdm.db");
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg == null || xdg.isBlank() ? home.resolve(".local/share") : Path.of(xdg)).resolve("ajdm/ajdm.db");
    }
    @Override public TransferHandle start(TransferRequest request, SourceRefresher refresher) throws IOException {
        return start(request, refresher, SourceRefreshPolicy.defaults());
    }
    @Override public synchronized TransferHandle start(TransferRequest request, SourceRefresher refresher, SourceRefreshPolicy policy) throws IOException {
        java.util.Objects.requireNonNull(refresher); java.util.Objects.requireNonNull(policy);
        return startRegistered(request, new RefreshRegistration(refresher, policy, new Semaphore(1)));
    }
    @Override public TransferHandle resume(UUID id, SourceRefresher refresher) throws IOException {
        return resume(id, Map.of(), refresher);
    }
    @Override public TransferHandle resume(UUID id, Map<String, String> headers, SourceRefresher refresher) throws IOException {
        return resume(id, headers, refresher, SourceRefreshPolicy.defaults());
    }
    @Override public synchronized TransferHandle resume(UUID id, Map<String, String> headers, SourceRefresher refresher, SourceRefreshPolicy policy) throws IOException {
        java.util.Objects.requireNonNull(refresher); java.util.Objects.requireNonNull(policy);
        RefreshRegistration previous = refreshers.get(id);
        return resumeRegistered(id, headers, new RefreshRegistration(refresher, policy,
                previous == null ? new Semaphore(1) : previous.gate()));
    }
    @Override public synchronized TransferHandle start(TransferRequest request) throws IOException {
        return startRegistered(request, null);
    }
    /** Persist the extraction identity atomically with the new download before launching any worker. */
    public synchronized TransferHandle startMedia(TransferRequest request, MediaOrigin origin, SourceRefresher refresher) throws IOException {
        return startRegistered(request, new RefreshRegistration(java.util.Objects.requireNonNull(refresher),
                SourceRefreshPolicy.defaults(), new Semaphore(1)), java.util.Objects.requireNonNull(origin));
    }
    public synchronized Optional<MediaOrigin> mediaOrigin(UUID id) throws IOException { return store.mediaOrigin(id); }
    /** After core remuxes a completed HLS download into a sibling file, the record follows it. */
    public synchronized void relocateCompletedStream(UUID id, Path target) throws IOException {
        if (active.containsKey(id)) throw new IllegalStateException("Download is active");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Remuxed file is missing");
        new TransferRequest(id, java.net.URI.create("https://localhost/"), target, Map.of(), TransferOptions.defaults()).requireSafeTarget();
        store.relocateCompletedStream(id, target);
    }
    public synchronized java.util.List<MediaJob> mediaJobs() throws IOException { return store.mediaJobs(); }
    public synchronized void saveMediaJob(MediaJob job) throws IOException { store.saveMediaJob(job); }
    public synchronized java.util.List<PlaylistJob> playlists() throws IOException { return store.playlists(); }
    public synchronized Optional<DownloadRecord> download(UUID id) throws IOException { return store.exists(id) ? Optional.of(store.get(id)) : Optional.empty(); }
    public synchronized java.util.List<PlaylistItem> playlistItems(UUID id) throws IOException { return store.playlistItems(id); }
    public synchronized void createPlaylist(PlaylistJob job, java.util.List<PlaylistItem> items) throws IOException { store.createPlaylist(job, items); }
    public synchronized void savePlaylist(PlaylistJob job) throws IOException { store.savePlaylist(job); }
    public synchronized void savePlaylistItem(PlaylistItem item) throws IOException { store.savePlaylistItem(item); }
    /** Coordinators exclude parent/playlist workers before entering. Direct worker admission is locked here. */
    public synchronized void removeStopped(UUID id, boolean deleteFiles) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        var media = store.mediaJobs(); var playlists = store.playlists(); var records = store.list();
        if (media.stream().anyMatch(job -> job.videoId().equals(id) || job.audioId().equals(id))) throw new IllegalStateException("Remove the paired parent");
        var roots = new java.util.HashSet<UUID>(); roots.add(id);
        if (playlists.stream().anyMatch(job -> job.id().equals(id))) {
            for (var item : store.playlistItems(id)) if (item.childId() != null) roots.add(item.childId());
        } else if (media.stream().noneMatch(job -> job.id().equals(id)) && records.stream().noneMatch(record -> record.request().id().equals(id)))
            throw new IllegalArgumentException("Unknown download");
        for (UUID root : roots) {
            if (media.stream().anyMatch(job -> job.videoId().equals(root) || job.audioId().equals(root))) throw new IllegalStateException("Remove the paired parent");
            if (!root.equals(id) && playlists.stream().anyMatch(job -> job.id().equals(root))) throw new IllegalStateException("Invalid nested playlist child");
        }
        var selectedMedia = media.stream().filter(job -> roots.contains(job.id())).toList();
        for (var job : selectedMedia) { roots.add(job.videoId()); roots.add(job.audioId()); }
        var selected = records.stream().filter(record -> roots.contains(record.request().id())).toList();
        for (var record : selected) {
            if (active.containsKey(record.request().id()) || !java.util.Set.of(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELED, DownloadState.COMPLETED).contains(record.state()))
                throw new IllegalStateException("Pause before deleting");
        }
        if (deleteFiles) {
            var protectedFiles = new java.util.HashSet<Path>();
            for (var record : records) if (!roots.contains(record.request().id())) {
                protectedFiles.add(record.partialPath().toAbsolutePath().normalize());
                if (record.state() == DownloadState.COMPLETED || record.publicationIntent() || active.containsKey(record.request().id()))
                    protectedFiles.add(record.request().target());
            }
            for (var job : media) if (!roots.contains(job.id())) {
                protectedFiles.add(job.videoPath()); protectedFiles.add(job.audioPath()); protectedFiles.add(job.stagePath());
                if (job.state() == DownloadState.COMPLETED || job.outputHash() != null || java.util.Set.of(DownloadState.DOWNLOADING, DownloadState.MERGING, DownloadState.VERIFYING).contains(job.state()))
                    protectedFiles.add(job.target());
            }
            try { RemovalFiles.delete(selected, selectedMedia, protectedFiles); }
            catch (DownloadException known) { throw known; }
            catch (IOException failure) { throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Could not delete job files", failure); }
        }
        var removed = store.removeStopped(id);
        for (UUID removedId : removed) { liveRequests.remove(removedId); refreshers.remove(removedId); }
    }
    /** Prepare both durable children without launching; parent recovery is possible after any subsequent crash. */
    public synchronized void prepareMediaJob(MediaJob job, java.util.List<TransferRequest> requests, java.util.List<MediaOrigin> origins) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        var partials = new java.util.ArrayList<Path>();
        try {
            for (var request : requests) {
                request.requireSafeTarget();
                partials.add(Files.createTempFile(job.workspace(), ".ajdm-", ".ajdmpart"));
            }
            store.createMediaJob(job, requests, partials, origins);
            for (var request : requests) liveRequests.put(request.id(), request);
        } catch (IOException | RuntimeException e) {
            for (var partial : partials) try { Files.deleteIfExists(partial); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    private TransferHandle startRegistered(TransferRequest request, RefreshRegistration registration) throws IOException {
        return startRegistered(request, registration, null);
    }
    private TransferHandle startRegistered(TransferRequest request, RefreshRegistration registration, MediaOrigin origin) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        if (active.containsKey(request.id()) || store.exists(request.id())) {
            throw new IllegalArgumentException("Download ID already exists; use resume");
        }
        request.requireSafeTarget();
        Files.createDirectories(request.target().getParent());
        Path partial = Files.createTempFile(request.target().getParent(), ".ajdm-", ".ajdmpart");
        store.create(request, partial, origin);
        liveRequests.put(request.id(), request);
        if (registration != null) refreshers.put(request.id(), registration);
        return launch(request, store.get(request.id()), false);
    }
    @Override public TransferHandle resume(UUID id) throws IOException { return resume(id, Map.of()); }
    @Override public synchronized TransferHandle resume(UUID id, ConflictPolicy policy) throws IOException {
        return resumeRegistered(id, Map.of(), null, java.util.Objects.requireNonNull(policy));
    }
    @Override public synchronized TransferHandle resume(UUID id, Map<String, String> headers) throws IOException {
        return resumeRegistered(id, headers, null);
    }
    /** Core callers may supply credentials and an explicit collision decision together. */
    public synchronized TransferHandle resume(UUID id, Map<String, String> headers, ConflictPolicy policy) throws IOException {
        return resumeRegistered(id, headers, null, policy);
    }
    public synchronized TransferHandle resumeMedia(UUID id, Map<String, String> headers, ConflictPolicy policy, SourceRefresher refresher) throws IOException {
        RefreshRegistration previous = refreshers.get(id);
        return resumeRegistered(id, headers, new RefreshRegistration(java.util.Objects.requireNonNull(refresher), SourceRefreshPolicy.defaults(),
                previous == null ? new Semaphore(1) : previous.gate()), policy);
    }
    /** Change stopped requests atomically before launching their next worker generation. */
    public synchronized void configureConnections(java.util.List<UUID> ids, int count) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        TransferOptions.defaults().withConnectionCount(count);
        var selected = java.util.List.copyOf(ids);
        for (UUID id : selected) {
            if (active.containsKey(id)) throw new IllegalStateException("Pause before configuring connections");
            var state = store.get(id).state();
            if (!java.util.Set.of(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED).contains(state)) throw new IllegalStateException("Download is not stopped");
        }
        store.connections(selected, count);
        for (UUID id : selected) liveRequests.computeIfPresent(id, (key, old) ->
                new TransferRequest(id, old.uri(), old.target(), old.headers(), old.options().withConnectionCount(count), old.conflictPolicy()));
    }
    /** Cancel an inactive persisted record without launching network or deleting its partial. */
    public synchronized void cancelInactive(UUID id) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        if (active.containsKey(id)) throw new IllegalStateException("Download is running");
        DownloadRecord saved = store.get(id);
        if (saved.state() == DownloadState.CANCELED) return;
        if (saved.state() == DownloadState.COMPLETED) throw new IllegalStateException("Download is completed");
        store.state(id, DownloadState.CANCELED, ErrorCode.CANCELED);
        liveRequests.remove(id); refreshers.remove(id);
    }
    private TransferHandle resumeRegistered(UUID id, Map<String, String> headers, RefreshRegistration registration) throws IOException {
        return resumeRegistered(id, headers, registration, null);
    }
    private TransferHandle resumeRegistered(UUID id, Map<String, String> headers, RefreshRegistration registration, ConflictPolicy policy) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        if (active.containsKey(id)) throw new IllegalStateException("Download is already running");
        DownloadRecord saved = store.get(id);
        if (saved.state() == DownloadState.COMPLETED || saved.state() == DownloadState.CANCELED) {
            throw new IllegalStateException("Download is terminal");
        }
        TransferRequest old = liveRequests.getOrDefault(id, saved.request());
        if (policy != null) {
            if (saved.publicationIntent() && !Files.exists(saved.partialPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Publication may already have completed; resume without changing policy first");
            }
            old = old.withConflictPolicy(policy);
            store.destination(id, old.target(), policy);
        }
        Map<String, String> merged = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(old.headers()); merged.putAll(headers);
        var request = new TransferRequest(id, old.uri(), old.target(), merged, old.options(), old.conflictPolicy());
        liveRequests.put(id, request);
        if (registration != null) refreshers.put(id, registration);
        return launch(request, saved, true);
    }
    @Override public java.util.List<DownloadRecord> downloads() throws IOException { return store.list(); }
    @Override public TransferHandle restart(UUID id) throws IOException { return restart(id, Map.of()); }
    @Override public synchronized TransferHandle restart(UUID id, Map<String, String> headers) throws IOException {
        if (closed) throw new IllegalStateException("Engine is closed");
        if (active.containsKey(id)) throw new IllegalStateException("Download is already running");
        DownloadRecord saved = store.get(id);
        if (saved.state() != DownloadState.PAUSED || saved.error() != ErrorCode.SOURCE_CHANGED) {
            throw new IllegalStateException("Only a source-change pause can be restarted");
        }
        TransferRequest old = liveRequests.getOrDefault(id, saved.request());
        Map<String, String> merged = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(old.headers()); merged.putAll(headers);
        var next = new TransferRequest(UUID.randomUUID(), old.uri(), old.target(), merged, old.options(), old.conflictPolicy());
        return startRegistered(next, refreshers.get(id), store.mediaOrigin(id).orElse(null));
    }
    public synchronized TransferHandle restartMedia(UUID id, Map<String, String> headers, SourceRefresher refresher) throws IOException {
        if (store.mediaOrigin(id).isEmpty()) throw new IllegalStateException("Download has no media origin");
        refreshers.put(id, new RefreshRegistration(java.util.Objects.requireNonNull(refresher), SourceRefreshPolicy.defaults(), new Semaphore(1)));
        return restart(id, headers);
    }
    private TransferHandle launch(TransferRequest request, DownloadRecord saved, boolean recovery) {
        var handle = new Handle(request, saved, recovery, refreshers.get(request.id()));
        if (active.putIfAbsent(request.id(), handle) != null) throw new IllegalArgumentException("Download ID is already active");
        Thread worker = Thread.ofVirtual().name("ajdm-transfer-" + request.id()).unstarted(() -> run(handle));
        handle.cancellation.worker(worker);
        worker.start();
        return handle;
    }

    private void run(Handle handle) {
        TransferResult result = null;
        DownloadException failure = null;
        try { result = transfer(handle); }
        catch (DownloadException e) { failure = e; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new DownloadException(ErrorCode.CANCELED, "Download canceled", e);
        } catch (IOException e) { failure = new DownloadException(ErrorCode.DISK_WRITE_ERROR, "File operation failed", e); }
        catch (RuntimeException e) { failure = new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Transfer could not complete", e); }
        if (handle.checkpointFailure != null) failure = handle.checkpointFailure;
        if (failure != null) {
            boolean stopping = handle.stopKind != StopKind.NONE && handle.checkpointFailure == null
                    && (handle.stopKind == StopKind.CANCEL || pauseReason(failure) == null)
                    && !(failure.code() == ErrorCode.DISK_WRITE_ERROR || failure.code() == ErrorCode.STATE_STORE_ERROR);
            if (stopping) failure = new DownloadException(handle.stopKind == StopKind.PAUSE ? ErrorCode.PAUSED : ErrorCode.CANCELED,
                    handle.stopKind == StopKind.PAUSE ? "Download paused" : "Download canceled");
            ErrorCode reason = pauseReason(failure);
            DownloadState state = reason != null ? DownloadState.PAUSED
                    : failure.code() == ErrorCode.CANCELED ? DownloadState.CANCELED : DownloadState.FAILED;
            try { store.state(handle.id(), state, failure.code() == ErrorCode.PAUSED ? null : reason == null ? failure.code() : reason); handle.state = state; }
            catch (IOException e) {
                failure = new DownloadException(ErrorCode.STATE_STORE_ERROR, "Could not save stopped download state", e);
                handle.state = DownloadState.FAILED;
            }
        }
        active.remove(handle.id(), handle);
        if (handle.state == DownloadState.COMPLETED || handle.state == DownloadState.CANCELED) {
            liveRequests.remove(handle.id()); refreshers.remove(handle.id());
        }
        if (failure == null) handle.done.complete(result);
        else {
            handle.done.completeExceptionally(failure);
        }
        if (handle.state == DownloadState.PAUSED) handle.paused.complete(null);
        else handle.paused.completeExceptionally(failure == null ? new IllegalStateException("Download is already complete") : failure);
    }

    private static ErrorCode pauseReason(DownloadException failure) {
        if (failure.code() == ErrorCode.ACCESS_DENIED && (failure.httpStatus() == 401 || failure.httpStatus() == 407)) return ErrorCode.AUTH_REQUIRED;
        return switch (failure.code()) {
            case PAUSED, SOURCE_CHANGED, DISK_WRITE_ERROR, INSUFFICIENT_DISK_SPACE, AUTH_REQUIRED -> failure.code();
            default -> null;
        };
    }

    private TransferResult transfer(Handle handle) throws IOException, InterruptedException {
        long started = System.nanoTime();
        TransferRequest request = handle.request;
        Path target = request.target();
        handle.cancellation.check();
        changeState(handle, DownloadState.PROBING);
        Path part = handle.saved.partialPath();
        handle.partial = part;
        if (!part.getParent().equals(target.getParent()) || Files.isSymbolicLink(part)) {
            throw new DownloadException(ErrorCode.STATE_STORE_ERROR, "Invalid stored partial-file location");
        }
        if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) {
            if (handle.recovery && handle.saved.readyToPublish() && handle.saved.publicationIntent()
                    && !Files.exists(part, LinkOption.NOFOLLOW_LINKS)) return recoverReady(handle, started);
            store.checkpoint(handle.id(), java.util.List.of(), DownloadState.PROBING); handle.durable.set(0); handle.written.set(0);
            throw new DownloadException(ErrorCode.PART_FILE_MISSING, "The partial file is missing; start a new download");
        }
        if (Files.size(part) < handle.saved.durableEnd()) {
            store.checkpoint(handle.id(), java.util.List.of(), DownloadState.PROBING); handle.durable.set(0); handle.written.set(0);
            throw new DownloadException(ErrorCode.PART_FILE_TRUNCATED, "The partial file lost saved bytes; start a new download");
        }
        if (handle.recovery && handle.saved.readyToPublish()) return recoverReady(handle, started);
        prepareDestination(handle);
        if (store.mediaOrigin(handle.id()).map(MediaOrigin::hls).orElse(false)) return stream(handle, part, started);
        try (var source = new SourceSession(handle)) {
            ProbeResult probe = source.initialProbe();
            handle.total = probe.contentLength();
            handle.cancellation.check();
            requireDiskSpace(handle, probe);
            java.util.List<ByteRange> saved = restoreCoverage(handle, probe);
            store.describe(handle.id(), probe);
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                // Retain disjoint durable ranges. Bytes in every gap are overwritten, never trusted.
                long end = saved.stream().mapToLong(ByteRange::endExclusive).max().orElse(0);
                onDisk(() -> { channel.truncate(end); channel.force(true); return null; });
                handle.written.set(new RangeSet(saved).coveredBytes());
                var options = request.options();
                var writer = new BoundedFileWriter(channel, disks, options.bufferBytes(), options.maxInflightBuffers(), handle.written, saved, buffers);
                handle.writer = writer;
                writer.preallocate(probe.contentLength());
                if (probe.contentLength() >= 0) observer.at(CheckpointObserver.Phase.AFTER_PREALLOCATION, handle.id(), handle.durable.get());
                changeState(handle, DownloadState.DOWNLOADING);
                var checkpoints = new Checkpoints(handle.id(), store, writer, handle.durable, options.checkpointInterval(),
                        checkpointTimers, observer, failure -> { handle.checkpointFailure = failure; handle.cancellation.cancel(); });
                handle.checkpoints = checkpoints;
                try {
                    // Without a validator, use one response so different versions cannot be spliced across windows.
                    for (;;) {
                        try {
                            if (probe.acceptRanges() && probe.validator() != null) ranged(source.transport, probe, writer, handle);
                            else single(source.transport, probe, writer, handle);
                            break;
                        } catch (DownloadException failure) {
                            boolean refresh = source.canRefresh(failure);
                            boolean rangeReprobe = failure.httpStatus() == 416;
                            boolean fileReprobe = !refresh && (failure.httpStatus() == 403 || failure.httpStatus() == 410);
                            if (!refresh && !rangeReprobe && !fileReprobe) throw failure;
                            if (rangeReprobe && source.rangeReprobes > 0) {
                                throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "The server repeatedly rejected a validated range", 416);
                            }
                            if (fileReprobe && source.fileReprobes > 0) throw failure;
                            // ranged() has joined every old worker. Force accepted writes before source replacement.
                            writer.drain(); handle.cancellation.check();
                            checkpoints.flush(DownloadState.DOWNLOADING);
                            probe = refresh ? source.refresh(failure, probe, handle.written.get())
                                    : source.reprobe(probe, handle.written.get(), rangeReprobe);
                            handle.total = probe.contentLength();
                            writer.preallocate(probe.contentLength());
                        }
                    }
                    writer.drain();
                    handle.cancellation.check();
                    checkpoints.stop();
                    if (handle.checkpointFailure != null) throw handle.checkpointFailure;
                    checkpoints.flush(DownloadState.VERIFYING); handle.state = DownloadState.VERIFYING;
                    long count = handle.written.get();
                    if (handle.total >= 0 && count != handle.total || channel.size() != count) {
                        throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Downloaded size does not match the source");
                    }
                    String hash = options.expectedSha256() == null ? null : onDisk(() -> sha256(channel, handle.cancellation));
                    if (hash != null && !hash.equals(options.expectedSha256())) {
                        throw new DownloadException(ErrorCode.CHECKSUM_MISMATCH, "Downloaded file failed SHA-256 verification");
                    }
                    if (handle.total < 0) {
                        store.describe(handle.id(), new ProbeResult(probe.finalUri(), count, false, probe.etag(), probe.lastModified(), probe.filename(), probe.contentType()));
                    }
                    store.ready(handle.id(), hash);
                    observer.at(CheckpointObserver.Phase.AFTER_READY_COMMIT, handle.id(), count);
                    handle.cancellation.check();
                    // Closing the file before publication is necessary on Windows.
                    return publishAfterClose(handle, channel, count, hash, started);
                } catch (IOException | InterruptedException failure) {
                    checkpoints.stop(); writer.drainUninterruptibly();
                    if (handle.checkpointFailure != null) throw handle.checkpointFailure;
                    if (failure instanceof DownloadException problem && (problem.code() == ErrorCode.CHECKSUM_MISMATCH
                            || problem.code() == ErrorCode.PROTOCOL_VIOLATION && problem.httpStatus() != 206 && problem.httpStatus() != 416)) {
                        checkpoints.invalidate(0, handle.state);
                    } else if (channel.isOpen() && !(failure instanceof DownloadException problem && (problem.code() == ErrorCode.DISK_WRITE_ERROR
                            || problem.code() == ErrorCode.STATE_STORE_ERROR))) {
                        DownloadState state = handle.stopKind == StopKind.PAUSE || failure instanceof DownloadException problem && pauseReason(problem) != null
                                ? DownloadState.PAUSED : handle.stopKind == StopKind.CANCEL ? DownloadState.CANCELED : DownloadState.FAILED;
                        ErrorCode reason = failure instanceof DownloadException problem ? pauseReason(problem) : null;
                        checkpoints.flush(state, reason == ErrorCode.PAUSED ? null : reason); handle.state = state;
                    }
                    throw failure;
                } finally { checkpoints.stop(); writer.drainUninterruptibly(); handle.writer = null; }
            }
        }
    }

    /**
     * An HLS media playlist: parts are joined into this download's own partial file (see {@link HlsDownload}), with the
     * joined prefix checkpointed as durable coverage; verification and publication then follow the file path.
     */
    private TransferResult stream(Handle handle, Path part, long started) throws IOException, InterruptedException {
        changeState(handle, DownloadState.DOWNLOADING);
        var request = handle.request;
        HlsDownload.Result result;
        // Expired or revoked links (playlist, part or key) are renewed through the media refresher, like a file's;
        // progress survives because it is keyed to the playlist's structure, not its links.
        for (;;) {
            try { result = streamOnce(handle, request, part); break; }
            catch (DownloadException failure) {
                int status = failure.httpStatus();
                if (handle.refresher == null || status != 401 && status != 403 && status != 404 && status != 410) throw failure;
                DownloadSource candidate;
                try (var session = new SourceSession(handle)) {
                    candidate = session.replacement(new DownloadSource(request.uri(), request.headers()), null, status);
                }
                store.replaceStreamSource(handle.id(), candidate);
                request = new TransferRequest(request.id(), candidate.uri(), request.target(), candidate.headers(), request.options(), request.conflictPolicy());
                liveRequests.put(handle.id(), request);
                EngineTrace.event("stream-refresh", -1, String.valueOf(status));
            }
        }
        return streamFinish(handle, request, result, started);
    }

    private HlsDownload.Result streamOnce(Handle handle, TransferRequest request, Path part) throws IOException, InterruptedException {
        var lastCheckpoint = new AtomicLong(System.nanoTime());
        HlsDownload.Result result;
        try (var transport = new JdkHttpTransport(request, connections)) {
            var download = new HlsDownload(request, transport, part, part.resolveSibling(part.getFileName() + ".hls"), handle.cancellation,
                    (done, total, joined, received) -> {
                        handle.written.set(joined); handle.received.set(received); handle.durable.set(joined);
                        long now = System.nanoTime();
                        if (now - lastCheckpoint.get() >= request.options().checkpointInterval().toNanos() || done == total) {
                            lastCheckpoint.set(now);
                            try { store.checkpoint(handle.id(), joined == 0 ? java.util.List.of() : java.util.List.of(new ByteRange(0, joined)), DownloadState.DOWNLOADING); }
                            catch (IOException e) { handle.checkpointFailure = new DownloadException(ErrorCode.STATE_STORE_ERROR, "Could not save stream progress", e); handle.cancellation.cancel(); }
                        }
                    });
            result = download.run();
        }
        if (handle.checkpointFailure != null) throw handle.checkpointFailure;
        return result;
    }

    private TransferResult streamFinish(Handle handle, TransferRequest request, HlsDownload.Result result, long started) throws IOException {
        long count = result.bytes();
        handle.total = count; handle.written.set(count); handle.durable.set(count);
        store.describe(handle.id(), new ProbeResult(request.uri(), count, false, null, null, request.target().getFileName().toString(), "video/mp2t"));
        store.checkpoint(handle.id(), java.util.List.of(new ByteRange(0, count)), DownloadState.VERIFYING);
        handle.state = DownloadState.VERIFYING;
        String expected = request.options().expectedSha256();
        if (expected != null && !expected.equals(result.sha256())) {
            throw new DownloadException(ErrorCode.CHECKSUM_MISMATCH, "Downloaded stream failed SHA-256 verification");
        }
        String hash = expected == null ? null : result.sha256();
        store.ready(handle.id(), hash);
        observer.at(CheckpointObserver.Phase.AFTER_READY_COMMIT, handle.id(), count);
        handle.cancellation.check();
        return publish(handle, count, hash, started, false, false);
    }

    private record RefreshRegistration(SourceRefresher provider, SourceRefreshPolicy policy, Semaphore gate) {}

    /** Owned by the coordinator: callbacks run only after an entire HTTP worker generation settles. */
    private final class SourceSession implements AutoCloseable {
        private final Handle handle;
        private DownloadSource current;
        private JdkHttpTransport transport;
        private int rangeReprobes, fileReprobes;
        SourceSession(Handle handle) {
            this.handle = handle; current = new DownloadSource(handle.request.uri(), handle.request.headers());
            transport = transportFor(current);
        }
        private TransferRequest requestFor(DownloadSource source) {
            return new TransferRequest(handle.id(), source.uri(), handle.target, source.headers(), handle.request.options(), handle.request.conflictPolicy());
        }
        private JdkHttpTransport transportFor(DownloadSource source) { return new JdkHttpTransport(requestFor(source), connections); }
        boolean canRefresh(DownloadException failure) {
            int status = failure.httpStatus();
            return handle.refresher != null && (status == 401 || status == 403 || status == 404 || status == 410);
        }
        ProbeResult initialProbe() throws IOException, InterruptedException {
            var warmups = prewarm();
            try { return probe(transport, current.uri(), handle); }
            catch (DownloadException failure) {
                if (canRefresh(failure)) return refresh(failure, handle.saved.probe(), handle.saved.durableBytes());
                if (failure.httpStatus() == 403 || failure.httpStatus() == 410) {
                    return reprobe(handle.saved.probe(), handle.saved.durableBytes(), false);
                }
                throw failure;
            } finally { awaitWarmups(warmups); }
        }
        /**
         * While the probe is in flight, open the other fixed-mode connections with 1-byte range requests so their TCP
         * and TLS handshakes finish before the first stream fills a bufferbloated mobile link: there a handshake behind
         * queued data took 2-7 s instead of ~1 s, and on a real 4G-class link warm-up won 6 of 9 paired rounds (about
         * 11% faster). Failures are ignored. {@link EngineLimits#withoutPrewarm()} or -Dajdm.prewarm=false disables it.
         */
        private java.util.List<Thread> prewarm() {
            var options = handle.request.options();
            if (!prewarm || options.adaptive() || options.connections() < 2) return java.util.List.of();
            var threads = new java.util.ArrayList<Thread>();
            var uri = current.uri(); var lane = transport; var scope = handle.cancellation;
            for (int i = 1; i < Math.min(options.connections(), maximumConnections); i++) {
                threads.add(Thread.ofVirtual().name("ajdm-prewarm-" + handle.id() + "-" + i).start(() -> {
                    try {
                        var response = lane.send(uri, "GET", Map.of("Range", "bytes=0-0"), scope);
                        // Drain only a genuine 1-byte answer so the connection returns to the pool; anything else is closed.
                        try (InputStream body = response.body()) {
                            if (response.statusCode() == 206) body.readNBytes(2);
                        }
                        finally { scope.unregister(response.body()); }
                    } catch (Exception ignored) { /* A warm-up is only a hint; the transfer opens its own connections. */ }
                }));
            }
            EngineTrace.event("prewarm", -1, String.valueOf(threads.size()));
            return threads;
        }
        private static void awaitWarmups(java.util.List<Thread> warmups) throws InterruptedException {
            long deadline = System.nanoTime() + 1_500_000_000L;
            for (Thread warmup : warmups) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !warmup.join(java.time.Duration.ofNanos(left))) break;
            }
            EngineTrace.event("prewarmed", -1, "");
        }
        ProbeResult reprobe(ProbeResult previous, long retainedBytes, boolean rangeError) throws IOException, InterruptedException {
            if (rangeError) rangeReprobes++; else fileReprobes++;
            ProbeResult checked = probe(transport, current.uri(), handle);
            if (previous != null) {
                if (!previous.finalUri().equals(checked.finalUri())) SourceIdentity.requireSame(previous, checked, retainedBytes);
                else {
                    boolean differentSize = previous.contentLength() >= 0 && checked.contentLength() >= 0
                            && previous.contentLength() != checked.contentLength();
                    boolean differentValidator = previous.validator() != null && checked.validator() != null
                            && !previous.validator().equals(checked.validator());
                    boolean unproven = retainedBytes > 0 && (previous.contentLength() < 0
                            || previous.contentLength() != checked.contentLength() || previous.validator() == null
                            || !previous.validator().equals(checked.validator()));
                    if (differentSize || differentValidator || unproven) {
                        throw new DownloadException(ErrorCode.SOURCE_CHANGED, "The source changed during re-probe");
                    }
                }
            }
            requireDiskSpace(handle, checked);
            store.describe(handle.id(), checked);
            observer.at(CheckpointObserver.Phase.AFTER_REPROBE_COMMIT, handle.id(), handle.durable.get());
            return checked;
        }
        ProbeResult refresh(DownloadException failure, ProbeResult previous, long retainedBytes) throws IOException, InterruptedException {
            DownloadSource failed = current;
            transport.close();
            for (;;) {
                handle.cancellation.check();
                DownloadSource candidate = replacement(failed, previous, failure.httpStatus());
                if (("https".equalsIgnoreCase(current.uri().getScheme())
                        || previous != null && "https".equalsIgnoreCase(previous.finalUri().getScheme()))
                        && !"https".equalsIgnoreCase(candidate.uri().getScheme())) {
                    throw new DownloadException(ErrorCode.INVALID_URL, "Source refresh cannot downgrade HTTPS");
                }
                var next = transportFor(candidate);
                boolean installed = false;
                try {
                    ProbeResult checked;
                    try { checked = probe(next, candidate.uri(), handle); }
                    catch (DownloadException problem) {
                        if (!canRefresh(problem)) throw problem;
                        failed = candidate; failure = problem; continue;
                    }
                    SourceIdentity.requireSame(previous, checked, retainedBytes);
                    handle.cancellation.check();
                    requireDiskSpace(handle, checked);
                    store.replaceSource(handle.id(), candidate, checked);
                    current = candidate; transport = next; installed = true;
                    liveRequests.put(handle.id(), requestFor(candidate));
                    observer.at(CheckpointObserver.Phase.AFTER_SOURCE_REFRESH_COMMIT, handle.id(), handle.durable.get());
                    handle.cancellation.check();
                    return checked;
                } finally { if (!installed) next.close(); }
            }
        }
        private DownloadSource replacement(DownloadSource failed, ProbeResult previous, int status) throws IOException, InterruptedException {
            RefreshRegistration registration = handle.refresher;
            if (handle.refreshAttempts >= registration.policy().maximumRefreshes()) {
                throw new DownloadException(ErrorCode.REFRESH_EXHAUSTED, "Source refresh limit reached");
            }
            int attempt = ++handle.refreshAttempts;
            long deadline = System.nanoTime() + registration.policy().timeout().toNanos();
            if (!registration.gate().tryAcquire(registration.policy().timeout().toNanos(), TimeUnit.NANOSECONDS)) {
                handle.cancellation.check();
                throw new DownloadException(ErrorCode.REFRESH_TIMEOUT, "Previous source refresh has not settled");
            }
            var scope = handle.cancellation.child();
            var context = new SourceRefreshContext(handle.id(), failed, previous, status, attempt, scope);
            var task = new FutureTask<DownloadSource>(() -> { scope.check(); return registration.provider().refresh(context); });
            Thread callback = Thread.ofVirtual().name("ajdm-source-refresh-" + handle.id()).unstarted(() -> {
                // Release only when caller code actually exits, even if Future.cancel has already completed.
                try { task.run(); } finally { registration.gate().release(); scope.detach(); }
            });
            scope.worker(callback);
            callback.start();
            try {
                handle.cancellation.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                DownloadSource result = task.get(remaining, TimeUnit.NANOSECONDS);
                handle.cancellation.check();
                if (result == null) throw new DownloadException(ErrorCode.REFRESH_FAILED, "Source refresher returned no source");
                return result;
            } catch (TimeoutException e) {
                handle.cancellation.check();
                throw new DownloadException(ErrorCode.REFRESH_TIMEOUT, "Source refresh timed out");
            } catch (ExecutionException | CancellationException e) {
                handle.cancellation.check();
                // Caller exceptions can contain signed URLs or auth values. Never surface their message or cause.
                throw new DownloadException(ErrorCode.REFRESH_FAILED, "Source refresher could not produce a source");
            } finally { scope.cancel(); task.cancel(true); }
        }
        @Override public void close() { transport.close(); }
    }

    private void requireDiskSpace(Handle handle, ProbeResult probe) throws IOException {
        onDisk(() -> {
            DiskSpaceGuard.require(diskSpace.usableBytes(handle.request.target().getParent()), probe.contentLength());
            return null;
        });
        handle.cancellation.check();
    }

    private java.util.List<ByteRange> restoreCoverage(Handle handle, ProbeResult probe) throws IOException {
        DownloadRecord saved = handle.saved;
        if (saved.durableBytes() > 0 && saved.resumeSupported()) {
            ProbeResult old = saved.probe();
            boolean same = old.contentLength() == probe.contentLength() && (old.etag() != null && !old.etag().startsWith("W/")
                    ? old.etag().equals(probe.etag()) : old.lastModified() != null && old.lastModified().equals(probe.lastModified()));
            if (!same) throw new DownloadException(ErrorCode.SOURCE_CHANGED, "The source changed since the saved checkpoint");
        }
        if (!saved.resumeSupported() || !probe.acceptRanges() || probe.validator() == null) {
            store.checkpoint(handle.id(), java.util.List.of(), DownloadState.PROBING); handle.durable.set(0);
            return java.util.List.of();
        }
        return saved.completed();
    }
    private TransferResult recoverReady(Handle handle, long started) throws IOException {
        long count = handle.saved.durableBytes();
        boolean moved = handle.saved.publicationIntent() && !Files.exists(handle.partial, LinkOption.NOFOLLOW_LINKS);
        Path verified = moved ? handle.target : handle.partial;
        if (!Files.isRegularFile(verified, LinkOption.NOFOLLOW_LINKS)) {
            throw new DownloadException(ErrorCode.PART_FILE_MISSING, "Verified publication file is missing");
        }
        if (Files.size(verified) != count) throw new DownloadException(ErrorCode.PART_FILE_TRUNCATED, "Verified file size changed during recovery");
        String hash;
        String proof = handle.saved.actualSha256();
        String expected = handle.request.options().expectedSha256();
        try (FileChannel channel = FileChannel.open(verified, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            hash = expected == null && proof == null ? null : onDisk(() -> sha256(channel, handle.cancellation));
        }
        if (hash != null && (expected != null && !hash.equals(expected) || proof != null && !hash.equals(proof))) {
            throw new DownloadException(ErrorCode.CHECKSUM_MISMATCH, "Verified file failed recovery verification");
        }
        handle.cancellation.check();
        changeState(handle, DownloadState.DOWNLOADING); changeState(handle, DownloadState.VERIFYING);
        handle.written.set(count);
        return publish(handle, count, hash, started, true, moved);
    }
    private void changeState(Handle handle, DownloadState state) throws IOException {
        store.state(handle.id(), state, null); handle.state = state;
    }

    private TransferResult publishAfterClose(Handle handle, FileChannel channel, long count, String hash, long started) throws IOException {
        channel.close();
        return publish(handle, count, hash, started, false, false);
    }
    private TransferResult publish(Handle handle, long count, String hash, long started, boolean recovery, boolean moved) throws IOException {
        Path part = handle.partial;
        synchronized (handle) {
            handle.cancellation.check();
            if (!(moved || recovery && Files.isRegularFile(handle.target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(handle.target, part))) {
                if (handle.request.conflictPolicy() == ConflictPolicy.OVERWRITE) hash = publishOverwrite(handle, part, hash);
                else {
                    for (;;) {
                        handle.cancellation.check();
                        try { publication.link(handle.target, part); break; }
                        catch (FileAlreadyExistsException e) {
                            if (handle.request.conflictPolicy() != ConflictPolicy.RENAME) {
                                throw new DownloadException(ErrorCode.TARGET_EXISTS, "Destination already exists", e);
                            }
                            chooseRename(handle);
                        } catch (UnsupportedOperationException e) {
                            throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Destination filesystem does not support safe publication", e);
                        }
                    }
                }
            }
            observer.at(CheckpointObserver.Phase.AFTER_FINAL_LINK, handle.id(), count);
            store.state(handle.id(), DownloadState.COMPLETED, null);
            handle.total = count;
            handle.durable.set(count);
            handle.state = DownloadState.COMPLETED;
            // A failed cleanup does not invalidate the completed final file.
            Path stage = part.resolveSibling(part.getFileName() + ".publish");
            try {
                if (Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(stage, part)) Files.delete(stage);
            } catch (IOException ignored) { /* Retain an owned redundant staging link. */ }
            try { Files.delete(part); } catch (IOException ignored) { /* Retain redundant partial file. */ }
        }
        return new TransferResult(handle.target, count, hash, Duration.ofNanos(System.nanoTime() - started));
    }

    private void prepareDestination(Handle handle) throws IOException {
        if (!Files.exists(handle.target, LinkOption.NOFOLLOW_LINKS)) return;
        switch (handle.request.conflictPolicy()) {
            case RENAME -> chooseRename(handle);
            case ASK -> throw new DownloadException(ErrorCode.TARGET_EXISTS, "Destination already exists");
            case OVERWRITE -> requireOverwriteTarget(handle.target);
        }
    }

    private void chooseRename(Handle handle) throws IOException {
        for (int number = ++handle.renameSuffix; number <= 10000; number = ++handle.renameSuffix) {
            Path candidate = handle.request.target().resolveSibling(SafeFilename.numbered(handle.request.target().getFileName().toString(), number));
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            store.destination(handle.id(), candidate, handle.request.conflictPolicy());
            handle.target = candidate;
            liveRequests.computeIfPresent(handle.id(), (id, request) -> request.withTarget(candidate));
            observer.at(CheckpointObserver.Phase.AFTER_DESTINATION_COMMIT, handle.id(), handle.durable.get());
            return;
        }
        throw new DownloadException(ErrorCode.TARGET_EXISTS, "No unused destination name is available");
    }

    private static void requireOverwriteTarget(Path target) throws DownloadException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new DownloadException(ErrorCode.TARGET_EXISTS, "Only a regular destination file may be replaced");
        }
    }

    private String publishOverwrite(Handle handle, Path part, String hash) throws IOException {
        requireOverwriteTarget(handle.target);
        Path stage = part.resolveSibling(part.getFileName() + ".publish");
        try { publication.link(stage, part); }
        catch (FileAlreadyExistsException e) {
            if (!Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(stage, part)) {
                throw new DownloadException(ErrorCode.TARGET_EXISTS, "Publication staging path is not owned by this download", e);
            }
        } catch (UnsupportedOperationException | IOException e) {
            // A provider may report unsupported links as IOException. Atomic replacement is still required.
            // Never reuse/delete an unrelated staging path, including a collision hidden by a provider error.
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new DownloadException(ErrorCode.TARGET_EXISTS, "Publication staging path is not owned by this download", e);
            }
            if (hash == null) {
                try (FileChannel channel = FileChannel.open(part, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    hash = onDisk(() -> sha256(channel, handle.cancellation));
                }
            }
            store.publicationIntent(handle.id(), hash);
            observer.at(CheckpointObserver.Phase.AFTER_PUBLICATION_INTENT, handle.id(), handle.durable.get());
            handle.cancellation.check(); requireOverwriteTarget(handle.target);
            publication.atomicReplace(part, handle.target);
            return hash;
        }
        observer.at(CheckpointObserver.Phase.AFTER_OVERWRITE_STAGE, handle.id(), handle.durable.get());
        handle.cancellation.check(); requireOverwriteTarget(handle.target);
        // Keep the original partial link through the database completion commit for crash reconciliation.
        publication.atomicReplace(stage, handle.target);
        return hash;
    }

    private ProbeResult probe(HttpTransport transport, java.net.URI uri, Handle handle) throws IOException, InterruptedException {
        int malformed = 0;
        for (int attempt = 0; ;) {
            handle.cancellation.check();
            try {
                EngineTrace.event("probe", -1, "");
                var result = new Probe(transport).inspect(uri, handle.cancellation);
                EngineTrace.event("probed", -1, String.valueOf(result.contentLength()));
                return result;
            }
            catch (IOException e) {
                if (e instanceof DownloadException problem && problem.code() == ErrorCode.PROTOCOL_VIOLATION && problem.httpStatus() == 206
                        && malformed++ == 0) continue;
                retry(e, attempt++, handle);
            }
        }
    }

    private static final double[] EQUAL_SPEEDS = {};
    /**
     * A parallel connection silent this long while most of its siblings receive is treated as stalled (see
     * {@link PeerStalls}). On a real mobile link 17 stalls in 37 downloads each held a lane for the full 15 s stall
     * timeout while the others kept flowing.
     */
    private static final Duration PEER_STALL_TIMEOUT = Duration.ofSeconds(5);
    private static final boolean PEER_STALL = !"false".equalsIgnoreCase(System.getProperty("ajdm.peerStall"));

    private void ranged(HttpTransport transport, ProbeResult probe, BoundedFileWriter writer, Handle handle)
            throws IOException, InterruptedException {
        var options = handle.request.options();
        int maximum = options.adaptive() ? Math.min(options.connections(), maximumConnections) : options.connections();
        String hostKey = options.adaptive() ? HostLearning.key(probe.finalUri()) : null;
        HostProfile hint = null;
        try { hint = store.hostProfile(hostKey, System.currentTimeMillis()).orElse(null); }
        catch (IOException ignored) { /* Performance hints must never fail a transfer. */ }
        int initial = options.adaptive() ? Math.min(maximum, hint == null ? options.initialConnections() : hint.connections()) : maximum;
        var scheduler = new SegmentScheduler(probe.contentLength(), writer.coverage(), maximum, options.minSegmentBytes(), initial);
        if (scheduler.workers() == 0) return;
        var adaptive = options.adaptive() ? new Adaptation(scheduler, handle, writer, initial, hostKey, hint, probe.finalUri()) : null;
        var generation = handle.cancellation.child();
        var workers = new java.util.ArrayList<Thread>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
            for (int owner = 0; owner < scheduler.workers(); owner++) {
                int workerId = owner;
                var scope = generation.child();
                var lane = adaptive != null && transport instanceof JdkHttpTransport jdk ? new TransferLane(jdk) : null;
                if (adaptive != null) adaptive.lanes[owner] = lane;
                Thread worker = Thread.ofVirtual().name("ajdm-range-" + handle.id() + "-" + owner).unstarted(() -> {
                    try {
                        SegmentScheduler.Lease lease;
                        long park = 0;
                        while (!scheduler.done()) {
                            scope.check();
                            // Fixed mode splits like a classic download manager: an idle worker takes the second part
                            // of the piece with the most remaining bytes instead of leaving one slow tail.
                            lease = scheduler.next(workerId, adaptive == null ? EQUAL_SPEEDS : adaptive.rates);
                            if (lease == null) {
                                if (adaptive == null) { EngineTrace.event("idle-exit", workerId, ""); break; }
                                scheduler.awaitSampleOrCompletion(100); continue;
                            }
                            if (adaptive != null && lease.stolen()) adaptive.record("tail-stolen", 0);
                            if (EngineTrace.enabled()) EngineTrace.event(lease.stolen() ? "steal" : "lease", workerId, lease.start() + "-" + lease.end());
                            var rejected = transferSegment(lane == null ? transport : lane, probe, writer, handle, scope, scheduler, lease, workerId, adaptive);
                            if (rejected == null) { scheduler.release(lease, workerId); park = 0; continue; }
                            // The host refused this extra connection while others keep working: its bytes went back to
                            // the queue. Probe again later with doubling waits in case the limit was temporary.
                            if (adaptive != null) adaptive.record("pushback-shed", 0);
                            EngineTrace.event("shed", workerId, String.valueOf(rejected.httpStatus()));
                            park = park == 0 ? Math.max(2000, rejected.retryAfter().toMillis()) : Math.min(60000, park * 2);
                            scheduler.awaitSampleOrCompletion(Math.min(60000, park));
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        generation.cancel();
                    } finally { if (lane != null) lane.close(); scope.detach(); }
                });
                scope.worker(worker); workers.add(worker); worker.start();
            }
            if (adaptive != null) {
                while (!scheduler.done() && failure.get() == null) {
                    handle.cancellation.check(); scheduler.awaitSampleOrCompletion(250); adaptive.sample();
                }
            }
            joinWorkers(workers);
        } finally {
            generation.cancel(); joinWorkers(workers); generation.detach();
            if (adaptive != null) adaptive.finish();
        }
        handle.cancellation.check();
        Throwable problem = failure.get();
        if (problem instanceof RangeRejected) {
            // Every old worker has stopped, and its write jobs settle before any reset/truncate.
            writer.drain(); handle.checkpoints.rewind(new RangeSet(writer.coverage()).prefixLength(), DownloadState.DOWNLOADING);
            single(transport, probe, writer, handle);
        } else if (problem instanceof IOException io) throw io;
        else if (problem instanceof InterruptedException interrupted) throw interrupted;
        else if (problem != null) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Range worker failed", problem);
        else if (adaptive != null) { adaptive.learn(); adaptive.finish(); }
    }

    /** Returns null when the lease is finished or no longer admitted, or the rejection that shed it back to the queue. */
    private RetryableResponseException transferSegment(HttpTransport transport, ProbeResult probe, BoundedFileWriter writer, Handle handle,
                                 CancellationScope scope, SegmentScheduler scheduler, SegmentScheduler.Lease lease, int owner, Adaptation adaptive)
            throws IOException, InterruptedException {
        while (scheduler.position(lease) < lease.end()) {
            if (adaptive != null && !scheduler.admitted(owner)) return null;
            long maximum = handle.request.options().windowBytes();
            if (adaptive != null) maximum = Math.min(maximum, adaptive.windowBytes(owner));
            ByteRange window = scheduler.beginWindow(lease, owner, maximum);
            long windowStart = window.start(), end = window.endExclusive();
            int malformedRanges = 0;
            for (int attempt = 0; ;) {
                scope.check();
                long from = scheduler.position(lease);
                // A steal may have shortened this window, including during a retry; never request bytes it no longer owns.
                end = scheduler.windowEnd(lease);
                if (from >= end) { settle(writer, scheduler, lease); scheduler.finishWindow(lease, owner); break; }
                HttpResponse<InputStream> response = null;
                int laneGeneration = transport instanceof TransferLane lane ? lane.generation() : -1;
                try {
                    if (EngineTrace.enabled()) EngineTrace.event("request", owner, from + "-" + end);
                    response = transport.send(probe.finalUri(), "GET", Map.of("Range", "bytes=" + from + "-" + (end - 1),
                            "If-Range", probe.validator()), scope);
                    if (EngineTrace.enabled()) EngineTrace.event("headers", owner, String.valueOf(response.statusCode()));
                    try (InputStream body = response.body()) {
                        ResponseChecks.status(response); ResponseChecks.identity(response); ResponseChecks.unchanged(response, probe);
                        if (response.statusCode() == 200) {
                            if (!echoesValidator(response, probe)) throw new DownloadException(ErrorCode.SOURCE_CHANGED,
                                    "Server stopped confirming the source representation");
                            throw new RangeRejected();
                        }
                        try { ResponseChecks.range(response, from, end, probe.contentLength()); }
                        catch (DownloadException invalidRange) {
                            // No bytes from this invalid response are accepted. Rewind the entire owned window,
                            // including bytes acknowledged from an earlier short response, before one retry.
                            writer.drain();
                            handle.checkpoints.discardRange(windowStart, end, DownloadState.DOWNLOADING);
                            scheduler.rewind(lease, windowStart);
                            if (malformedRanges++ == 0) continue;
                            throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "The server repeatedly sent an invalid range", 206);
                        }
                        receive(body, end - from, from, writer, handle, scope, scheduler, lease, owner);
                    }
                } catch (RangeRejected e) { throw e; }
                catch (InterruptedException e) {
                    scope.check();
                    if (transport instanceof TransferLane lane && lane.generation() != laneGeneration) {
                        Thread.interrupted(); writer.drain();
                        if (scheduler.position(lease) == end) {
                            handle.checkpoints.discardRange(windowStart, end, DownloadState.DOWNLOADING);
                            scheduler.rewind(lease, windowStart);
                        }
                        continue;
                    }
                    throw e;
                }
                catch (IOException e) {
                    if (adaptive != null && !(transport instanceof TransferLane lane && lane.generation() != laneGeneration)) {
                        Throwable cause = e;
                        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                            if (cause instanceof java.net.SocketException) { adaptive.connectionReset(); break; }
                        }
                    }
                    if (adaptive != null && e instanceof RetryableResponseException responseFailure
                            && (responseFailure.httpStatus() == 429 || responseFailure.httpStatus() == 503)) adaptive.pushbacks.incrementAndGet();
                    writer.drain();
                    if (scheduler.position(lease) == end && !(e instanceof DownloadException)) {
                        handle.checkpoints.discardRange(windowStart, end, DownloadState.DOWNLOADING);
                        scheduler.rewind(lease, windowStart);
                    }
                    if (!(e instanceof DownloadException) && transport instanceof TransferLane lane && lane.generation() != laneGeneration) {
                        scope.check(); continue;
                    }
                    if (e instanceof RetryableResponseException rejected && (rejected.httpStatus() == 429 || rejected.httpStatus() == 503)
                            && scheduler.shed(lease, owner)) return rejected;
                    retry(e, attempt++, handle, scope);
                    continue;
                } finally { if (response != null) scope.unregister(response.body()); }
                settle(writer, scheduler, lease); scheduler.finishWindow(lease, owner);
                break;
            }
        }
        return null;
    }
    /** Wait for this lease's own writes; a disk failure in the shared queue still surfaces promptly. */
    private static void settle(BoundedFileWriter writer, SegmentScheduler scheduler, SegmentScheduler.Lease lease)
            throws InterruptedException, DownloadException {
        while (!scheduler.awaitSettled(lease, 50)) writer.checkHealthy();
        writer.checkHealthy();
    }
    private static void joinWorkers(java.util.List<Thread> workers) {
        boolean interrupted = Thread.interrupted();
        for (Thread worker : workers) {
            for (;;) {
                try { worker.join(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static final class RangeRejected extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;
    }

    private void single(HttpTransport transport, ProbeResult probe, BoundedFileWriter writer, Handle handle)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            handle.cancellation.check();
            HttpResponse<InputStream> response = null;
            try {
                response = transport.send(probe.finalUri(), "GET", Map.of(), handle.cancellation);
                try (InputStream body = response.body()) {
                    ResponseChecks.status(response); ResponseChecks.identity(response); ResponseChecks.unchanged(response, probe);
                    if (response.statusCode() != 200) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Unrequested partial response");
                    if (handle.written.get() > 0 && !echoesValidator(response, probe)) {
                        throw new DownloadException(ErrorCode.SOURCE_CHANGED, "The full response cannot confirm retained bytes");
                    }
                    long length = ResponseChecks.contentLength(response);
                    if (probe.contentLength() >= 0 && length >= 0 && length != probe.contentLength()) {
                        throw new DownloadException(ErrorCode.SOURCE_CHANGED, "The source size changed");
                    }
                    long expected = probe.contentLength() >= 0 ? probe.contentLength() : length;
                    handle.total = expected;
                    if (probe.contentLength() < 0 && expected >= 0) {
                        requireDiskSpace(handle, new ProbeResult(probe.finalUri(), expected, false, probe.etag(), probe.lastModified()));
                        writer.preallocate(expected);
                    }
                    receive(body, expected, 0, writer, handle);
                }
                writer.drain();
                return;
            } catch (IOException e) {
                writer.drain();
                retry(e, attempt, handle);
                handle.checkpoints.rewind(0, DownloadState.DOWNLOADING);
            } finally { if (response != null) handle.cancellation.unregister(response.body()); }
        }
    }

    private void receive(InputStream body, long expected, long offset, BoundedFileWriter writer, Handle handle)
            throws IOException, InterruptedException {
        receive(body, expected, offset, writer, handle, handle.cancellation, null, null, -1);
    }
    private void receive(InputStream body, long expected, long offset, BoundedFileWriter writer, Handle handle,
                         CancellationScope scope, SegmentScheduler scheduler, SegmentScheduler.Lease lease, int owner)
            throws IOException, InterruptedException {
        long received = 0;
        boolean cut = false;
        var stall = handle.request.options().stallTimeout();
        try (var guard = scheduler == null || !PEER_STALL ? new StallGuard(body, stall, watchdogs)
                : new StallGuard(body, stall, watchdogs, handle.peerStalls)) {
            while (expected < 0 || received < expected) {
                scope.check();
                if (handle.checkpointFailure != null) throw handle.checkpointFailure;
                // Another worker may have taken the rest of this window; abandon the unread response body.
                long limit = scheduler == null ? -1 : scheduler.windowEnd(lease) - offset;
                if (limit >= 0 && received >= limit) { cut = received < expected; break; }
                byte[] buffer = writer.acquire();
                boolean submitted = false;
                try {
                    long wanted = expected < 0 ? buffer.length : expected - received;
                    if (limit >= 0) wanted = Math.min(wanted, limit - received);
                    int capacity = (int) Math.min(buffer.length, wanted);
                    int count = guard.read(buffer, capacity);
                    if (count < 0) {
                        if (expected >= 0) throw new EOFException("Response ended before the requested bytes arrived");
                        break;
                    }
                    if (count == 0) continue;
                    handle.received.addAndGet(count);
                    scope.check();
                    if (scheduler == null) writer.submit(buffer, count, offset + received);
                    else {
                        // A steal between read and reserve leaves fewer owned bytes; the excess is discarded.
                        var ticket = scheduler.reserveUpTo(lease, owner, offset + received, count);
                        if (ticket == null) { cut = true; break; }
                        count = (int) (ticket.length());
                        writer.submit(buffer, count, offset + received, () -> scheduler.acknowledge(ticket));
                    }
                    submitted = true;
                    received += count;
                } finally { if (!submitted) writer.release(buffer); }
            }
            if (!cut && expected >= 0 && guard.readOne() != -1) {
                throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Response exceeded its requested range");
            }
        }
    }

    private static boolean echoesValidator(HttpResponse<?> response, ProbeResult probe) {
        String name = probe.etag() != null && !probe.etag().startsWith("W/") ? "ETag" : "Last-Modified";
        return probe.validator().equals(response.headers().firstValue(name).orElse(null));
    }
    private void retry(IOException failure, int attempt, Handle handle) throws IOException, InterruptedException {
        retry(failure, attempt, handle, handle.cancellation);
    }
    private void retry(IOException failure, int attempt, Handle handle, CancellationScope scope) throws IOException, InterruptedException {
        scope.check();
        if (failure instanceof DownloadException) throw failure;
        if (RetryFailures.certificateFailure(failure)) throw new DownloadException(ErrorCode.TLS_VERIFICATION_FAILED, "TLS validation failed", failure);
        var options = handle.request.options();
        if (attempt >= options.maxRetries()) throw new DownloadException(ErrorCode.RETRIES_EXHAUSTED, "Transfer retry limit reached", failure);
        long base = options.retryBaseDelay().toMillis(), cap = options.retryMaxDelay().toMillis();
        long maximum = Math.min(cap, base > (Long.MAX_VALUE >> Math.min(attempt, 30)) ? cap : base << Math.min(attempt, 30));
        long delay = maximum > 0 ? ThreadLocalRandom.current().nextLong(maximum + 1) : 0;
        if (failure instanceof RetryableResponseException response) delay = Math.max(delay, response.retryAfter().toMillis());
        if (EngineTrace.enabled()) EngineTrace.event("retry", -1, "attempt=" + attempt + " delay=" + delay + "ms "
                + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()).replace('\n', ' '));
        Thread.sleep(delay);
    }

    private <T> T onDisk(Callable<T> operation) throws IOException {
        Future<T> job = disks.submit(operation);
        boolean interrupted = Thread.interrupted();
        try {
            for (;;) {
                try { return job.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException io) throw io;
                    throw new IOException("Disk operation failed", e.getCause());
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static String sha256(FileChannel channel, CancellationScope cancellation) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            long offset = 0;
            while (true) {
                cancellation.check();
                int count = channel.read(buffer, offset);
                if (count < 0) break;
                buffer.flip(); digest.update(buffer); buffer.clear(); offset += count;
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    @Override public void close() {
        Handle[] handles;
        synchronized (this) {
            if (closed) return;
            closed = true;
            handles = active.values().toArray(Handle[]::new);
        }
        for (Handle handle : handles) handle.pause();
        boolean interrupted = Thread.interrupted();
        for (Handle handle : handles) {
            for (;;) {
                try { handle.done.get(); break; }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { break; }
            }
        }
        checkpointTimers.shutdownNow(); watchdogs.shutdownNow(); disks.shutdown();
        checkpointTimers.close(); watchdogs.close(); disks.close();
        liveRequests.clear(); refreshers.clear();
        try { store.close(); } catch (IOException e) { throw new UncheckedIOException("Could not close AJDM state", e); }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private enum StopKind { NONE, PAUSE, CANCEL }
    private final class Adaptation {
        private final SegmentScheduler scheduler;
        private final Handle handle;
        private final BoundedFileWriter writer;
        private final AdaptiveController controller;
        private final long start = System.nanoTime(), initialBytes;
        private long sampledAt = start;
        private long[] lastAcknowledged;
        private volatile double[] rates;
        private final java.util.concurrent.atomic.AtomicInteger pushbacks = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.ArrayDeque<AdaptationEvent> events = new java.util.ArrayDeque<>();
        private volatile int target;
        private final TransferLane[] lanes;
        private final long[] slowSince, stalledSince, lastSplit, lastRecycle;
        private final java.util.ArrayDeque<Long> resets = new java.util.ArrayDeque<>();
        private final String hostKey;
        private final java.net.URI origin;
        private int learnedTarget;
        private double learnedRate;
        private long stableSince;

        Adaptation(SegmentScheduler scheduler, Handle handle, BoundedFileWriter writer, int initial, String hostKey, HostProfile hint, java.net.URI origin) {
            this.scheduler = scheduler; this.handle = handle; this.writer = writer;
            initialBytes = handle.written.get(); rates = new double[scheduler.workers()]; lastAcknowledged = new long[rates.length];
            controller = new AdaptiveController(scheduler.workers(), Math.min(initial, scheduler.workers()), start, hint == null ? 0 : hint.bytesPerSecond());
            target = controller.target(); this.hostKey = hostKey; this.origin = origin; learnedTarget = target; stableSince = start;
            lanes = new TransferLane[rates.length]; slowSince = new long[rates.length]; stalledSince = new long[rates.length];
            lastSplit = new long[rates.length]; lastRecycle = new long[rates.length];
            scheduler.target(target); record(hint == null ? "start" : "host-hint-start", 0); publish(scheduler.measurement());
        }
        long windowBytes(int owner) {
            double rate = rates[owner];
            // Tiny requests waste round trips on slow links. Respect a caller's smaller cap and
            // protect the whole active window while stealing only its unrequested tail.
            long maximum = handle.request.options().windowBytes();
            long minimum = Math.min(maximum, 1024 * 1024);
            return (long) Math.max(minimum, Math.min(maximum, rate > 0 ? rate * 2 : minimum));
        }
        void sample() {
            long now = System.nanoTime();
            var measurement = scheduler.measurement(); var next = rates.clone();
            for (int owner = 0; owner < next.length; owner++) {
                double instant = (measurement.acknowledged()[owner] - lastAcknowledged[owner]) * 1e9 / (now - sampledAt);
                if (measurement.busy()[owner] || instant > 0) next[owner] = next[owner] == 0 ? instant : next[owner] * .65 + instant * .35;
            }
            rates = next; lastAcknowledged = measurement.acknowledged(); sampledAt = now;
            var options = handle.request.options();
            boolean pressure = measurement.queuedBytes() >= (long) options.bufferBytes() * options.maxInflightBuffers() * .75;
            controller.lanes(rates, measurement.busy());
            var decision = controller.observe(now, Math.max(0, handle.written.get() - initialBytes),
                    Math.max(0, handle.total - handle.written.get()), pressure, pushbacks.getAndSet(0), scheduler.canSplit());
            if (decision != null) {
                int previous = target;
                target = decision.target(); scheduler.target(target); record(decision.reason(), decision.usefulBytesPerSecond());
                if (previous != target) stableSince = now;
                if (decision.reason().equals("trial-accepted")) { learnedTarget = target; learnedRate = decision.usefulBytesPerSecond(); }
                if (decision.reason().equals("host-hint-revalidate") || decision.reason().equals("network-change")) { learnedRate = 0; stableSince = now; }
            }
            if (!pressure) laggards(now, measurement);
            if (!pressure && controller.settled() && now - stableSince >= 3_000_000_000L) {
                int busyCount = 0; double aggregate = 0;
                for (int owner = 0; owner < rates.length; owner++) if (measurement.busy()[owner]) { busyCount++; aggregate += rates[owner]; }
                if (busyCount >= target && aggregate > 0) { learnedTarget = target; learnedRate = aggregate; }
            }
            publish(measurement);
        }
        synchronized void connectionReset() {
            long now = System.nanoTime();
            while (!resets.isEmpty() && now - resets.getFirst() > 10_000_000_000L) resets.removeFirst();
            resets.addLast(now);
            if (resets.size() >= 3) { resets.clear(); pushbacks.incrementAndGet(); }
        }
        private void laggards(long now, SegmentScheduler.Measurement measurement) {
            double[] busyRates = java.util.stream.IntStream.range(0, rates.length).filter(i -> measurement.busy()[i]).mapToDouble(i -> rates[i]).sorted().toArray();
            if (busyRates.length < 2 || busyRates[busyRates.length / 2] <= 0) {
                java.util.Arrays.fill(slowSince, 0); java.util.Arrays.fill(stalledSince, 0); return;
            }
            double median = busyRates[busyRates.length / 2];
            for (int owner = 0; owner < rates.length; owner++) {
                boolean slow = measurement.busy()[owner] && rates[owner] < .35 * median;
                boolean stalled = measurement.busy()[owner] && rates[owner] < .10 * median;
                slowSince[owner] = slow ? (slowSince[owner] == 0 ? now : slowSince[owner]) : 0;
                stalledSince[owner] = stalled ? (stalledSince[owner] == 0 ? now : stalledSince[owner]) : 0;
                if (slow && now - slowSince[owner] >= 3_000_000_000L && (lastSplit[owner] == 0 || now - lastSplit[owner] >= 20_000_000_000L) && scheduler.splitLaggard(owner)) {
                    lastSplit[owner] = now; record("laggard-tail-split", rates[owner]);
                }
                if (stalled && now - stalledSince[owner] >= 5_000_000_000L && (lastRecycle[owner] == 0 || now - lastRecycle[owner] >= 20_000_000_000L) && lanes[owner] != null && lanes[owner].recycle()) {
                    lastRecycle[owner] = now; stalledSince[owner] = slowSince[owner] = 0;
                    record("laggard-pool-recycled", rates[owner]);
                }
            }
        }
        void learn() {
            if (hostKey == null || System.nanoTime() - start < 8_000_000_000L || learnedRate <= 0
                    || handle.written.get() - initialBytes < 1024 * 1024) return;
            if (!hostKey.equals(HostLearning.key(origin))) return;
            try { store.saveHostProfile(hostKey, new HostProfile(learnedTarget, learnedRate, System.currentTimeMillis())); record("host-hint-saved", learnedRate); }
            catch (IOException ignored) { record("host-hint-save-skipped", 0); }
        }
        synchronized void record(String reason, double rate) {
            if (events.size() == 256) events.removeFirst();
            events.addLast(new AdaptationEvent((System.nanoTime() - start) / 1_000_000, target, reason, rate));
        }
        private synchronized void publish(SegmentScheduler.Measurement measurement) {
            int busy = 0; for (boolean value : measurement.busy()) if (value) busy++;
            handle.adaptation = new AdaptationSnapshot(target, busy, measurement.queuedBytes(),
                    java.util.Arrays.stream(rates).boxed().toList(), java.util.List.copyOf(events));
        }
        synchronized void finish() {
            handle.adaptation = new AdaptationSnapshot(target, 0, 0, java.util.Arrays.stream(rates).boxed().toList(), java.util.List.copyOf(events));
        }
    }
    private static final class Handle implements TransferHandle {
        private final TransferRequest request;
        private volatile Path target;
        private int renameSuffix;
        private final RefreshRegistration refresher;
        private int refreshAttempts;
        private final CancellationScope cancellation = new CancellationScope();
        private final CompletableFuture<TransferResult> done = new CompletableFuture<>();
        private final CompletableFuture<Void> paused = new CompletableFuture<>();
        private final DownloadRecord saved;
        private final boolean recovery;
        private final AtomicLong received = new AtomicLong(), written = new AtomicLong(), durable = new AtomicLong();
        /** This download's open response bodies, so an individually silent connection can be abandoned early. */
        private final PeerStalls peerStalls = new PeerStalls(PEER_STALL_TIMEOUT);
        private volatile AdaptationSnapshot adaptation = AdaptationSnapshot.empty();
        @Override public AdaptationSnapshot adaptation() { return adaptation; }
        private volatile DownloadState state = DownloadState.QUEUED;
        private volatile long total = -1;
        private volatile Path partial;
        private volatile StopKind stopKind = StopKind.NONE;
        private volatile DownloadException checkpointFailure;
        private volatile Checkpoints checkpoints;
        private volatile BoundedFileWriter writer;
        @Override public long queuedWriteBytes() {
            BoundedFileWriter current = writer; return current == null ? 0 : current.queuedBytes();
        }
        private Handle(TransferRequest request, DownloadRecord saved, boolean recovery, RefreshRegistration refresher) {
            this.refresher = refresher; this.request = request; this.saved = saved; this.recovery = recovery;
            target = request.target();
            written.set(saved.durableBytes()); durable.set(saved.durableBytes());
            partial = saved.partialPath(); total = saved.probe() == null ? -1 : saved.probe().contentLength();
        }
        @Override public UUID id() { return request.id(); }
        @Override public ProgressSnapshot progress() {
            for (;;) {
                DownloadState before = state;
                long flushed = durable.get(), accepted = written.get(), arrived = received.get(), length = total;
                if (before == state) return new ProgressSnapshot(before, arrived, accepted, flushed, length);
            }
        }
        @Override public CompletionStage<TransferResult> completion() { return done.minimalCompletionStage(); }
        @Override public Optional<Path> partialPath() { return Optional.ofNullable(partial); }
        @Override public synchronized CompletionStage<Void> pause() {
            if (!done.isDone() && state != DownloadState.COMPLETED && stopKind != StopKind.CANCEL) {
                stopKind = StopKind.PAUSE; cancellation.cancel();
            }
            return paused.minimalCompletionStage();
        }
        @Override public synchronized void cancel() {
            if (!done.isDone() && state != DownloadState.COMPLETED) { stopKind = StopKind.CANCEL; cancellation.cancel(); }
        }
    }
}
