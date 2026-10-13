package io.ajdm.store;

import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionsStoreTest {
    @TempDir Path temp;
    TransferRequest request(String name) { return new TransferRequest(UUID.randomUUID(), URI.create("https://cdn.test/file"), temp.resolve(name), Map.of("User-Agent", "fixture"), TransferOptions.defaults().withAdaptiveParallelism(4, 1024 * 1024)); }
    void paused(SqliteDownloadStore store, TransferRequest r) throws Exception {
        store.create(r, temp.resolve(r.id() + ".part")); store.state(r.id(), DownloadState.PROBING, null);
        store.describe(r.id(), new ProbeResult(r.uri(), 100, true, "\"v1\"", null)); store.state(r.id(), DownloadState.DOWNLOADING, null);
        store.checkpoint(r.id(), List.of(new ByteRange(0, 50)), DownloadState.PAUSED);
    }
    @Test void connectionChangePersistsWithoutChangingCoverageIdentityOrMode() throws Exception {
        var r = request("output"); Path db = temp.resolve("state/ajdm.db");
        try (var store = new SqliteDownloadStore(db)) {
            paused(store, r); var before = store.get(r.id()); store.connections(List.of(r.id()), 8); var after = store.get(r.id());
            assertEquals(8, after.request().options().connections()); assertTrue(after.request().options().adaptive());
            assertEquals(before.completed(), after.completed()); assertEquals(before.probe(), after.probe()); assertEquals(before.partialPath(), after.partialPath()); assertEquals(before.state(), after.state());
        }
        try (var store = new SqliteDownloadStore(db)) { assertEquals(8, store.get(r.id()).request().options().connections()); assertEquals(50, store.get(r.id()).durableBytes()); }
    }
    @Test void secondUpdateFailureRollsBackAllConnectionCounts() throws Exception {
        var a = request("a"); var b = request("b"); Path db = temp.resolve("state/ajdm.db");
        try (var store = new SqliteDownloadStore(db)) {
            paused(store, a); paused(store, b);
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = conn.createStatement()) {
                sql.execute("CREATE TRIGGER reject_connections BEFORE UPDATE OF connections ON download WHEN NEW.id='" + b.id() + "' BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(DownloadException.class, () -> store.connections(List.of(a.id(), b.id()), 8));
            assertEquals(4, store.get(a.id()).request().options().connections()); assertEquals(4, store.get(b.id()).request().options().connections());
        }
    }
    @Test void runningCanceledUnknownAndInvalidCountsCannotPartiallyChangeSavedOptions() throws Exception {
        var a = request("a"); var b = request("b");
        try (var store = new SqliteDownloadStore(temp.resolve("state/ajdm.db"))) {
            paused(store, a); paused(store, b); store.state(b.id(), DownloadState.PROBING, null);
            assertThrows(DownloadException.class, () -> store.connections(List.of(a.id(), b.id()), 8));
            store.state(b.id(), DownloadState.CANCELED, ErrorCode.CANCELED);
            assertThrows(DownloadException.class, () -> store.connections(List.of(a.id(), b.id()), 8));
            assertThrows(DownloadException.class, () -> store.connections(List.of(a.id(), UUID.randomUUID()), 8));
            for (int count : new int[]{0, 33}) assertThrows(IllegalArgumentException.class, () -> store.connections(List.of(a.id()), count));
            assertEquals(4, store.get(a.id()).request().options().connections());
        }
    }
}
