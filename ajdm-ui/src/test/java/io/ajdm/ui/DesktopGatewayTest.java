package io.ajdm.ui;

import com.google.gson.JsonObject;
import io.ajdm.bench.FaultyRangeServer;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.IpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
@SuppressWarnings("try")
class DesktopGatewayTest {
    @TempDir Path temp;
    @Test void desktopControlsPreserveIdentityAndSavedProgressWhileCoreOutlivesClients() throws Exception {
        Path socket = temp.resolve("ipc/ajdm.sock");
        try (var source = new FaultyRangeServer(3 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW);
             var core = new CoreService(temp.resolve("state/ajdm.db")); var server = new IpcServer(socket, core)) {
            var client = new DesktopGateway(socket);
            var payload = FormRequests.base(source.uri().toString(), temp.resolve("downloads").toString(), 1);
            FormRequests.filename(payload, "test.bin"); FormRequests.checksum(payload, source.sha256());
            UUID id = UUID.fromString(client.call("add", payload).get("downloadId").getAsString());
            assertTrue(source.awaitTransfer());
            long bytesDeadline = System.nanoTime() + 5_000_000_000L;
            while (client.downloads(System.nanoTime()).getFirst().written() < 32768 && System.nanoTime() < bytesDeadline) Thread.sleep(10);
            client.control("pause", id, null);
            var stopped = client.downloads(System.nanoTime()).getFirst();
            assertEquals("PAUSED", stopped.state()); assertEquals(id, stopped.id()); assertEquals(0, stopped.reading().bytesPerSecond());
            assertTrue(stopped.durable() > 0);
            new DesktopGateway(socket).control("resume", id, 8);
            long deadline = System.nanoTime() + 12_000_000_000L;
            DownloadRow row;
            do { row = new DesktopGateway(socket).downloads(System.nanoTime()).getFirst(); if (row.state().equals("COMPLETED")) break; Thread.sleep(20); } while (System.nanoTime() < deadline);
            assertEquals("COMPLETED", row.state()); assertEquals(id, row.id());
            assertArrayEquals(source.data(), Files.readAllBytes(Path.of(row.target())));
            assertThrows(IllegalArgumentException.class, () -> client.control("cancel", id, 8));
        }
    }
    @Test void missingCoreProducesCatalogFailureWithoutCreatingState() {
        var client = new DesktopGateway(temp.resolve("missing/socket"));
        var failure = assertThrows(io.ajdm.model.DownloadException.class, () -> client.call("list", new JsonObject()));
        assertEquals(io.ajdm.model.ErrorCode.IPC_UNAVAILABLE, failure.code()); assertFalse(Files.exists(temp.resolve("missing")));
    }
}
