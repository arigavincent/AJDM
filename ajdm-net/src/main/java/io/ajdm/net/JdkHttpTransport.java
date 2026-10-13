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
