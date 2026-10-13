package io.ajdm.cli;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/** Strict TLS with an explicitly trusted test certificate and SAN; no verification bypass. */
final class MediaHttpsFixture implements AutoCloseable {
    final byte[] data;
    final Path keystore;
    private final HttpsServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final SSLContext previous;
    final AtomicInteger oldRequests = new AtomicInteger(), newRequests = new AtomicInteger();
    final List<Long> starts = new CopyOnWriteArrayList<>();
    volatile boolean expired;
    volatile boolean expireAfterWindow;
    volatile boolean missingHeaders;
    volatile boolean expiredProbeHadCookie;
    volatile String etag = "\"fixture-v1\"";
    MediaHttpsFixture(Path folder, int size, long bytesPerSecond) throws Exception { this(folder, random(size), Map.of(), bytesPerSecond); }
    MediaHttpsFixture(Path folder, Map<String, byte[]> streams, long bytesPerSecond) throws Exception { this(folder, streams.get("/old"), streams, bytesPerSecond); }
    private static byte[] random(int size) { byte[] bytes = new byte[size]; new Random(77).nextBytes(bytes); return bytes; }
    private MediaHttpsFixture(Path folder, byte[] bytes, Map<String, byte[]> streams, long bytesPerSecond) throws Exception {
        data = bytes; keystore = folder.resolve("fixture.p12");
        var generator = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(), "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-only-password", "-keypass", "test-only-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(folder.resolve("keytool.log").toFile()).start();
        if (!generator.waitFor(10, TimeUnit.SECONDS) || generator.exitValue() != 0) { generator.destroyForcibly(); throw new IllegalStateException("Certificate fixture failed"); }
        KeyStore keys = KeyStore.getInstance("PKCS12"); try (var in = Files.newInputStream(keystore)) { keys.load(in, "test-only-password".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "test-only-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        var context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        previous = SSLContext.getDefault(); SSLContext.setDefault(context);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setHttpsConfigurator(new HttpsConfigurator(context)); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = streams.getOrDefault(exchange.getRequestURI().getPath(), data);
                boolean old = exchange.getRequestURI().getPath().equals("/old"); (old ? oldRequests : newRequests).incrementAndGet();
                if (old && expired) {
                    if (exchange.getRequestHeaders().getFirst("Cookie") != null) expiredProbeHadCookie = true;
                    exchange.sendResponseHeaders(403, -1); return;
                }
                if (!"sid=secret".equals(exchange.getRequestHeaders().getFirst("Cookie")) || !"AJDM-extractor-fixture".equals(exchange.getRequestHeaders().getFirst("User-Agent"))) missingHeaders = true;
                exchange.getResponseHeaders().set("ETag", etag); exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                if (exchange.getRequestMethod().equals("HEAD")) { exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length)); exchange.sendResponseHeaders(200, -1); return; }
                if (exchange.getRequestURI().getPath().equals("/invalid")) {
                    exchange.getResponseHeaders().set("Content-Range", "invalid"); exchange.sendResponseHeaders(206, 1); exchange.getResponseBody().write(0); return;
                }
                long start = 0, end = body.length - 1; String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null) {
                    String[] parts = range.substring(6).split("-", -1); start = Long.parseLong(parts[0]); if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + body.length);
                }
                int count = Math.toIntExact(end - start + 1); boolean probe = count == 1; if (!probe) starts.add(start);
                exchange.sendResponseHeaders(range == null ? 200 : 206, count);
                long deadline = System.nanoTime();
                for (int offset = 0; offset < count; offset += 4096) {
                    int chunk = Math.min(4096, count - offset); exchange.getResponseBody().write(body, Math.toIntExact(start) + offset, chunk); exchange.getResponseBody().flush();
                    if (!probe && bytesPerSecond > 0) {
                        deadline += chunk * 1_000_000_000L / bytesPerSecond; long wait = deadline - System.nanoTime(); if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                    }
                }
                if (old && !probe && expireAfterWindow) expired = true;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException e) { /* Intentional client pause/kill closes the payload. */ }
        }); server.start();
    }
    String audioUrl() { return "https://127.0.0.1:" + server.getAddress().getPort() + "/audio?signature=secret"; }
    String url(boolean old) { return "https://127.0.0.1:" + server.getAddress().getPort() + (old ? "/old" : "/new") + "?signature=secret"; }
    @Override public void close() { server.stop(0); workers.close(); SSLContext.setDefault(previous); }
}
