package io.ajdm.ipc;

import com.google.gson.JsonObject;
import io.ajdm.bench.FaultyRangeServer;
import io.ajdm.core.CoreService;
import io.ajdm.model.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
@SuppressWarnings("try")
class RemovalIntegrationTest {
    @TempDir Path temp;
    @Test void stoppedRemovalKeepsOrDeletesFilesDurablyWhileActiveRemovalIsRejected() throws Exception {
        Path database = temp.resolve("state/ajdm.db"), socket = temp.resolve("ipc/socket");
        try (var source = new FaultyRangeServer(8 * 1024 * 1024, FaultyRangeServer.Mode.SLOW)) {
            try (var core = new CoreService(database); var server = new IpcServer(socket, core)) {
                var client = new IpcClient(socket); var request = new JsonObject();
                request.addProperty("url", source.uri().toString()); request.addProperty("directory", temp.resolve("downloads").toString());
                request.addProperty("connections", 1);
                String id = client.call("add", request).get("downloadId").getAsString(); assertTrue(source.awaitTransfer());
                var remove = new JsonObject(); remove.addProperty("downloadId", id);
                assertEquals(ErrorCode.INVALID_STATE, assertThrows(DownloadException.class, () -> client.call("remove", remove)).code());
                client.call("pause", remove);
                Path partial; try (var paths = Files.list(temp.resolve("downloads"))) { partial = paths.filter(path -> path.toString().endsWith(".ajdmpart")).findFirst().orElseThrow(); }
                int requests = source.requests();
                assertEquals(id, client.call("remove", remove).get("downloadId").getAsString());
                assertTrue(client.call("list", new JsonObject()).getAsJsonArray("downloads").isEmpty()); assertTrue(Files.exists(partial));
                assertEquals(requests, source.requests());
                id = client.call("add", request).get("downloadId").getAsString(); remove.addProperty("downloadId", id);
                client.call("pause", remove); remove.addProperty("deleteFiles", true);
                client.call("remove", remove);
                try (var paths = Files.list(temp.resolve("downloads"))) { assertEquals(java.util.List.of(partial), paths.toList()); }
                assertTrue(client.call("list", new JsonObject()).getAsJsonArray("downloads").isEmpty());
                var unsafe = new JsonObject(); unsafe.addProperty("downloadId", id); unsafe.addProperty("path", "/some/file");
                assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class, () -> client.call("remove", unsafe)).code());
            }
            try (var core = new CoreService(database)) { assertTrue(core.list().isEmpty()); }
        }
    }
}
