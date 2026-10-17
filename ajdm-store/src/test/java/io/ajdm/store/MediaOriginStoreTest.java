package io.ajdm.store;

import io.ajdm.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaOriginStoreTest {
    @TempDir Path temp;
    private TransferRequest request() { return new TransferRequest(UUID.randomUUID(), URI.create("https://cdn.test/video?signature=private"),
            temp.resolve("result.mp4"), Map.of("Cookie", "sid=secret", "User-Agent", "Agent"), TransferOptions.defaults()); }
    private MediaOrigin origin() { return new MediaOrigin(URI.create("https://page.test/watch?token=private"), "video", "18", "mp4", "avc1", "mp4a"); }
    @Test void originSurvivesReopenWithoutPersistingCredentials() throws Exception {
        Path database = temp.resolve("state/ajdm.db"); var request = request(); var origin = origin();
        try (var store = new SqliteDownloadStore(database)) { store.create(request, temp.resolve("partial"), origin); }
        try (var store = new SqliteDownloadStore(database)) {
            assertEquals(origin, store.mediaOrigin(request.id()).orElseThrow()); assertFalse(store.get(request.id()).request().headers().containsKey("Cookie"));
            assertFalse(origin.toString().contains("private"));
        }
    }
    @Test void failedOriginInsertRollsBackDownloadAndHeadersTogether() throws Exception {
        Path database = temp.resolve("state/ajdm.db"); var request = request();
        try (var store = new SqliteDownloadStore(database)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_origin BEFORE INSERT ON media_origin BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(DownloadException.class, () -> store.create(request, temp.resolve("partial"), origin()));
            assertFalse(store.exists(request.id())); assertTrue(store.mediaOrigin(request.id()).isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM download_header")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
    @Test void versionFiveMigrationPreservesOrdinaryFileWithoutInventingMediaOrigin() throws Exception {
        Path database = temp.resolve("state/ajdm.db"); var request = request();
        try (var store = new SqliteDownloadStore(database)) { store.create(request, temp.resolve("partial")); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("DROP TABLE media_origin"); sql.execute("ALTER TABLE download DROP COLUMN initial_connections"); sql.execute("PRAGMA user_version=5");
        }
        try (var store = new SqliteDownloadStore(database)) {
            assertEquals(request.uri(), store.get(request.id()).request().uri()); assertTrue(store.mediaOrigin(request.id()).isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next()); assertEquals(10, rows.getInt(1));
            }
        }
    }
    @Test void hlsDeliverySurvivesReopenAndVersionNineOriginsStayFiles() throws Exception {
        Path database = temp.resolve("state/ajdm.db"); var file = request(); var stream = request();
        var hls = new MediaOrigin(URI.create("https://page.test/watch"), "video", "hls-720", "mp4", "avc1", "mp4a", true);
        try (var store = new SqliteDownloadStore(database)) { store.create(file, temp.resolve("p1"), origin()); store.create(stream, temp.resolve("p2"), hls); }
        try (var store = new SqliteDownloadStore(database)) {
            assertTrue(store.mediaOrigin(stream.id()).orElseThrow().hls()); assertFalse(store.mediaOrigin(file.id()).orElseThrow().hls());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("DELETE FROM media_origin WHERE download_id='" + stream.id() + "'");
            sql.execute("ALTER TABLE media_origin DROP COLUMN delivery"); sql.execute("PRAGMA user_version=9");
        }
        try (var store = new SqliteDownloadStore(database)) {
            assertEquals(origin(), store.mediaOrigin(file.id()).orElseThrow());
            store.create(request(), temp.resolve("p3"), hls);
        }
    }
}
