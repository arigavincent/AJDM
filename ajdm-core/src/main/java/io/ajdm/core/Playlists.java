package io.ajdm.core;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One opt-in playlist runner, at most three lazy extractions, two-second durable start spacing. */
final class Playlists implements AutoCloseable {
    private final CoreService core;
    private final AjdmEngine engine;
    private final MediaExtractor extractor;
    private final PlaylistObserver observer;
    private final ExecutorService extraction;
    private volatile Run running;
    private volatile boolean closed;
    Playlists(CoreService core, AjdmEngine engine, MediaExtractor extractor, PlaylistObserver observer, ExecutorService extraction) {
        this.core = core; this.engine = engine; this.extractor = extractor; this.observer = observer;
        this.extraction = extraction;
    }
    List<PlaylistJob> saved() throws IOException { return engine.playlists(); }
    List<PlaylistItem> items(UUID id) throws IOException { find(id); return engine.playlistItems(id); }
    PlaylistJob find(UUID id) throws IOException { return saved().stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown playlist")); }
    boolean isPlaylist(UUID id) throws IOException { return saved().stream().anyMatch(j -> j.id().equals(id)); }
    /** Caller holds this coordinator's admission monitor while checking and removing the stopped graph. */
    void requireRemovable(UUID id) throws IOException {
        Run run = running;
        if (run != null && (run.job.id().equals(id) || engine.playlistItems(run.job.id()).stream().anyMatch(item -> id.equals(item.childId()))))
            throw new IllegalStateException("Pause the playlist before removing its jobs");
    }
    DownloadState state(PlaylistJob job) {
        if ((running == null || !running.job.id().equals(job.id())) && Set.of(DownloadState.QUEUED, DownloadState.EXTRACTING, DownloadState.DOWNLOADING).contains(job.state())) return DownloadState.PAUSED;
        return job.state();
    }
    synchronized UUID create(PlaylistJob job, List<PlaylistItem> items) throws IOException {
        if (closed || running != null) throw new DownloadException(ErrorCode.INVALID_STATE, "One active playlist allowed");
        engine.createPlaylist(job, items); observer.reached(job, null, PlaylistObserver.Boundary.CREATED); resume(job.id(), null); return job.id();
    }
    synchronized void resume(UUID id, ConflictPolicy policy) throws IOException {
        if (closed) throw new IllegalStateException("Playlist runner closed");
        if (running != null) { if (running.job.id().equals(id)) return; throw new DownloadException(ErrorCode.INVALID_STATE, "One active playlist allowed"); }
        var job = find(id);
        if (job.state() == DownloadState.COMPLETED || job.state() == DownloadState.CANCELED) throw new IllegalStateException("Terminal playlist");
        var run = new Run(job.status(DownloadState.DOWNLOADING, null), policy); engine.savePlaylist(run.job); running = run;
        run.worker = Thread.ofVirtual().name("ajdm-playlist-" + id).unstarted(() -> execute(run)); run.worker.start();
    }
    private void execute(Run run) {
        var futures = new HashMap<Integer, Future<MediaInfo>>(); var ready = new HashMap<Integer, MediaInfo>();
        try {
            for (var item : engine.playlistItems(run.job.id())) if (!item.terminal()) engine.savePlaylistItem(item.status(PlaylistItem.State.QUEUED, null));
            while (true) {
                check(run); var items = engine.playlistItems(run.job.id());
                for (var item : items) {
                    if (item.terminal()) continue;
                    CoreService.Row child = item.childId() == null ? null : core.work(item.childId());
                    if (child != null) {
                        if (child.state() == DownloadState.COMPLETED) {
                            var done = item.status(PlaylistItem.State.COMPLETED, null); engine.savePlaylistItem(done); observer.reached(run.job, done, PlaylistObserver.Boundary.ITEM_COMPLETED);
                        } else if (child.state() == DownloadState.FAILED || child.state() == DownloadState.CANCELED) {
                            engine.savePlaylistItem(item.status(child.state() == DownloadState.CANCELED ? PlaylistItem.State.CANCELED : PlaylistItem.State.FAILED, child.error()));
                        } else if (child.state() == DownloadState.PAUSED) {
                            if (item.state() == PlaylistItem.State.DOWNLOADING) throw new DownloadException(child.error() == null ? ErrorCode.PAUSED : child.error(), "Playlist item paused");
                            try { core.resume(item.childId(), Map.of(), run.policy); engine.savePlaylistItem(item.status(PlaylistItem.State.DOWNLOADING, null)); }
                            catch (DownloadException e) { if (e.code() != ErrorCode.INVALID_STATE) throw e; }
                        } else if (item.state() != PlaylistItem.State.DOWNLOADING) engine.savePlaylistItem(item.status(PlaylistItem.State.DOWNLOADING, null));
                        continue;
                    }
                    Future<MediaInfo> future = futures.get(item.index());
                    if (future != null && future.isDone()) {
                        try { ready.put(item.index(), future.get()); }
                        catch (ExecutionException e) {
                            ErrorCode reason = e.getCause() instanceof DownloadException d ? d.code() : ErrorCode.EXTRACTION_FAILED;
                            if (reason == ErrorCode.INVALID_STATE) { engine.savePlaylistItem(item.status(PlaylistItem.State.QUEUED, null)); }
                            else engine.savePlaylistItem(item.status(PlaylistItem.State.SKIPPED, reason));
                        } finally { futures.remove(item.index()); }
                        if (!ready.containsKey(item.index())) continue;
                    }
                    MediaInfo info = ready.get(item.index());
                    if (info != null) {
                        try {
                            var choice = new PlaylistSelection.Choice(item.preset(), item.formatId(), item.audioId()); var plan = choice.plan(info, run.job.modernCodecs());
                            PlaylistItem intent = item.childId() == null ? item.child(UUID.randomUUID()) : item;
                            engine.savePlaylistItem(intent.status(PlaylistItem.State.QUEUED, null)); observer.reached(run.job, intent, PlaylistObserver.Boundary.ITEM_INTENT); check(run);
                            String leaf = SafeFilename.sanitize(String.format(Locale.ROOT, "%04d-%s.%s", item.index(), SafeFilename.sanitize(info.title()), plan.container()));
                            core.admitMedia(info, plan, run.job.directory(), leaf, run.job.conflict(), run.job.connections(), intent.childId());
                            var admitted = intent.status(PlaylistItem.State.DOWNLOADING, null); engine.savePlaylistItem(admitted); observer.reached(run.job, admitted, PlaylistObserver.Boundary.ITEM_ADMITTED);
                            ready.remove(item.index());
                        } catch (DownloadException e) {
                            if (e.code() != ErrorCode.INVALID_STATE) {
                                if (e.code() == ErrorCode.INSUFFICIENT_DISK_SPACE || e.code() == ErrorCode.TARGET_EXISTS) throw e;
                                var persisted = engine.playlistItems(run.job.id()).stream().filter(i -> i.index() == item.index()).findFirst().orElseThrow();
                                // Admission can fail after committing a child. Keep it recoverable, never mark it skipped.
                                if (persisted.childId() != null && core.work(persisted.childId()) != null) throw e;
                                engine.savePlaylistItem(persisted.status(PlaylistItem.State.SKIPPED, e.code())); ready.remove(item.index());
                            }
                        } catch (IllegalArgumentException e) { engine.savePlaylistItem(item.status(PlaylistItem.State.SKIPPED, ErrorCode.FEATURE_UNAVAILABLE)); ready.remove(item.index()); }
                    } else if (future == null && futures.size() + ready.size() < 3 && System.currentTimeMillis() >= run.job.nextExtractionAt()) {
                        // Save the next wall-clock start before launching; reopening does not reset rate protection.
                        run.job = run.job.nextExtraction(System.currentTimeMillis() + 2000); engine.savePlaylist(run.job);
                        engine.savePlaylistItem(item.status(PlaylistItem.State.EXTRACTING, null));
                        futures.put(item.index(), extraction.submit(() -> {
                            run.awaitExtractionStart();
                            return extractor.extractEntry(item.page(), item.mediaId(), () -> run.stop != null || closed || Thread.currentThread().isInterrupted());
                        }));
                    }
                }
                var latest = engine.playlistItems(run.job.id());
                if (latest.stream().allMatch(PlaylistItem::terminal)) { engine.savePlaylist(run.job.status(DownloadState.COMPLETED, null)); return; }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) { Thread.interrupted(); stopAll(run, futures, run.stop == DownloadState.CANCELED, null); }
        catch (IOException | RuntimeException e) { stopAll(run, futures, false, e instanceof DownloadException d ? d.code() : ErrorCode.STATE_STORE_ERROR); }
        finally { synchronized (this) { if (running == run) running = null; } run.done.complete(null); }
    }
    private void stopAll(Run run, Map<Integer, Future<MediaInfo>> futures, boolean cancel, ErrorCode reason) {
        Thread.interrupted(); futures.values().forEach(f -> f.cancel(true));
        try {
            for (var item : engine.playlistItems(run.job.id())) {
                if (item.terminal()) continue;
                CoreService.Row child = item.childId() == null ? null : core.work(item.childId());
                if (child != null && child.state() != DownloadState.COMPLETED && child.state() != DownloadState.CANCELED) {
                    if (cancel) core.cancel(item.childId()); else if (child.state() != DownloadState.FAILED) core.pause(item.childId());
                    child = core.work(item.childId());
                }
                if (child != null && child.state() == DownloadState.COMPLETED) engine.savePlaylistItem(item.status(PlaylistItem.State.COMPLETED, null));
                else engine.savePlaylistItem(item.status(cancel ? PlaylistItem.State.CANCELED : PlaylistItem.State.PAUSED, child != null && child.error() != null ? child.error() : reason));
            }
            engine.savePlaylist(run.job.status(cancel ? DownloadState.CANCELED : DownloadState.PAUSED, cancel ? ErrorCode.CANCELED : reason == ErrorCode.PAUSED ? null : reason));
        } catch (IOException | RuntimeException e) { run.failure = new IOException("Playlist control failed"); }
    }
    private static void check(Run run) throws InterruptedException { if (run.stop != null || Thread.currentThread().isInterrupted()) throw new InterruptedException("Playlist stopped"); }
    void stop(UUID id, boolean cancel) throws IOException {
        Run run;
        synchronized (this) {
            var job = find(id);
            if (job.state() == DownloadState.COMPLETED || job.state() == DownloadState.CANCELED) { if (cancel && job.state() == DownloadState.CANCELED) return; throw new IllegalStateException("Terminal playlist"); }
            run = running;
            if (run != null && !run.job.id().equals(id)) run = null;
            if (run != null) { run.stop = cancel ? DownloadState.CANCELED : DownloadState.PAUSED; run.worker.interrupt(); }
            else run = new Run(job, null);
        }
        if (run.worker == null) { stopAll(run, Map.of(), cancel, null); if (run.failure != null) throw run.failure; return; }
        try { run.done.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Playlist control interrupted"); }
        catch (ExecutionException | TimeoutException e) { throw new DownloadException(ErrorCode.INVALID_STATE, "Playlist still stopping"); }
        if (run.failure != null) throw run.failure;
    }
    @Override public void close() {
        closed = true; Run run = running;
        if (run != null) try { stop(run.job.id(), false); } catch (IOException | RuntimeException e) { /* Core still shuts down all payload/extraction workers. */ }
        extraction.shutdownNow();
        try { extraction.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static final class Run {
        volatile PlaylistJob job; final ConflictPolicy policy;
        final CompletableFuture<Void> done = new CompletableFuture<>(); volatile DownloadState stop; volatile Thread worker; volatile IOException failure;
        private long nextStart;
        Run(PlaylistJob job, ConflictPolicy policy) {
            this.job = job; this.policy = policy;
            // After reopening, a dispatched request may have started later than its saved reservation.
            nextStart = System.nanoTime() + (job.nextExtractionAt() == 0 ? 0 : TimeUnit.SECONDS.toNanos(2));
        }
        synchronized void awaitExtractionStart() throws InterruptedException {
            while (true) {
                check(this);
                long remaining = nextStart - System.nanoTime();
                if (remaining <= 0) {
                    nextStart = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }
}
