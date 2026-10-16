package io.ajdm.bench;

import com.sun.net.httpserver.HttpServer;
import io.ajdm.model.*;
import io.ajdm.net.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ProbeMetadataTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings = {"get-wins", "head-only", "head-rejected", "empty", "redirect"})
    void actualProbeCapturesMetadataAndSafeFinalUrlFallback(String mode) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    boolean head = exchange.getRequestMethod().equals("HEAD");
                    if (mode.equals("redirect") && exchange.getRequestURI().getPath().equals("/entry")) {
                        exchange.getResponseHeaders().set("Location", "/final%20file.bin"); exchange.sendResponseHeaders(302, -1); return;
                    }
                    if (mode.equals("head-rejected") && head) { exchange.sendResponseHeaders(405, -1); return; }
                    exchange.getResponseHeaders().set("ETag", "\"v1\"");
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    if (!mode.equals("redirect")) {
                        if (head) exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=head.bin");
                        else if (!mode.equals("head-only")) exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=plain.bin; filename*=UTF-8''caf%C3%A9.zip");
                    }
                    if (head) {
                        exchange.getResponseHeaders().set("Content-Length", mode.equals("empty") ? "0" : "42");
                        exchange.sendResponseHeaders(200, -1);
                    } else if (mode.equals("empty")) {
                        // A real empty representation cannot satisfy a 1-byte range; the probe falls back to HEAD.
                        exchange.getResponseHeaders().set("Content-Range", "bytes */0"); exchange.sendResponseHeaders(416, -1);
                    } else {
                        assertEquals("bytes=0-0", exchange.getRequestHeaders().getFirst("Range"));
                        exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/42"); exchange.sendResponseHeaders(206, 1);
                        exchange.getResponseBody().write(7);
                    }
                }
            });
            server.start();
            try {
                URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/entry?signature=secret");
                try (var transport = new JdkHttpTransport(TransferRequest.of(uri, directory.resolve("output.bin")))) {
                    var probe = new Probe(transport).inspect(uri, new CancellationScope());
                    // The probe is a single ranged GET; a name a server only puts on HEAD is not consulted then.
                    assertEquals(mode.equals("empty") ? "head.bin" : mode.equals("head-only") ? "entry"
                            : mode.equals("redirect") ? "final file.bin" : "café.zip", probe.filename());
                    assertEquals("application/octet-stream", probe.contentType());
                    assertEquals(mode.equals("empty") ? 0 : 42, probe.contentLength());
                    assertFalse(probe.toString().contains("signature=secret"));
                    if (mode.equals("redirect")) assertTrue(probe.finalUri().getRawPath().contains("final%20file.bin"));
                }
            } finally { server.stop(0); executor.shutdownNow(); }
        }
    }
}
