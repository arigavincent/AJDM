package io.ajdm.core;

import io.ajdm.media.MediaInfo;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

/**
 * Recent media analyses by page URL, so choosing a quality and clicking Download does not run the extractor a second
 * time (7 s on a slow link) and a page the browser prefetched opens its qualities at once. One analysis per page runs
 * at a time; callers asking meanwhile share its result. Failures are not kept. Entries expire well before signed media
 * links do (the engine renews those anyway), and a cookie or browser-session change clears everything.
 */
final class MediaCache {
    interface Loader { MediaInfo load() throws IOException; }
    private static final long TTL_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int CAPACITY = 32;
    private record Entry(CompletableFuture<MediaInfo> result, long createdAt) {}
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    MediaCache() { this(System::nanoTime); }
    MediaCache(LongSupplier clock) { this.clock = clock; }

    MediaInfo get(String page, Loader loader) throws IOException {
        CompletableFuture<MediaInfo> result; boolean owner = false;
        synchronized (this) {
            var entry = entries.get(page);
            if (entry != null && (entry.result().isCompletedExceptionally() || done(entry) && clock.getAsLong() - entry.createdAt() > TTL_NANOS)) {
                entries.remove(page); entry = null;
            }
            if (entry == null) {
                entry = new Entry(new CompletableFuture<>(), clock.getAsLong()); entries.put(page, entry); owner = true;
                while (entries.size() > CAPACITY) entries.remove(entries.keySet().iterator().next());
            }
            result = entry.result();
        }
        if (owner) {
            try { result.complete(loader.load()); }
            catch (IOException | RuntimeException failure) {
                synchronized (this) { entries.remove(page); }
                result.completeExceptionally(failure); throw failure;
            }
        }
        try { return result.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.CANCELED, "Extraction interrupted"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Extraction failed", e.getCause());
        }
    }
    /** A finished analysis of {@code page} that is still fresh, without starting one. */
    synchronized MediaInfo peek(String page) {
        var entry = entries.get(page);
        if (entry == null || !done(entry) || entry.result().isCompletedExceptionally() || clock.getAsLong() - entry.createdAt() > TTL_NANOS) return null;
        return entry.result().join();
    }
    synchronized void clear() { entries.clear(); }
    private static boolean done(Entry entry) { return entry.result().isDone(); }
}
