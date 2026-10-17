package io.ajdm.bench;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * HTTPS origin for HLS tests: playlists and resources by path, Range support, per-path request counts and injected
 * faults. Strict TLS with an explicitly trusted test certificate; no verification bypass.
 */
final class HlsFixture implements AutoCloseable {
    final Map<String, byte[]> resources = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    /** Path to number of upcoming requests answered with the given status instead of the body. */
    final Map<String, int[]> faults = new ConcurrentHashMap<>();
    volatile long bytesPerSecond;
    private final HttpsServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final SSLContext previous;

    HlsFixture(Path folder) throws Exception {
        Path keystore = folder.resolve("hls-fixture.p12");
        var generator = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(), "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-only-password", "-keypass", "test-only-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(folder.resolve("keytool.log").toFile()).start();
        if (!generator.waitFor(20, TimeUnit.SECONDS) || generator.exitValue() != 0) { generator.destroyForcibly(); throw new IllegalStateException("Certificate fixture failed"); }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) { keys.load(in, "test-only-password".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "test-only-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        var context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        previous = SSLContext.getDefault(); SSLContext.setDefault(context);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context)); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                requests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
                int[] fault = faults.get(path);
                if (fault != null) synchronized (fault) {
                    if (fault[0] > 0) { fault[0]--; exchange.sendResponseHeaders(fault[1], -1); return; }
                }
                byte[] body = resources.get(path);
                if (body == null) { exchange.sendResponseHeaders(404, -1); return; }
                long start = 0, end = body.length - 1;
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null) {
                    String[] parts = range.substring(6).split("-", -1);
                    start = Long.parseLong(parts[0]); if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + body.length);
                }
                int count = Math.toIntExact(end - start + 1);
                exchange.sendResponseHeaders(range == null ? 200 : 206, count);
                long deadline = System.nanoTime();
                for (int offset = 0; offset < count; offset += 4096) {
                    int chunk = Math.min(4096, count - offset);
                    exchange.getResponseBody().write(body, Math.toIntExact(start) + offset, chunk);
                    if (bytesPerSecond > 0) {
                        exchange.getResponseBody().flush();
                        deadline += chunk * 1_000_000_000L / bytesPerSecond;
                        long wait = deadline - System.nanoTime(); if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException e) { /* Canceled clients close bodies. */ }
        });
        server.start();
    }

    String origin() { return "https://127.0.0.1:" + server.getAddress().getPort(); }
    void text(String path, String text) { resources.put(path, text.getBytes(StandardCharsets.UTF_8)); }
    int requests(String path) { var count = requests.get(path); return count == null ? 0 : count.get(); }
    void fail(String path, int times, int status) { faults.put(path, new int[]{times, status}); }

    /** A transport-stream style playlist of {@code count} parts with varied sizes; returns their concatenation. */
    byte[] segments(String directory, int count, String query) {
        var random = new Random(count * 31L + directory.hashCode());
        var playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n");
        var joined = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] part = new byte[20_000 + random.nextInt(60_000)];
            random.nextBytes(part);
            resources.put(directory + "/seg-" + i + ".ts", part);
            joined.writeBytes(part);
            playlist.append("#EXTINF:4.0,\nseg-").append(i).append(".ts").append(query).append('\n');
        }
        playlist.append("#EXT-X-ENDLIST\n");
        text(directory + "/index.m3u8", playlist.toString());
        return joined.toByteArray();
    }

    @Override public void close() { server.stop(0); workers.close(); SSLContext.setDefault(previous); }
}
