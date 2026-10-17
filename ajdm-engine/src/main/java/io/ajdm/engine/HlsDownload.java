package io.ajdm.engine;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import io.ajdm.net.CancellationScope;
import io.ajdm.net.HttpTransport;
import io.ajdm.net.JdkHttpTransport;
import io.ajdm.net.ResponseChecks;
import io.ajdm.net.RetryableResponseException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads one HLS media playlist with AJDM's own connections: parts are fetched in parallel, each verified against
 * its declared length or byte range, and appended in order to a single partial file. Progress survives restarts: the
 * work directory keeps the joined prefix and a durable record of how far it reaches, keyed to the playlist's
 * structure rather than its (often signed, expiring) links, plus any finished parts beyond it.
 */
public final class HlsDownload {
    public record Result(Path target, long bytes, String sha256, int parts, double durationSeconds) {}
    @FunctionalInterface public interface Progress { void update(int partsDone, int partsTotal, long bytes); }

    private static final String STATE = "hls.state", JOINED = "stream.part";
    private final TransferRequest request;
    private final Path work;
    private final CancellationScope scope;
    private final Progress progress;

    /** {@code request.uri()} is the media playlist; its headers, connections, timeouts and retries apply to every part. */
    public HlsDownload(TransferRequest request, Path work, CancellationScope scope, Progress progress) {
        this.request = request; this.work = work.toAbsolutePath().normalize(); this.scope = scope; this.progress = progress;
    }

    public Result run() throws IOException, InterruptedException {
        var watchdogs = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ajdm-hls-watchdog").factory());
        try (var transport = new JdkHttpTransport(request)) {
            return run(transport, watchdogs);
        } finally { watchdogs.shutdownNow(); }
    }

    private Result run(HttpTransport transport, ScheduledExecutorService watchdogs) throws IOException, InterruptedException {
        var options = request.options();
        if (Files.exists(request.target(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.TARGET_EXISTS, "Target exists");
        HlsPlaylist playlist = playlist(transport);
        int total = playlist.parts().size();
        Files.createDirectories(work);
        String signature = signature(playlist);
        Properties state = load();
        long joined = 0; int next = 0;
        if (state != null && signature.equals(state.getProperty("signature"))) {
            next = Integer.parseInt(state.getProperty("next")); joined = Long.parseLong(state.getProperty("length"));
            if (next < 0 || next > total || joined < 0) throw new DownloadException(ErrorCode.STATE_STORE_ERROR, "Invalid stream state");
        } else clear();
        try (FileChannel output = FileChannel.open(work.resolve(JOINED), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            if (output.size() < joined) throw new DownloadException(ErrorCode.PART_FILE_TRUNCATED, "Joined stream lost saved bytes");
            output.truncate(joined);
            var ready = new ConcurrentHashMap<Integer, Path>();
            for (int i = next; i < total; i++) if (Files.isRegularFile(part(i))) ready.put(i, part(i));
            var failure = new AtomicReference<Throwable>();
            var generation = scope.child();
            var cursor = new AtomicInteger(next);
            var appended = new AtomicInteger(next);
            var inFlight = new AtomicLong();
            int lanes = Math.max(1, Math.min(options.connections(), total - next));
            int lookahead = Math.max(4, 3 * lanes);
            var monitor = new Object();
            var workers = new java.util.ArrayList<Thread>();
            for (int lane = 0; lane < lanes && next < total; lane++) {
                var laneScope = generation.child();
                Thread worker = Thread.ofVirtual().name("ajdm-hls-" + request.id() + "-" + lane).unstarted(() -> {
                    try {
                        for (;;) {
                            int index;
                            synchronized (monitor) {
                                while (cursor.get() < total && cursor.get() >= appended.get() + lookahead && failure.get() == null) monitor.wait(200);
                                if (failure.get() != null || cursor.get() >= total) return;
                                index = cursor.getAndIncrement();
                            }
                            if (!ready.containsKey(index)) {
                                fetch(transport, playlist.parts().get(index), index, laneScope, watchdogs, inFlight);
                                ready.put(index, part(index));
                            }
                            synchronized (monitor) { monitor.notifyAll(); }
                        }
                    } catch (Throwable problem) {
                        // Other lanes finish the parts they are fetching (stop taking new ones) so a resume reuses them.
                        failure.compareAndSet(null, problem);
                        synchronized (monitor) { monitor.notifyAll(); }
                    } finally { laneScope.detach(); }
                });
                laneScope.worker(worker); workers.add(worker); worker.start();
            }
            try {
                long sinceForce = System.nanoTime();
                boolean drained = false;
                while (next < total) {
                    Path piece;
                    synchronized (monitor) {
                        while ((piece = ready.get(next)) == null && failure.get() == null) { scope.check(); monitor.wait(200); }
                    }
                    if (piece == null) {
                        // A part failed: wait for the other lanes' parts, then join every contiguous one before reporting.
                        if (drained) break;
                        for (Thread worker : workers) worker.join();
                        drained = true;
                        continue;
                    }
                    try (FileChannel in = FileChannel.open(piece, StandardOpenOption.READ)) {
                        long size = in.size(), copied = 0;
                        while (copied < size) copied += output.transferFrom(in, joined + copied, size - copied);
                        joined += size;
                    }
                    next++;
                    // Joined bytes become durable before the record that claims them; only then is the part removed.
                    output.force(false);
                    save(signature, next, joined);
                    Files.deleteIfExists(piece); ready.remove(next - 1);
                    synchronized (monitor) { appended.set(next); monitor.notifyAll(); }
                    if (progress != null && (next == total || System.nanoTime() - sinceForce > 200_000_000L)) {
                        progress.update(next, total, joined + inFlight.get()); sinceForce = System.nanoTime();
                    }
                }
            } finally {
                if (next < total && failure.get() == null) generation.cancel();
                for (Thread worker : workers) worker.join();
                generation.detach();
            }
            scope.check();
            Throwable problem = failure.get();
            if (problem instanceof IOException io) throw io;
            if (problem instanceof InterruptedException interrupted) throw interrupted;
            if (problem != null) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Stream worker failed", problem);
            output.force(true);
        }
        String sha256 = sha256(work.resolve(JOINED));
        Files.createDirectories(request.target().getParent());
        Files.move(work.resolve(JOINED), request.target());
        clear(); Files.deleteIfExists(work);
        return new Result(request.target(), Files.size(request.target()), sha256, total, playlist.durationSeconds());
    }

    private HlsPlaylist playlist(HttpTransport transport) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            scope.check();
            HttpResponse<InputStream> response = null;
            try {
                response = transport.send(request.uri(), "GET", Map.of(), scope);
                try (InputStream body = response.body()) {
                    ResponseChecks.status(response); ResponseChecks.identity(response);
                    byte[] bytes = body.readNBytes(HlsPlaylist.MAX_BYTES + 1);
                    if (bytes.length > HlsPlaylist.MAX_BYTES) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Playlist size limit");
                    String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    return HlsPlaylist.parse(text, response.uri());
                }
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Playlist is not UTF-8");
            } catch (DownloadException e) { throw e; }
            catch (IOException e) { backoff(e, attempt); }
            finally { if (response != null) scope.unregister(response.body()); }
        }
    }

    private void fetch(HttpTransport transport, HlsPlaylist.Part part, int index, CancellationScope lane,
                       ScheduledExecutorService watchdogs, AtomicLong inFlight) throws IOException, InterruptedException {
        var options = request.options();
        Path temporary = work.resolve("part-" + index + ".tmp");
        for (int attempt = 0; ; attempt++) {
            lane.check();
            HttpResponse<InputStream> response = null;
            long counted = 0;
            try {
                var range = part.range();
                response = transport.send(part.uri(), "GET",
                        range == null ? Map.of() : Map.of("Range", "bytes=" + range.start() + "-" + (range.endExclusive() - 1)), lane);
                try (InputStream body = response.body();
                     OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                     var guard = new StallGuard(body, options.stallTimeout(), watchdogs)) {
                    ResponseChecks.status(response); ResponseChecks.identity(response);
                    long expected;
                    if (range != null) {
                        if (response.statusCode() != 206) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Server ignored a stream byte range");
                        ResponseChecks.range(response, range.start(), range.endExclusive(), -1);
                        expected = range.endExclusive() - range.start();
                    } else {
                        if (response.statusCode() != 200) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Unexpected partial stream response");
                        expected = ResponseChecks.contentLength(response);
                    }
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = guard.read(buffer, buffer.length)) >= 0; ) {
                        if (count == 0) continue;
                        if (expected >= 0 && counted + count > expected) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Stream part exceeded its length");
                        out.write(buffer, 0, count); counted += count; inFlight.addAndGet(count);
                        lane.check();
                    }
                    if (expected >= 0 && counted != expected) throw new EOFException("Stream part ended early");
                    if (counted == 0) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Empty stream part");
                }
                try (FileChannel written = FileChannel.open(temporary, StandardOpenOption.WRITE)) { written.force(true); }
                Files.move(temporary, part(index), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                inFlight.addAndGet(-counted);
                return;
            } catch (DownloadException e) {
                inFlight.addAndGet(-counted); throw e;
            } catch (IOException e) {
                inFlight.addAndGet(-counted);
                backoff(e, attempt);
            } finally { if (response != null) lane.unregister(response.body()); }
        }
    }

    private void backoff(IOException failure, int attempt) throws IOException, InterruptedException {
        scope.check();
        if (RetryFailures.certificateFailure(failure)) throw new DownloadException(ErrorCode.TLS_VERIFICATION_FAILED, "TLS validation failed", failure);
        TransferOptions options = request.options();
        if (attempt >= options.maxRetries()) throw new DownloadException(ErrorCode.RETRIES_EXHAUSTED, "Stream retry limit reached", failure);
        long base = options.retryBaseDelay().toMillis(), cap = options.retryMaxDelay().toMillis();
        long maximum = Math.min(cap, base << Math.min(attempt, 20));
        long delay = maximum > 0 ? ThreadLocalRandom.current().nextLong(maximum + 1) : 0;
        if (failure instanceof RetryableResponseException response) delay = Math.max(delay, response.retryAfter().toMillis());
        EngineTrace.event("hls-retry", -1, "attempt=" + attempt + " " + failure.getClass().getSimpleName());
        Thread.sleep(delay);
    }

    private Path part(int index) { return work.resolve("part-" + index); }

    /** The playlist's shape, not its links: a renewed signed link to the same stream keeps its progress. */
    private static String signature(HlsPlaylist playlist) {
        var text = new StringBuilder().append(playlist.parts().size());
        for (var part : playlist.parts()) {
            text.append('|').append(part.initialization() ? 'i' : 's');
            if (part.range() != null) text.append(part.range().start()).append('-').append(part.range().endExclusive());
            String path = part.uri().getPath();
            text.append(':').append(path == null ? "" : path.substring(path.lastIndexOf('/') + 1));
        }
        return hex(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Properties load() throws IOException {
        Path file = work.resolve(STATE);
        if (!Files.isRegularFile(file)) return null;
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
        catch (IllegalArgumentException e) { return null; }
        try { Integer.parseInt(properties.getProperty("next")); Long.parseLong(properties.getProperty("length")); }
        catch (NumberFormatException e) { return null; }
        return properties;
    }

    private void save(String signature, int next, long length) throws IOException {
        var properties = new Properties();
        properties.setProperty("signature", signature);
        properties.setProperty("next", Integer.toString(next));
        properties.setProperty("length", Long.toString(length));
        var bytes = new ByteArrayOutputStream();
        properties.store(bytes, null);
        Path temporary = work.resolve(STATE + ".tmp");
        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            out.write(java.nio.ByteBuffer.wrap(bytes.toByteArray())); out.force(true);
        }
        Files.move(temporary, work.resolve(STATE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void clear() throws IOException {
        if (!Files.isDirectory(work)) return;
        try (var entries = Files.list(work)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                if (name.startsWith("part-") || name.equals(JOINED) || name.startsWith(STATE)) Files.deleteIfExists(entry);
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int count; (count = in.read(buffer)) >= 0; ) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
