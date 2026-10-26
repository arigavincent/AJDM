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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Fresh per trial. This server serves deterministic bytes and never orchestrates a download. */
public final class BenchmarkServer implements AutoCloseable {
    public record Request(int ordinal, long start, long endExclusive, long elapsedMillis,
                          long offeredBytes, boolean intentionalDrop) {}
    private final NetworkProfile profile;
    private final byte[] data;
    private final String sha256;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger allRequests = new AtomicInteger(), payloadRequests = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), drops = new AtomicInteger();
    private final AtomicLong epoch = new AtomicLong(), offered = new AtomicLong();
    private long sharedDeadline;
    public BenchmarkServer(NetworkProfile profile) throws IOException { this(profile, 0); }
    public BenchmarkServer(NetworkProfile profile, int port) throws IOException {
        this.profile = profile;
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        data = new byte[profile.bytes()];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + i / 17);
        try { sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor); server.createContext("/payload.bin", this::serve); server.start();
    }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/payload.bin"); }
    public String sha256() { return sha256; }
    public int peakResponses() { return peak.get(); }
    public int activeResponses() { return active.get(); }
    public int allRequests() { return allRequests.get(); }
    public int payloadRequests() { return payloadRequests.get(); }
    public int droppedBodies() { return drops.get(); }
    public long offeredBytes() { return offered.get(); }
    public List<Request> requests() { return List.copyOf(requests); }
    private synchronized long sharedGrant(int bytes, long now) {
        long begin = paced(sharedDeadline, now);
        long rate = profile.sharedRateAt(Math.max(0, begin - epoch.get()));
        if (rate == 0) return now;
        sharedDeadline = begin + cost(bytes, rate); return sharedDeadline;
    }
    /**
     * Next slot on a running schedule. Sleep overshoot (up to ~16 ms per 4 KiB step with Windows' timer) is caught
     * up so a cap is delivered at its stated rate; a lag beyond {@link #CATCH_UP} is a stalled reader, which restarts
     * the schedule instead of earning a burst above the cap.
     */
    static long paced(long scheduled, long now) { return now - scheduled > CATCH_UP ? now : scheduled; }
    static final long CATCH_UP = TimeUnit.MILLISECONDS.toNanos(50);
    private static long cost(int bytes, long rate) { return (bytes * 1_000_000_000L + rate - 1) / rate; }
    private static void waitUntil(long deadline) throws InterruptedException {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) TimeUnit.NANOSECONDS.sleep(remaining);
    }
    private void serve(HttpExchange exchange) throws IOException {
        allRequests.incrementAndGet();
        boolean tracked = false, drop = false;
        int ordinal = 0; long from = 0, to = data.length, written = 0, at = 0;
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("HEAD") && !method.equals("GET")) { exchange.sendResponseHeaders(405, -1); return; }
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean head = method.equals("HEAD"), probe = "bytes=0-0".equals(range);
            if (!head && !probe) {
                ordinal = payloadRequests.incrementAndGet(); tracked = true;
                long now = System.nanoTime(); epoch.compareAndSet(0, now); at = Math.max(0, now - epoch.get()) / 1_000_000;
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                drop = profile.droppedBodies().contains(ordinal);
                if (drop) drops.incrementAndGet();
            }
            // Delay is an HTTP request/response delay, applied to HEAD and range probes as well.
            if (!profile.responseDelay().isZero()) TimeUnit.NANOSECONDS.sleep(profile.responseDelay().toNanos());
            exchange.getResponseHeaders().set("ETag", "\"sha256-" + sha256 + "\"");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (head) { exchange.getResponseHeaders().set("Content-Length", Integer.toString(data.length)); exchange.sendResponseHeaders(200, -1); return; }
            int status = 200;
            if (range != null) {
                if (!range.matches("bytes=[0-9]+-[0-9]*")) { exchange.sendResponseHeaders(400, -1); return; }
                String[] parts = range.substring(6).split("-", -1);
                try {
                    from = Long.parseLong(parts[0]); to = parts[1].isEmpty() ? data.length : Math.addExact(Long.parseLong(parts[1]), 1);
                } catch (NumberFormatException | ArithmeticException e) { exchange.sendResponseHeaders(400, -1); return; }
                if (from >= data.length || from >= to || to > data.length) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + data.length); exchange.sendResponseHeaders(416, -1); return;
                }
                status = 206; exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (to - 1) + "/" + data.length);
            }
            exchange.sendResponseHeaders(status, to - from);
            long limit = drop ? from + Math.max(1, (to - from) / 2) : to;
            long responseDeadline = System.nanoTime();
            for (long offset = from; offset < limit; offset += 4096) {
                int count = (int) Math.min(4096, limit - offset);
                if (tracked) {
                    long now = System.nanoTime();
                    if (profile.perResponseRate() > 0) responseDeadline = paced(responseDeadline, now) + cost(count, profile.perResponseRate());
                    else responseDeadline = now;
                    waitUntil(Math.max(responseDeadline, sharedGrant(count, now)));
                }
                exchange.getResponseBody().write(data, (int) offset, count); exchange.getResponseBody().flush();
                if (tracked) { written += count; offered.addAndGet(count); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException e) { /* Incomplete bodies and canceled siblings deliberately close responses. */ }
        finally {
            if (tracked) { requests.add(new Request(ordinal, from, to, at, written, drop)); active.decrementAndGet(); }
        }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); executor.close(); }
}
