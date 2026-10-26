package io.ajdm.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ajdm.model.ByteRange;
import io.ajdm.model.DownloadSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Expiration is a status change, never an instruction to another downloader. */
final class ExpiringSourceServer implements AutoCloseable {
    final byte[] data = new byte[1024 * 1024 + 19];
    final List<ByteRange> replacementRanges = new CopyOnWriteArrayList<>();
    final AtomicInteger oldPayloads = new AtomicInteger(), replacementPayloads = new AtomicInteger();
    final AtomicInteger unauthorized = new AtomicInteger();
    enum Fault { NONE, TRUNCATE_ONCE, TRUNCATE_ALWAYS, STALL_ONCE, STALL_ALWAYS, THROTTLE_ONCE }
    volatile Fault replacementFault = Fault.NONE;
    private final AtomicInteger faultRequests = new AtomicInteger();
    volatile boolean initiallyExpired, replacementExpired, replacementRangesSupported = true, slowReplacement;
    volatile int rejection = 403, successfulOldWindows = 4, replacementSize;
    volatile String oldEtag = "\"stable-file\"", replacementEtag = "\"stable-file\"";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    ExpiringSourceServer() throws IOException {
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + i / 17);
        replacementSize = data.length;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor); server.createContext("/old", exchange -> serve(exchange, false));
        server.createContext("/new", exchange -> serve(exchange, true)); server.start();
    }
    URI uri(boolean replacement) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + (replacement ? "/new" : "/old"));
    }
    DownloadSource replacement() { return new DownloadSource(uri(true), Map.of("Authorization", "Bearer fresh-secret", "User-Agent", "AJDM-refresh-test")); }
    String sha256() throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private void serve(HttpExchange exchange, boolean replacement) throws IOException {
        try (exchange) {
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean head = exchange.getRequestMethod().equals("HEAD");
            boolean payload = !head && !"bytes=0-0".equals(range);
            if (replacement && (!"Bearer fresh-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || exchange.getRequestHeaders().containsKey("X-Old-Credential"))) {
                unauthorized.incrementAndGet(); exchange.sendResponseHeaders(401, -1); return;
            }
            if ((!replacement && (initiallyExpired || payload && oldPayloads.getAndIncrement() >= successfulOldWindows))
                    || replacement && replacementExpired) {
                exchange.sendResponseHeaders(rejection, -1); return;
            }
            int faultRequest = replacement && payload ? faultRequests.incrementAndGet() : 0;
            if (replacement && payload && replacementFault == Fault.THROTTLE_ONCE && faultRequest == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0"); exchange.sendResponseHeaders(429, -1); return;
            }
            int length = replacement ? replacementSize : data.length;
            String etag = replacement ? replacementEtag : oldEtag;
            if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Last-Modified", "Wed, 01 Jan 2025 00:00:00 GMT");
            if (head) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
                exchange.sendResponseHeaders(200, -1); return;
            }
            int from = 0, to = length;
            int status = 200;
            if (range != null && (!replacement || replacementRangesSupported)) {
                String[] parts = range.substring(6).split("-", -1);
                from = Integer.parseInt(parts[0]); to = Math.min(length, Integer.parseInt(parts[1]) + 1);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (to - 1) + "/" + length);
                status = 206;
            }
            if (replacement && payload) { replacementPayloads.incrementAndGet(); replacementRanges.add(new ByteRange(from, to)); }
            exchange.sendResponseHeaders(status, to - from);
            boolean truncate = replacement && payload && (replacementFault == Fault.TRUNCATE_ALWAYS
                    || replacementFault == Fault.TRUNCATE_ONCE && faultRequest == 1);
            boolean stall = replacement && payload && (replacementFault == Fault.STALL_ALWAYS
                    || replacementFault == Fault.STALL_ONCE && faultRequest == 1);
            int bodyEnd = truncate ? from + (to - from) / 2 : to;
            for (int offset = from; offset < bodyEnd; offset += 4096) {
                int count = Math.min(4096, bodyEnd - offset);
                exchange.getResponseBody().write(data, offset, count);
                if (stall && offset == from) {
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (replacement && slowReplacement && payload) {
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
            // Deliver the headers and the first half before the cut. Unflushed, a tiny truncated response never leaves
            // the server, and the JDK client then silently resends the GET as if a stale keep-alive connection had closed.
            if (truncate) exchange.getResponseBody().flush();
        } catch (IOException ignored) { /* The engine closes sibling responses on expiration. */ }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); executor.close(); }
}
