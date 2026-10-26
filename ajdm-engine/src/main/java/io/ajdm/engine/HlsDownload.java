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
    /** {@code joinedBytes} are durable in the joined file; {@code receivedBytes} adds parts still being fetched. */
    @FunctionalInterface public interface Progress { void update(int partsDone, int partsTotal, long joinedBytes, long receivedBytes); }

    private static final String STATE = "hls.state", JOINED = "stream.part";
    private final TransferRequest request;
    private final Path work, joinedFile;
    private final HttpTransport transport;
    private final boolean publish;
    /** A playlist supplied by extraction (DASH fragments), used instead of fetching {@code request.uri()}. */
    private final String supplied;
    private RateLimiter limiter;
    private final CancellationScope scope;
    private final Progress progress;

    /** {@code request.uri()} is the media playlist; its headers, connections, timeouts and retries apply to every part. */
    public HlsDownload(TransferRequest request, Path work, CancellationScope scope, Progress progress) {
        this(request, null, work.toAbsolutePath().normalize().resolve(JOINED), work, scope, progress, true, null);
    }
    /**
     * Engine use: join into the engine's own partial file over its transport and leave publication to the engine.
     * {@code supplied} is a playlist from extraction, or null to fetch {@code request.uri()}.
     */
    HlsDownload(TransferRequest request, HttpTransport transport, Path joined, Path work, CancellationScope scope, Progress progress,
                String supplied, RateLimiter limiter) {
        this(request, transport, joined, work, scope, progress, false, supplied);
        this.limiter = limiter;
    }
    private HlsDownload(TransferRequest request, HttpTransport transport, Path joined, Path work, CancellationScope scope,
                        Progress progress, boolean publish, String supplied) {
        this.request = request; this.transport = transport; this.joinedFile = joined.toAbsolutePath().normalize();
        this.work = work.toAbsolutePath().normalize(); this.scope = scope; this.progress = progress; this.publish = publish;
        this.supplied = supplied;
    }

    /** Joins the stream and, for standalone use, moves it to the request's target. */
    public Result run() throws IOException, InterruptedException {
        var watchdogs = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ajdm-hls-watchdog").factory());
        try {
            if (transport != null) return run(transport, watchdogs);
            try (var owned = new JdkHttpTransport(request)) { return run(owned, watchdogs); }
        } finally { watchdogs.shutdownNow(); }
    }

    private Result run(HttpTransport transport, ScheduledExecutorService watchdogs) throws IOException, InterruptedException {
        var options = request.options();
        if (publish && Files.exists(request.target(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.TARGET_EXISTS, "Target exists");
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
        try (FileChannel output = FileChannel.open(joinedFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
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
                        progress.update(next, total, joined, joined + inFlight.get()); sinceForce = System.nanoTime();
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
        String sha256 = sha256(joinedFile);
        long bytes = Files.size(joinedFile);
        Path result = joinedFile;
        if (publish) {
            Files.createDirectories(request.target().getParent());
            Files.move(joinedFile, request.target());
            result = request.target();
        }
        clear(); Files.deleteIfExists(work);
        return new Result(result, bytes, sha256, total, playlist.durationSeconds());
    }

    private HlsPlaylist playlist(HttpTransport transport) throws IOException, InterruptedException {
        if (supplied != null) return HlsPlaylist.parse(supplied, request.uri());
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

    /**
     * Fetches one part into its own file. A failed attempt keeps the bytes it received and the next one asks only for
     * the rest (a byte range), so a flaky link never refetches what already arrived; a server that answers a resume
     * with the whole body restarts the part. Only consecutive attempts that add no new bytes count towards the retry
     * limit, so a slow part that keeps progressing is never abandoned.
     */
    private void fetch(HttpTransport transport, HlsPlaylist.Part part, int index, CancellationScope lane,
                       ScheduledExecutorService watchdogs, AtomicLong inFlight) throws IOException, InterruptedException {
        var options = request.options();
        Path temporary = work.resolve("part-" + index + ".tmp");
        Files.deleteIfExists(temporary); // A leftover from an earlier run has no trustworthy length; start clean.
        var range = part.range();
        long have = 0, best = 0, total = range == null ? -1 : range.endExclusive() - range.start();
        for (int failures = 0; ; ) {
            lane.check();
            HttpResponse<InputStream> response = null;
            long received = 0;
            try {
                boolean resume = have > 0 && total >= 0;
                String header = range != null ? "bytes=" + (range.start() + have) + "-" + (range.endExclusive() - 1)
                        : resume ? "bytes=" + have + "-" : null;
                response = transport.send(part.uri(), "GET", header == null ? Map.of() : Map.of("Range", header), lane);
                try (InputStream body = response.body(); var guard = new StallGuard(body, options.stallTimeout(), watchdogs)) {
                    ResponseChecks.status(response); ResponseChecks.identity(response);
                    if (range != null) {
                        if (response.statusCode() != 206) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Server ignored a stream byte range");
                        ResponseChecks.range(response, range.start() + have, range.endExclusive(), -1);
                    } else if (resume && response.statusCode() == 206) {
                        ResponseChecks.range(response, have, total, total);
                    } else {
                        if (response.statusCode() != 200) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Unexpected partial stream response");
                        inFlight.addAndGet(-have); have = 0; // Fresh, or the server ignored the resume range: start over.
                        total = ResponseChecks.contentLength(response);
                    }
                    try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            have == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int count; (count = guard.read(buffer, buffer.length)) >= 0; ) {
                            if (count == 0) continue;
                            if (total >= 0 && have + received + count > total) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Stream part exceeded its length");
                            if (limiter != null) limiter.acquire(count, lane);
                            out.write(buffer, 0, count); received += count; inFlight.addAndGet(count);
                            lane.check();
                        }
                    }
                    if (total >= 0 && have + received != total) throw new EOFException("Stream part ended early");
                    if (have + received == 0) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Empty stream part");
                }
                long bytes = have + received;
                if (part.key() != null) decrypt(temporary, key(transport, part.key(), lane), part.iv());
                try (FileChannel written = FileChannel.open(temporary, StandardOpenOption.WRITE)) { written.force(true); }
                Files.move(temporary, part(index), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                inFlight.addAndGet(-bytes);
                return;
            } catch (DownloadException e) {
                inFlight.addAndGet(-(have + received)); throw e;
            } catch (IOException e) {
                have += received;
                if (have > best) { best = have; failures = 0; } else failures++;
                if (failures > 0) EngineTrace.event("hls-part-retry", -1, "part=" + index + " kept=" + have);
                // The index of this failure among consecutive ones without progress; a progressing attempt is never counted.
                backoff(e, Math.max(0, failures - 1));
            } finally { if (response != null) lane.unregister(response.body()); }
        }
    }

    /** AES-128 keys by link: fetched once per download with AJDM's own connection, exactly 16 bytes. */
    private final Map<java.net.URI, byte[]> keys = new ConcurrentHashMap<>();

    private final Map<java.net.URI, Object> keyLocks = new ConcurrentHashMap<>();

    /** Single flight per key: parts that need the same key at once wait for one fetch instead of each fetching it. */
    private byte[] key(HttpTransport transport, HlsPlaylist.Key key, CancellationScope lane) throws IOException, InterruptedException {
        synchronized (keyLocks.computeIfAbsent(key.uri(), uri -> new Object())) { return fetchKey(transport, key, lane); }
    }

    private byte[] fetchKey(HttpTransport transport, HlsPlaylist.Key key, CancellationScope lane) throws IOException, InterruptedException {
        byte[] cached = keys.get(key.uri());
        if (cached != null) return cached;
        for (int attempt = 0; ; attempt++) {
            lane.check();
            HttpResponse<InputStream> response = null;
            try {
                response = transport.send(key.uri(), "GET", Map.of(), lane);
                try (InputStream body = response.body()) {
                    ResponseChecks.status(response); ResponseChecks.identity(response);
                    byte[] bytes = body.readNBytes(17);
                    if (bytes.length != 16) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Stream key is not 16 bytes");
                    keys.put(key.uri(), bytes);
                    return bytes;
                }
            } catch (DownloadException e) { throw e; }
            catch (IOException e) { backoff(e, attempt); }
            finally { if (response != null) lane.unregister(response.body()); }
        }
    }

    /** Replaces an encrypted part with its AES-128-CBC plaintext (PKCS#7 padding), as RFC 8216 section 5.2 defines. */
    private static void decrypt(Path file, byte[] key, byte[] iv) throws IOException {
        byte[] plain;
        try {
            var cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            plain = cipher.doFinal(Files.readAllBytes(file));
        } catch (java.security.GeneralSecurityException e) {
            throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Could not decrypt a stream part with its key");
        }
        Files.write(file, plain, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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
