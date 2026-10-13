package io.ajdm.ipc;

import com.google.gson.JsonObject;
import io.ajdm.core.CoreService;
import io.ajdm.model.*;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class LoopbackIntegrationTest {
    @TempDir Path temp;
    private Path endpoint() throws Exception {
        var folder = temp.resolve("private"); PrivateFiles.directory(folder); return folder.resolve("core.endpoint");
    }
    @Test void authenticatedClientConnectsAndDescriptorIsRemovedOnShutdown() throws Exception {
        Path endpoint = endpoint();
        try (var core = new CoreService(temp.resolve("state/state.db")); var server = new IpcServer(endpoint, core, Duration.ofSeconds(2), true)) {
            var client = new IpcClient(endpoint, true);
            assertTrue(client.call("ping", new JsonObject()).has("version"));
            assertEquals(0, client.call("list", new JsonObject()).getAsJsonArray("downloads").size());
            PrivateFiles.check(endpoint, false);
            assertThrows(DownloadException.class, () -> new IpcServer(endpoint, core, Duration.ofSeconds(2), true));
            assertTrue(client.call("shutdown", new JsonObject()).get("stopping").getAsBoolean());
            server.await();
        }
        assertFalse(Files.exists(endpoint));
    }
    @Test void missingAndWrongCapabilitiesCannotReachDispatcherOrStopCore() throws Exception {
        Path endpoint = endpoint();
        try (var core = new CoreService(temp.resolve("state/state.db")); var server = new IpcServer(endpoint, core, Duration.ofSeconds(2), true)) {
            assertNotNull(server);
            var descriptor = LoopbackEndpoint.read(endpoint);
            for (String token : new String[]{"", "0".repeat(64)}) {
                try (var channel = SocketChannel.open(java.net.StandardProtocolFamily.INET)) {
                    Frames.connect(channel, descriptor.address(), Duration.ofSeconds(1));
                    var request = Dispatcher.envelope("test"); request.addProperty("type", "shutdown"); request.add("payload", new JsonObject());
                    if (!token.isEmpty()) request.addProperty("auth", token);
                    Frames.write(channel, request, Duration.ofSeconds(2));
                    assertFalse(Frames.read(channel, Duration.ofSeconds(2)).get("ok").getAsBoolean());
                }
                assertTrue(new IpcClient(endpoint, true).call("ping", new JsonObject()).has("version"));
            }
        }
    }
    @Test void staleDescriptorRecoversWithNewCapabilityButUnrelatedFilesAreProtected() throws Exception {
        Path endpoint = endpoint(); String old;
        try (var owner = LoopbackEndpoint.acquire(endpoint)) { owner.publish(endpoint, 12345); old = LoopbackEndpoint.read(endpoint).token(); }
        try (var owner = LoopbackEndpoint.acquire(endpoint)) { owner.publish(endpoint, 12346); assertNotEquals(old, LoopbackEndpoint.read(endpoint).token()); }
        Files.writeString(endpoint, "unrelated user file");
        assertThrows(java.io.IOException.class, () -> LoopbackEndpoint.acquire(endpoint));
        assertEquals("unrelated user file", Files.readString(endpoint));
    }
    @Test void downloadPauseReopenResumeProducesIdenticalBytesThroughLocalControl() throws Exception {
        Path endpoint = endpoint(); Path db = temp.resolve("state/state.db"); String id;
        try (var fixture = new io.ajdm.bench.FaultyRangeServer(2 * 1024 * 1024 + 17, io.ajdm.bench.FaultyRangeServer.Mode.SLOW)) {
            try (var core = new CoreService(db); var server = new IpcServer(endpoint, core, Duration.ofSeconds(5), true)) {
                assertNotNull(server); var client = new IpcClient(endpoint, true); var add = new JsonObject();
                add.addProperty("url", fixture.uri().toString()); add.addProperty("directory", temp.resolve("downloads").toString());
                add.addProperty("filename", "verified.bin"); add.addProperty("sha256", fixture.sha256()); add.addProperty("connections", 2);
                id = client.call("add", add).get("downloadId").getAsString(); assertTrue(fixture.awaitTransfer());
                var control = new JsonObject(); control.addProperty("downloadId", id); client.call("pause", control);
                assertEquals("PAUSED", client.call("list", new JsonObject()).getAsJsonArray("downloads").get(0).getAsJsonObject().get("state").getAsString());
            }
            try (var core = new CoreService(db); var server = new IpcServer(endpoint, core, Duration.ofSeconds(5), true)) {
                assertNotNull(server); var client = new IpcClient(endpoint, true); var control = new JsonObject(); control.addProperty("downloadId", id);
                client.call("resume", control); long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                String state;
                do {
                    state = client.call("list", new JsonObject()).getAsJsonArray("downloads").get(0).getAsJsonObject().get("state").getAsString();
                    if (state.equals("COMPLETED")) break;
                    assertFalse(java.util.Set.of("FAILED", "PAUSED", "CANCELED").contains(state)); Thread.sleep(30);
                } while (System.nanoTime() < until);
                assertEquals("COMPLETED", state);
                assertArrayEquals(fixture.data(), Files.readAllBytes(temp.resolve("downloads/verified.bin")));
            }
        }
    }
    @Test void overlongDescriptorFailsBeforeParsing() throws Exception {
        Path endpoint = endpoint();
        Files.createFile(endpoint, PrivateFiles.attributes(endpoint.getParent(), false));
        Files.writeString(endpoint, "x".repeat(1025));
        assertThrows(java.io.IOException.class, () -> LoopbackEndpoint.read(endpoint));
    }
}
