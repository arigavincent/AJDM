package io.ajdm.core;

import io.ajdm.engine.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Durable parent orchestration; workers never acquire the core monitor that controls wait on. */
final class MediaJobs implements AutoCloseable {
    private final AjdmEngine engine;
    private final MediaMuxer muxer;
    private final Function<MediaOrigin, SourceRefresher> refreshers;
    private final MediaJobObserver observer;
    private final ConcurrentMap<UUID, Run> running = new ConcurrentHashMap<>();
    private volatile boolean closed;
    MediaJobs(AjdmEngine engine, MediaMuxer muxer, Function<MediaOrigin, SourceRefresher> refreshers, MediaJobObserver observer) {
        this.engine = engine; this.muxer = muxer; this.refreshers = refreshers; this.observer = observer;
    }
    int activeTransfers() { return running.values().stream().mapToInt(r -> (int) r.children.stream().filter(h -> !h.completion().toCompletableFuture().isDone()).count()).sum(); }
    int requiredTransfers(MediaJob job) throws IOException {
        if (job.outputHash() != null) return 0;
        var records = records(); int count = 0;
        for (UUID child : List.of(job.videoId(), job.audioId())) if (records.get(child).state() != DownloadState.COMPLETED) count++;
        return count;
    }
    int activeJobs() { return running.size(); }
    boolean isRunning(UUID id) { return running.containsKey(id); }
    List<MediaJob> saved() throws IOException { return engine.mediaJobs(); }
    MediaJob find(UUID id) throws IOException { return saved().stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown media job")); }
    void start(MediaJob job, List<TransferRequest> requests, List<MediaOrigin> origins) throws IOException {
        requireSpace(job.target().getParent(), job.estimatedBytes(), 2);
        if (job.conflict() == ConflictPolicy.ASK && Files.exists(job.target(), LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.TARGET_EXISTS, "Destination exists");
        Files.createDirectory(job.workspace());
        try {
            if (Files.getFileStore(job.workspace()).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(job.workspace(), PosixFilePermissions.fromString("rwx------"));
            engine.prepareMediaJob(job, requests, origins);
        } catch (IOException | RuntimeException e) {
            try { Files.delete(job.workspace()); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        observer.reached(job, MediaJobObserver.Boundary.PREPARED);
        resume(job.id(), Map.of(), null);
    }
    void resume(UUID id, Map<String, String> headers, ConflictPolicy policy) throws IOException {
        resume(id, headers, policy, null);
    }
    void resume(UUID id, Map<String, String> headers, ConflictPolicy policy, Integer connections) throws IOException {
        if (closed) throw new IllegalStateException("Media jobs closed");
        if (running.containsKey(id)) { if (connections != null) throw new IllegalStateException("Pause before configuring connections"); return; }
        MediaJob job = find(id);
        if (job.state() == DownloadState.COMPLETED || job.state() == DownloadState.CANCELED) throw new IllegalStateException("Terminal media job");
        workspace(job);
        if (policy != null) job = job.destination(job.target(), policy);
        var run = new Run(job);
        if (running.putIfAbsent(id, run) != null) return;
        try {
            if (connections != null) {
                var saved = records();
                engine.configureConnections(List.of(job.videoId(), job.audioId()).stream().filter(child -> saved.get(child).state() != DownloadState.COMPLETED).toList(), connections);
            }
            // Completed children are reused without opening a network request.
            if (job.outputHash() == null) {
                Map<UUID, DownloadRecord> records = records();
                for (UUID child : List.of(job.videoId(), job.audioId())) {
                    if (records.get(child).state() == DownloadState.COMPLETED) continue;
                    MediaOrigin origin = engine.mediaOrigin(child).orElseThrow(() -> new IllegalStateException("Missing media origin"));
                    run.children.add(engine.resumeMedia(child, headers, null, refreshers.apply(origin)));
                }
            }
            run.job = job.status(job.outputHash() == null ? DownloadState.DOWNLOADING : DownloadState.VERIFYING, null);
            engine.saveMediaJob(run.job);
            run.worker = Thread.ofVirtual().name("ajdm-media-job-" + id).unstarted(() -> execute(run)); run.worker.start();
        } catch (IOException | RuntimeException e) {
            stopChildren(run, false); running.remove(id, run);
            engine.saveMediaJob(job.status(DownloadState.PAUSED, e instanceof DownloadException d ? d.code() : ErrorCode.STATE_STORE_ERROR)); throw e;
        }
    }
    private Map<UUID, DownloadRecord> records() throws IOException {
        var records = new HashMap<UUID, DownloadRecord>(); for (var record : engine.downloads()) records.put(record.request().id(), record); return records;
    }
    CoreService.Row row(MediaJob job) throws IOException {
        Run run = running.get(job.id()); Map<UUID, DownloadRecord> records = records(); long written = 0, durable = 0, total = 0; boolean unknown = false, resumable = true;
        for (UUID id : List.of(job.videoId(), job.audioId())) {
            DownloadRecord record = records.get(id);
            if (record.state() != DownloadState.COMPLETED) resumable &= record.resumeSupported();
            TransferHandle handle = run == null ? null : run.children.stream().filter(h -> h.id().equals(id)).findFirst().orElse(null);
            written += handle == null ? record.durableBytes() : handle.progress().writtenBytes();
            durable += handle == null ? record.durableBytes() : handle.progress().durableBytes();
            long size = record.probe() == null ? -1 : record.probe().contentLength(); if (size < 0) unknown = true; else total += size;
        }
        DownloadState state = job.state();
        if (run == null && Set.of(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.MERGING, DownloadState.VERIFYING).contains(state)) state = DownloadState.PAUSED;
        return new CoreService.Row(job.id(), state, written, durable, unknown ? job.estimatedBytes() : total, job.target().toString(), job.outputHash() != null || resumable, job.error());
    }
    private void execute(Run run) {
        try {
            while (run.children.stream().anyMatch(h -> !h.completion().toCompletableFuture().isDone())) {
                check(run);
                for (var child : run.children) if (child.completion().toCompletableFuture().isCompletedExceptionally()) child.completion().toCompletableFuture().get();
                Thread.sleep(20);
            }
            for (var child : run.children) child.completion().toCompletableFuture().get();
            check(run); MediaJob job = run.job; workspace(job);
            if (job.outputHash() == null) {
                var records = records();
                for (UUID child : List.of(job.videoId(), job.audioId())) {
                    var saved = records.get(child);
                    if (saved.state() != DownloadState.COMPLETED || saved.probe() == null || Files.size(saved.request().target()) != (saved.probe().contentLength() >= 0 ? saved.probe().contentLength() : saved.durableBytes())) throw new DownloadException(ErrorCode.PART_FILE_TRUNCATED, "Completed media stream changed");
                }
                String video = hash(job.videoPath(), run), audio = hash(job.audioPath(), run);
                if (job.videoHash() != null && (!job.videoHash().equals(video) || !job.audioHash().equals(audio))) throw new DownloadException(ErrorCode.CHECKSUM_MISMATCH, "Media input changed");
                job = job.hashes(video, audio, null, -1).status(DownloadState.MERGING, null); save(run, job);
                observer.reached(job, MediaJobObserver.Boundary.MERGING);
                requireSpace(job.workspace(), Math.addExact(Files.size(job.videoPath()), Files.size(job.audioPath())), 1);
                if (Files.exists(job.stagePath(), LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(job.stagePath(), LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.MUX_FAILED, "Unsafe staging file");
                    Files.delete(job.stagePath());
                }
                muxer.mux(job.videoPath(), job.videoExt(), job.audioPath(), job.audioExt(), job.stagePath(), job.container(), () -> run.stop != null || closed);
                check(run);
                // The merged file is published owner-only like every AJDM download, not with ffmpeg's default mode.
                if (Files.getFileStore(job.stagePath()).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(job.stagePath(), PosixFilePermissions.fromString("rw-------"));
                }
                try (var file = FileChannel.open(job.stagePath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
                job = job.hashes(video, audio, hash(job.stagePath(), run), Files.size(job.stagePath())).status(DownloadState.VERIFYING, null);
                save(run, job); observer.reached(job, MediaJobObserver.Boundary.READY);
            }
            publish(run); save(run, run.job.status(DownloadState.COMPLETED, null));
            // Publication is durable before cleanup; inputs remain on every earlier failure boundary.
            cleanup(run.job);
        } catch (InterruptedException e) { Thread.interrupted(); fail(run, run.stop == DownloadState.CANCELED ? ErrorCode.CANCELED : ErrorCode.PAUSED); }
        catch (ExecutionException e) { fail(run, e.getCause() instanceof DownloadException failure ? failure.code() : ErrorCode.PROTOCOL_VIOLATION); }
        catch (IOException | RuntimeException e) { fail(run, e instanceof DownloadException failure ? failure.code() : ErrorCode.MUX_FAILED); }
        finally {
            // Timed-out stops keep their admission slots until the actual transfer workers settle.
            var settled = run.children.stream().map(h -> h.completion().toCompletableFuture().handle((result, failure) -> null)).toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(settled).whenComplete((result, failure) -> running.remove(run.job.id(), run));
            run.done.complete(null);
        }
    }
    private void save(Run run, MediaJob job) throws IOException { engine.saveMediaJob(job); run.job = job; }
    private void fail(Run run, ErrorCode error) {
        Thread.interrupted(); stopChildren(run, run.stop == DownloadState.CANCELED);
        DownloadState state = run.stop != null ? run.stop : switch (error) {
            case PAUSED, SOURCE_CHANGED, INSUFFICIENT_DISK_SPACE, DISK_WRITE_ERROR, TARGET_EXISTS, AUTH_REQUIRED -> DownloadState.PAUSED;
            default -> DownloadState.FAILED;
        };
        ErrorCode reason = state == DownloadState.CANCELED ? ErrorCode.CANCELED
                : error == ErrorCode.PAUSED || error == ErrorCode.CANCELED && run.stop == DownloadState.PAUSED ? null : error;
        try { save(run, run.job.status(state, reason)); } catch (IOException e) { run.failure = e; }
    }
    private static void check(Run run) throws InterruptedException { if (run.stop != null || Thread.currentThread().isInterrupted()) throw new InterruptedException("Media stopped"); }
    private static void workspace(MediaJob job) throws IOException {
        if (!Files.isDirectory(job.workspace(), LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.PART_FILE_MISSING, "Media workspace missing");
    }
    static void requireSpace(Path path, long bytes, int copies) throws IOException {
        Files.createDirectories(path); long usable = Files.getFileStore(path).getUsableSpace(), reserve = 64L * 1024 * 1024;
        if (usable < reserve || bytes >= 0 && bytes > (usable - reserve) / copies) throw new DownloadException(ErrorCode.INSUFFICIENT_DISK_SPACE, "Media workspace needs stream and output space");
    }
    private static String hash(Path path, Run run) throws IOException, InterruptedException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.PART_FILE_MISSING, "Media file missing");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[1024 * 1024]; int count;
            while ((count = in.read(bytes)) != -1) { check(run); digest.update(bytes, 0, count); }
        } return HexFormat.of().formatHex(digest.digest());
    }
    private static boolean matches(Path path, MediaJob job, Run run) throws IOException, InterruptedException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == job.outputBytes() && hash(path, run).equals(job.outputHash());
    }
    private void publish(Run run) throws IOException, InterruptedException {
        MediaJob job = run.job;
        // A crash after publication but before COMPLETED is recognized by the durable full-file hash.
        if (matches(job.target(), job, run)) return;
        if (!matches(job.stagePath(), job, run)) throw new DownloadException(ErrorCode.CHECKSUM_MISMATCH, "Ready media output changed");
        Path original = job.target(); int suffix = 1;
        while (true) {
            check(run);
            try {
                if (job.conflict() == ConflictPolicy.OVERWRITE) Files.move(job.stagePath(), job.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.createLink(job.target(), job.stagePath());
                observer.reached(job, MediaJobObserver.Boundary.PUBLISHED); return;
            } catch (FileAlreadyExistsException e) {
                if (job.conflict() == ConflictPolicy.ASK) throw new DownloadException(ErrorCode.TARGET_EXISTS, "Destination exists");
                if (job.conflict() != ConflictPolicy.RENAME || suffix > 10000) throw new DownloadException(ErrorCode.TARGET_EXISTS, "Destination exists");
                String candidate = SafeFilename.numbered(original.getFileName().toString(), suffix++);
                job = job.destination(original.resolveSibling(candidate), job.conflict()); save(run, job);
            }
        }
    }
    private static void cleanup(MediaJob job) {
        for (Path path : List.of(job.videoPath(), job.audioPath(), job.stagePath())) try { if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path); } catch (IOException e) { /* Completed output retained; orphan cleanup can be retried. */ }
        try { Files.delete(job.workspace()); } catch (IOException e) { /* Keep any unexpected file rather than recursive deletion. */ }
    }
    private static void stopChildren(Run run, boolean cancel) {
        for (var child : run.children) {
            if (child.completion().toCompletableFuture().isDone()) continue;
            if (cancel) child.cancel(); else child.pause();
        }
        for (var child : run.children) try { child.completion().toCompletableFuture().handle((r, e) -> null).get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException e) { run.failure = new IOException("Media streams still stopping"); }
    }
    void stop(UUID id, boolean cancel) throws IOException {
        MediaJob job = find(id); if (job.state() == DownloadState.CANCELED) { if (cancel) return; throw new IllegalStateException("Canceled media job"); }
        if (job.state() == DownloadState.COMPLETED) throw new IllegalStateException("Completed media job");
        Run run = running.get(id);
        if (run == null) {
            if (cancel) {
                var records = records(); for (UUID child : List.of(job.videoId(), job.audioId())) if (records.get(child).state() != DownloadState.COMPLETED) engine.cancelInactive(child);
            }
            engine.saveMediaJob(job.status(cancel ? DownloadState.CANCELED : DownloadState.PAUSED, cancel ? ErrorCode.CANCELED : job.error())); return;
        }
        run.stop = cancel ? DownloadState.CANCELED : DownloadState.PAUSED; run.worker.interrupt();
        try { run.done.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Media control interrupted"); }
        catch (ExecutionException | TimeoutException e) { throw new DownloadException(ErrorCode.INVALID_STATE, "Media job still stopping"); }
        if (run.failure != null) throw run.failure;
    }
    @Override public void close() {
        closed = true;
        for (UUID id : List.copyOf(running.keySet())) try { stop(id, false); } catch (IOException | RuntimeException e) { /* Engine shutdown still stops all payloads. */ }
        muxer.close();
    }
    private static final class Run {
        volatile MediaJob job;
        final List<TransferHandle> children = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile DownloadState stop;
        volatile Thread worker;
        volatile IOException failure;
        Run(MediaJob job) { this.job = job; }
    }
}
