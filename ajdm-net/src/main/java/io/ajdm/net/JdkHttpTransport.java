package io.ajdm.net;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.TransferRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP primitives only. Range planning, retries, buffers, and files belong to AJDM. */
public final class JdkHttpTransport implements HttpTransport {
    private final TransferRequest source;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;
    private final RequestBudget budget;

    public JdkHttpTransport(TransferRequest source) { this(source, RequestBudget.unlimited()); }
    public JdkHttpTransport(TransferRequest source, RequestBudget budget) {
        this.source = source;
        this.budget = budget;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(source.options().connectTimeout())
                .executor(executor).build();
    }
    /**
     * Loads the JDK HTTP client, its default TLS context and its request/response path off the critical path. Cold,
     * building the first transport cost 0.7-0.9 s and the first exchange another ~0.4 s, both inside the user's first
     * download; a resident engine calls this once at construction instead. The one exchange is with a single-use
     * listener on an ephemeral loopback port that is closed straight after, so nothing leaves the machine.
     */
    public static void warmUp() {
        var timeout = java.time.Duration.ofSeconds(2);
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout((int) timeout.toMillis());
            Thread.ofVirtual().name("ajdm-http-warmup-peer").start(() -> {
                try (var peer = listener.accept()) {
                    peer.setSoTimeout((int) timeout.toMillis());
                    var in = peer.getInputStream(); int matched = 0;
                    for (int b; matched < 4 && (b = in.read()) >= 0; ) matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : b == '\r' ? 1 : 0;
                    peer.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-0/1\r\nContent-Length: 1\r\n"
                            + "Connection: close\r\n\r\nx").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                } catch (IOException ignored) { /* The client side reports nothing either; this is only a warm-up. */ }
            });
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/")).timeout(timeout)
                    .method("GET", HttpRequest.BodyPublishers.noBody()).header("Accept-Encoding", "identity").header("Range", "bytes=0-0").build();
            try (InputStream body = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) { body.readAllBytes(); }
        } catch (IOException ignored) { /* A failed warm-up leaves the first download to load the stack itself. */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { client.shutdownNow(); }
    }
    /** Independent pool for one engine lane, sharing only the active-response budget. */
    public JdkHttpTransport fork() { return new JdkHttpTransport(source, budget); }
    /** Cancel this pool without waiting on a monitor thread. The owning worker later closes it. */
    public void abort() { client.shutdownNow(); }
    @Override public HttpResponse<InputStream> send(URI uri, String method, Map<String, String> engineHeaders,
                                                   CancellationScope cancellation) throws IOException, InterruptedException {
        URI current = uri;
        for (int redirects = 0; redirects <= 10; redirects++) {
            cancellation.check();
            var builder = HttpRequest.newBuilder(current).timeout(source.options().requestTimeout())
                    .method(method, HttpRequest.BodyPublishers.noBody()).header("Accept-Encoding", "identity");
            // Drop every caller-supplied header across origins, including arbitrary API-key headers.
            if (sameOrigin(source.uri(), current)) source.headers().forEach(builder::header);
            if (!sameOrigin(source.uri(), current) || source.headers().keySet().stream()
                    .noneMatch(name -> name.equalsIgnoreCase("User-Agent"))) builder.header("User-Agent", "AJDM/0.1");
            engineHeaders.forEach(builder::header);
            RequestBudget.Lease lease = budget.acquire(current, cancellation);
            HttpResponse<InputStream> response;
            try {
                var original = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                response = new LeasedResponse(original, new java.io.FilterInputStream(original.body()) {
                    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                    @Override public void close() throws IOException {
                        if (closed.compareAndSet(false, true)) { try { super.close(); } finally { lease.close(); } }
                    }
                });
                cancellation.register(response.body());
            } catch (IOException | InterruptedException | RuntimeException e) { lease.close(); throw e; }
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return response;
            try (InputStream _ = response.body()) {
                String location = response.headers().firstValue("Location").orElseThrow(
                        () -> new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Redirect has no Location"));
                URI next;
                try { next = current.resolve(location); }
                catch (IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid redirect"); }
                if (!("http".equalsIgnoreCase(next.getScheme()) || "https".equalsIgnoreCase(next.getScheme()))
                        || next.getHost() == null || next.getUserInfo() != null
                        || ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme()))) {
                    throw new DownloadException(ErrorCode.INVALID_URL, "Unsafe redirect");
                }
                // Fragments are browser metadata, not part of an HTTP request.
                if (next.getFragment() != null) next = URI.create(next.toString().split("#", 2)[0]);
                current = next;
            } finally { cancellation.unregister(response.body()); }
        }
        throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Too many redirects");
    }
    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    private record LeasedResponse(HttpResponse<InputStream> original, InputStream body) implements HttpResponse<InputStream> {
        @Override public int statusCode() { return original.statusCode(); }
        @Override public HttpRequest request() { return original.request(); }
        @Override public java.util.Optional<HttpResponse<InputStream>> previousResponse() { return original.previousResponse(); }
        @Override public java.net.http.HttpHeaders headers() { return original.headers(); }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return original.sslSession(); }
        @Override public URI uri() { return original.uri(); }
        @Override public HttpClient.Version version() { return original.version(); }
    }
    @Override public void close() {
        client.shutdownNow();
        executor.shutdownNow();
        executor.close();
    }
}
