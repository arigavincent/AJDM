package io.ajdm.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import io.ajdm.model.ByteRange;

/** Deterministic local fixture. It never fetches data from another downloader or network source. */
public final class FaultyRangeServer implements AutoCloseable {
    public enum Mode {
        NORMAL, HEAD_REJECTED, NO_RANGES, NO_RANGES_TRUNCATE_ONCE, NO_VALIDATOR, UNKNOWN_LENGTH, WRONG_RANGE,
        TRUNCATE_ONCE, TRUNCATE_ALWAYS, STALL_ONCE, STALL_ALWAYS,
        THROTTLE_ONCE, THROTTLE_ALWAYS, CHANGED_SOURCE, IGNORE_RANGES_MIDWAY,
        ENCODED, CORRUPT_BODY, WRONG_TOTAL, EXCESS_LENGTH, HEADER_STALL, SLOW, SLOW_NO_RANGES, SLOW_NO_VALIDATOR,
        SLOW_LAST_MODIFIED, SLOW_UNKNOWN_LENGTH, BLOCK_FIRST_SEGMENT, CAPPED, SHARED_CAPPED, CHANGING_LANE, PUSHBACK_CAPPED
    }
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final byte[] data;
    private final Mode mode;
    private final AtomicInteger transferRequests = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger activeTransfers = new AtomicInteger(), peakTransfers = new AtomicInteger();
    private final long bytesPerSecond;
    private final java.util.concurrent.atomic.AtomicLong sharedPace = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong transferStarted = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicInteger changingPort = new AtomicInteger();
    private final CountDownLatch requested = new CountDownLatch(1);
    private final CountDownLatch releaseStalls = new CountDownLatch(1);
    private volatile Map<String, List<String>> lastHeaders = Map.of();
    private volatile String etag = "\"fixture-v1\"";
    private final ConcurrentLinkedQueue<ByteRange> requestedRanges = new ConcurrentLinkedQueue<>();

    public FaultyRangeServer(int size, Mode mode) throws IOException {
        this(size, mode, 512 * 1024);
    }
    public FaultyRangeServer(int size, Mode mode, long bytesPerSecond) throws IOException {
        if (bytesPerSecond < 1) throw new IllegalArgumentException("Rate must be positive");
        this.mode = mode;
        this.bytesPerSecond = bytesPerSecond;
        this.data = new byte[size];
        for (int i = 0; i < size; i++) {
            int value = i * 0x9e3779b9;
            value ^= value >>> 16;
            value *= 0x85ebca6b;
            data[i] = (byte) (value ^ (value >>> 13));
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/file", this::serve);
        server.start();
    }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"); }
    public byte[] data() { return data.clone(); }
    public int transferRequests() { return transferRequests.get(); }
    public int requests() { return requests.get(); }
    public int peakTransfers() { return peakTransfers.get(); }
    public int activeTransfers() { return activeTransfers.get(); }
    public void releaseFirstSegment() { releaseStalls.countDown(); }
    public Map<String, List<String>> lastHeaders() { return lastHeaders; }
    public void etag(String value) { etag = value; }
    public List<ByteRange> requestedRanges() { return List.copyOf(requestedRanges); }
    public boolean awaitTransfer() throws InterruptedException { return requested.await(5, TimeUnit.SECONDS); }
    public String sha256() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private void serve(HttpExchange exchange) throws IOException {
        boolean tracked = false;
        try (exchange) {
            requests.incrementAndGet();
            lastHeaders = Map.copyOf(exchange.getRequestHeaders());
            boolean unknownLength = mode == Mode.UNKNOWN_LENGTH || mode == Mode.SLOW_UNKNOWN_LENGTH;
            if (mode != Mode.NO_VALIDATOR && mode != Mode.SLOW_NO_VALIDATOR && mode != Mode.SLOW_LAST_MODIFIED) exchange.getResponseHeaders().set("ETag", etag);
            if (mode == Mode.SLOW_LAST_MODIFIED) exchange.getResponseHeaders().set("Last-Modified", "Wed, 30 Sep 2026 12:00:00 GMT");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                if (mode == Mode.HEAD_REJECTED) { exchange.sendResponseHeaders(405, -1); return; }
                if (!unknownLength) exchange.getResponseHeaders().set("Content-Length", "" + data.length);
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean probe = "bytes=0-0".equals(range);
            int attempt = probe ? 0 : transferRequests.incrementAndGet();
            if (!probe) {
                transferStarted.compareAndSet(0, System.nanoTime());
                changingPort.compareAndSet(0, exchange.getRemoteAddress().getPort());
                requested.countDown(); tracked = true;
                peakTransfers.accumulateAndGet(activeTransfers.incrementAndGet(), Math::max);
            }
            if (!probe && mode == Mode.HEADER_STALL) {
                try { releaseStalls.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (!probe && (mode == Mode.THROTTLE_ALWAYS || ((mode == Mode.THROTTLE_ONCE || mode == Mode.PUSHBACK_CAPPED) && attempt == 1))) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1); return;
            }
            if (!probe && mode == Mode.CHANGED_SOURCE && attempt > 1) {
                exchange.getResponseHeaders().set("ETag", "\"fixture-v2\"");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data); return;
            }
            boolean ranged = range != null && mode != Mode.NO_RANGES && mode != Mode.NO_RANGES_TRUNCATE_ONCE
                    && mode != Mode.SLOW_NO_RANGES && !unknownLength
                    && !(mode == Mode.IGNORE_RANGES_MIDWAY && !probe && attempt > 1);
            long start = 0, end = data.length - 1L;
            if (ranged) {
                String[] bounds = range.substring(6).split("-", -1);
                start = Long.parseLong(bounds[0]); end = Long.parseLong(bounds[1]);
                if (!probe) requestedRanges.add(new ByteRange(start, end + 1));
                if (start >= data.length || end >= data.length || end < start) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + data.length);
                    exchange.sendResponseHeaders(416, -1); return;
                }
                long reportedStart = !probe && mode == Mode.WRONG_RANGE ? start + 1 : start;
                long reportedTotal = !probe && mode == Mode.WRONG_TOTAL ? data.length + 1L : data.length;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + reportedStart + "-" + end + "/" + reportedTotal);
            }
            if (!probe && mode == Mode.ENCODED) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            long length = end - start + 1;
            boolean chunked = unknownLength;
            long declaredLength = !probe && mode == Mode.EXCESS_LENGTH ? length + 1 : length;
            exchange.sendResponseHeaders(ranged ? 206 : 200, length == 0 ? -1 : chunked ? 0 : declaredLength);
            if (!probe && mode == Mode.BLOCK_FIRST_SEGMENT && start == 0) {
                try { releaseStalls.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (!probe && (mode == Mode.STALL_ALWAYS || (mode == Mode.STALL_ONCE && attempt == 1))) {
                try { releaseStalls.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return;
            }
            int count = (int) length;
            if (!probe && (mode == Mode.TRUNCATE_ALWAYS || ((mode == Mode.TRUNCATE_ONCE
                    || mode == Mode.NO_RANGES_TRUNCATE_ONCE) && attempt == 1))) count /= 2;
            if (!probe && mode == Mode.CORRUPT_BODY && count > 0) {
                byte[] damaged = java.util.Arrays.copyOfRange(data, (int) start, (int) start + count);
                damaged[0] ^= 1;
                exchange.getResponseBody().write(damaged);
            } else if (count > 0 && (mode == Mode.SLOW || mode == Mode.SLOW_NO_RANGES || mode == Mode.SLOW_NO_VALIDATOR
                    || mode == Mode.SLOW_LAST_MODIFIED || mode == Mode.SLOW_UNKNOWN_LENGTH
                    || mode == Mode.BLOCK_FIRST_SEGMENT || mode == Mode.CAPPED || mode == Mode.SHARED_CAPPED
                    || mode == Mode.CHANGING_LANE || mode == Mode.PUSHBACK_CAPPED) && !probe) {
                long pacedUntil = System.nanoTime();
                for (int offset = 0; offset < count; offset += 4096) {
                    int chunk = Math.min(4096, count - offset);
                    exchange.getResponseBody().write(data, (int) start + offset, chunk);
                    exchange.getResponseBody().flush();
                    try {
                        if (mode == Mode.CAPPED || mode == Mode.SHARED_CAPPED || mode == Mode.CHANGING_LANE || mode == Mode.PUSHBACK_CAPPED) {
                            long rate = bytesPerSecond;
                            if (mode == Mode.CHANGING_LANE && exchange.getRemoteAddress().getPort() == changingPort.get()
                                    && System.nanoTime() - transferStarted.get() >= TimeUnit.SECONDS.toNanos(1)) rate = Math.max(1, rate / 16);
                            long cost = Math.max(1, chunk * 1000000000L / rate);
                            if (mode == Mode.SHARED_CAPPED) {
                                long now = System.nanoTime();
                                pacedUntil = sharedPace.updateAndGet(previous -> Math.max(now, previous) + cost);
                            } else pacedUntil += cost;
                            long delay = pacedUntil - System.nanoTime();
                            if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
                        } else Thread.sleep(3);
                    }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            } else if (count > 0) exchange.getResponseBody().write(data, (int) start, count);
        } catch (IOException expectedDisconnect) {
            // Abrupt-close and cancellation scenarios deliberately break HTTP bodies.
        } finally { if (tracked) activeTransfers.decrementAndGet(); }
    }
    @Override public void close() {
        releaseStalls.countDown();
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }
}
