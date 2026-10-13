package io.ajdm.bench;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class TlsVerificationTest {
    @TempDir Path directory;
    @Test void untrustedCertificateFailsWithoutPayloadOrSilentTlsRetry() throws Exception {
        Path keystore = directory.resolve("fixture.p12");
        var generator = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "test-only-password", "-keypass", "test-only-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        try { assertTrue(generator.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, generator.exitValue()); }
        finally { if (generator.isAlive()) generator.destroyForcibly(); }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) { keys.load(input, "test-only-password".toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, "test-only-password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, null);
        var calls = new AtomicInteger();
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setHttpsConfigurator(new HttpsConfigurator(context));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers); server.createContext("/file", exchange -> {
                try (exchange) { calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); }
            }); server.start();
            try (var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
                var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                        4096, 2, 65536, 2, Duration.ZERO, Duration.ZERO, null);
                var handle = engine.start(new TransferRequest(UUID.randomUUID(), URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/file"),
                        directory.resolve("result.bin"), Map.of(), options));
                var failure = (DownloadException) assertThrows(ExecutionException.class, () -> handle.completion().toCompletableFuture().get()).getCause();
                assertEquals("TLS_VERIFICATION_FAILED", failure.code().name());
                assertEquals(DownloadState.FAILED, handle.progress().state()); assertEquals(0, calls.get());
                assertEquals(0, handle.progress().receivedBytes()); assertFalse(Files.exists(directory.resolve("result.bin")));
                assertEquals(failure.code(), engine.downloads().getFirst().error());
            } finally { server.stop(0); }
        }
    }
}
