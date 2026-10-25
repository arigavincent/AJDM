package io.ajdm.net;

import com.sun.net.httpserver.HttpServer;
import io.ajdm.model.*;
import java.io.InputStream;
import java.net.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarmUpTest {
    @Test void warmUpOpensNoConnectionIsRepeatableAndLeavesTransportsWorking() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes()); exchange.close(); });
        server.start();
        try {
            JdkHttpTransport.warmUp(); JdkHttpTransport.warmUp();
            assertEquals(0, requests.get(), "Warm-up must not send a request");
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
            var request = new TransferRequest(UUID.randomUUID(), uri, Path.of(System.getProperty("java.io.tmpdir"), "warmup.bin").toAbsolutePath(), Map.of(), TransferOptions.defaults());
            try (var transport = new JdkHttpTransport(request); InputStream _ = transport.send(uri, "GET", Map.of(), new CancellationScope()).body()) {
                assertEquals(1, requests.get());
            }
        } finally { server.stop(0); }
    }
}
