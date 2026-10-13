package io.ajdm.bench;

import com.sun.net.httpserver.HttpServer;
import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RedirectIntegrationTest {
    @TempDir Path directory;
    @Test void crossOriginRedirectDoesNotLeakCallerHeaders() throws Exception {
        Map<String, String> secrets = Map.of("Authorization", "Bearer test-secret", "Cookie", "sid=test-cookie", "X-Api-Key", "test-key");
        try (var file = new FaultyRangeServer(200_003, FaultyRangeServer.Mode.NORMAL);
             var redirect = new RedirectServer(file.uri()); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var request = new TransferRequest(UUID.randomUUID(), redirect.uri(), directory.resolve("file.bin"), secrets, TransferOptions.defaults());
            var result = engine.start(request).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertArrayEquals(file.data(), Files.readAllBytes(result.target()));
            for (String name : secrets.keySet()) {
                assertTrue(hasHeader(redirect.headers.get(), name), "Original origin receives authorized headers");
                assertFalse(hasHeader(file.lastHeaders(), name), "Redirected origin must not receive " + name);
            }
        }
    }
    @Test void redirectToNonHttpSchemeIsRejected() throws Exception {
        try (var redirect = new RedirectServer(URI.create("file:///etc/passwd")); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var failure = assertThrows(ExecutionException.class,
                    () -> engine.start(TransferRequest.of(redirect.uri(), directory.resolve("file.bin")))
                            .completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(ErrorCode.INVALID_URL, ((DownloadException) failure.getCause()).code());
            assertFalse(Files.exists(directory.resolve("file.bin")));
        }
    }
    private static boolean hasHeader(Map<String, List<String>> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }
    private static final class RedirectServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>(Map.of());
        RedirectServer(URI destination) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/redirect", exchange -> {
                try (exchange) {
                    headers.set(Map.copyOf(exchange.getRequestHeaders()));
                    exchange.getResponseHeaders().set("Location", destination.toString());
                    exchange.sendResponseHeaders(302, -1);
                }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); executor.close(); }
    }
}
