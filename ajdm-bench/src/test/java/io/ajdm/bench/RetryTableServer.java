package io.ajdm.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Status/identity transitions after a successful probe, isolated from ordinary network faults. */
final class RetryTableServer implements AutoCloseable {
    final byte[] data = new byte[320017];
    final AtomicInteger probes = new AtomicInteger(), transfers = new AtomicInteger();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final String mode;
    private volatile int size = data.length;
    private volatile String etag = "\"v1\"";

    RetryTableServer(String mode) throws IOException {
        this.mode = mode; new java.util.Random(716).nextBytes(data);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers); server.createContext("/file", this::serve); server.start();
    }
    URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"); }
    String hash() throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean head = exchange.getRequestMethod().equals("HEAD"), probe = head || "bytes=0-0".equals(range);
            if (head) probes.incrementAndGet();
            int attempt = probe ? 0 : transfers.incrementAndGet();
            if (mode.equals("WRONG_THEN_500") && attempt == 2
                    || mode.equals("PROBE_WRONG_THEN_500") && head && probes.get() == 2) {
                exchange.sendResponseHeaders(500, -1); return;
            }
            if (mode.startsWith("INITIAL_") && (mode.endsWith("ALWAYS") || probes.get() == 1)) {
                int status = mode.contains("403") ? 403 : 410; exchange.sendResponseHeaders(status, -1); return;
            }
            if (!probe && (mode.startsWith("HTTP_") || mode.startsWith("LATE_HTTP_"))) {
                int status = Integer.parseInt(mode.split("_")[mode.startsWith("LATE_") ? 2 : 1]);
                boolean auth = status == 401 || status == 407;
                boolean authorized = "Bearer supplied".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                if (auth ? !authorized : mode.endsWith("ALWAYS") || attempt == (mode.startsWith("LATE_") ? 2 : 1)) {
                    if (mode.contains("SECONDS")) exchange.getResponseHeaders().set("Retry-After", "1");
                    exchange.sendResponseHeaders(status, -1); return;
                }
            }
            if (!probe && (mode.startsWith("416") || mode.startsWith("LATE_416"))
                    && (mode.endsWith("ALWAYS") || attempt == (mode.startsWith("LATE_") ? 2 : 1))) {
                if (mode.endsWith("SIZE")) size = data.length - 100;
                if (mode.endsWith("ETAG")) etag = "\"v2\"";
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                exchange.sendResponseHeaders(416, -1); return;
            }
            if (!probe && mode.equals("CHANGE") && attempt > 1) etag = "\"v2\"";
            exchange.getResponseHeaders().set("ETag", etag);
            if (head) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(size));
                exchange.sendResponseHeaders(200, -1); return;
            }
            int from = 0, to = size;
            if (range != null) {
                String[] bounds = range.substring(6).split("-"); from = Integer.parseInt(bounds[0]); to = Integer.parseInt(bounds[1]) + 1;
                if (to > size) { exchange.sendResponseHeaders(416, -1); return; }
                boolean wrongPayload = !probe && mode.startsWith("WRONG") && (mode.endsWith("ALWAYS") || attempt == 1)
                        && (!mode.contains("LATE") || attempt > 1);
                boolean wrongProbe = probe && mode.startsWith("PROBE_WRONG") && (mode.endsWith("ALWAYS") || probes.get() == 1);
                int reported = wrongPayload || wrongProbe ? from + 1 : from;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + reported + "-" + (to - 1) + "/" + size);
            }
            exchange.sendResponseHeaders(range == null ? 200 : 206, to - from);
            if (etag.equals("\"v2\"") && mode.equals("CHANGE")) {
                byte[] changed = java.util.Arrays.copyOfRange(data, from, to);
                java.util.Arrays.fill(changed, (byte) 77); exchange.getResponseBody().write(changed);
            } else exchange.getResponseBody().write(data, from, to - from);
        }
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); workers.close(); }
}
