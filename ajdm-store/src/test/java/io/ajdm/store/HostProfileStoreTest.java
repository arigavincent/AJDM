package io.ajdm.store;

import io.ajdm.model.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HostProfileStoreTest {
    @TempDir Path temp;
    @Test void hintsPersistExpireAndIgnoreClockRollback() throws Exception {
        var db = temp.resolve("state.db"); var key = "a".repeat(64); long now = 2000000000000L;
        var hint = new HostProfile(8, 123456, now);
        try (var store = new SqliteDownloadStore(db)) { store.saveHostProfile(key, hint); }
        try (var store = new SqliteDownloadStore(db)) {
            assertEquals(hint, store.hostProfile(key, now).orElseThrow());
            assertTrue(store.hostProfile(key, now - 1).isEmpty());
            assertTrue(store.hostProfile(key, now + Duration.ofDays(30).toMillis() + 1).isEmpty());
            assertTrue(store.hostProfile("b".repeat(64), now).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.hostProfile("https://host/path?secret", now));
        }
    }
    @Test void hintTableIsBoundedAndRetainsNewestObservations() throws Exception {
        try (var store = new SqliteDownloadStore(temp.resolve("state.db"))) {
            for (int i = 0; i < 513; i++) store.saveHostProfile(String.format("%064x", i), new HostProfile(4, 1000, 1000 + i));
            assertTrue(store.hostProfile(String.format("%064x", 0), 2000).isEmpty());
            for (int i = 1; i < 513; i++) assertTrue(store.hostProfile(String.format("%064x", i), 2000).isPresent());
        }
    }
    @Test void versionEightMigrationPreservesCoverageAndPreviousOptions() throws Exception {
        var db = temp.resolve("legacy.db");
        var options = TransferOptions.defaults().withAdaptiveParallelism(8, 1048576);
        var request = new TransferRequest(UUID.randomUUID(), URI.create("https://example.test/file"), temp.resolve("file"), Map.of(), options);
        try (var store = new SqliteDownloadStore(db)) {
            store.create(request, temp.resolve("file.part"));
            store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), java.util.List.of(new ByteRange(25, 50)), DownloadState.PAUSED);
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE download DROP COLUMN initial_connections");
            sql.execute("DROP TABLE host_profile"); sql.execute("PRAGMA user_version=8");
        }
        try (var store = new SqliteDownloadStore(db)) {
            var saved = store.get(request.id());
            assertEquals(options.withInitialConnections(2), saved.request().options());
            assertEquals(java.util.List.of(new ByteRange(25, 50)), saved.completed());
            assertEquals(request.target(), saved.request().target());
            assertEquals("\"v1\"", saved.probe().etag());
            assertEquals(DownloadState.PAUSED, saved.state());
        }
    }
    @Test void initialCountSurvivesRestartAndMaximumChangeIndependently() throws Exception {
        var db = temp.resolve("state.db"); var options = TransferOptions.defaults().withAdaptiveParallelism(16, 65536).withInitialConnections(8);
        var request = new TransferRequest(UUID.randomUUID(), URI.create("https://example.test/file"), temp.resolve("file"), Map.of(), options);
        try (var store = new SqliteDownloadStore(db)) { store.create(request, temp.resolve("file.part")); store.connections(java.util.List.of(request.id()), 2); }
        try (var store = new SqliteDownloadStore(db)) {
            assertEquals(8, store.get(request.id()).request().options().initialConnections());
            assertEquals(2, store.get(request.id()).request().options().connections());
        }
    }
}
