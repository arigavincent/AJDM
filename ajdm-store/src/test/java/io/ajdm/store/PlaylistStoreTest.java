package io.ajdm.store;

import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistStoreTest {
    @TempDir Path temp;
    private PlaylistJob job() { return new PlaylistJob(UUID.randomUUID(), "List", URI.create("https://page.test/list?secret=token"), temp.resolve("files"), ConflictPolicy.RENAME, 4, true, DownloadState.QUEUED, null, 0); }
    private PlaylistItem item(PlaylistJob j, int index) { return new PlaylistItem(j.id(), index, "id", "Title", 1.5, URI.create("https://page.test/video"), "BEST", null, null, PlaylistItem.State.QUEUED, null, null); }
    @Test void choicesAdmissionIntentAndSpacingSurviveReopenWithoutAdmittingChild() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var job = job(); var item = item(job, 1).child(UUID.randomUUID());
        try (var store = new SqliteDownloadStore(db)) { store.createPlaylist(job, List.of(item)); store.savePlaylist(job.nextExtraction(12345)); }
        try (var store = new SqliteDownloadStore(db)) {
            assertEquals(12345, store.playlists().getFirst().nextExtractionAt()); assertEquals(List.of(item), store.playlistItems(job.id())); assertTrue(store.list().isEmpty());
            assertFalse(item.toString().contains("https")); assertFalse(job.toString().contains("secret"));
        }
    }
    @Test void failedItemBatchRollsBackParentAndAllItems() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var job = job();
        try (var store = new SqliteDownloadStore(db)) {
            assertThrows(DownloadException.class, () -> store.createPlaylist(job, List.of(item(job, 1), item(job, 1))));
            assertTrue(store.playlists().isEmpty()); assertTrue(store.playlistItems(job.id()).isEmpty());
        }
    }
    @Test void versionSevenMigrationPreservesDownloadsWithoutInventingPlaylist() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var id = UUID.randomUUID();
        try (var store = new SqliteDownloadStore(db)) { store.create(new TransferRequest(id, URI.create("https://cdn.test/file"), temp.resolve("out"), Map.of(), TransferOptions.defaults()), temp.resolve("part")); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) { sql.execute("DROP TABLE playlist_item"); sql.execute("DROP TABLE playlist_job"); sql.execute("ALTER TABLE download DROP COLUMN initial_connections"); sql.execute("PRAGMA user_version=7"); }
        try (var store = new SqliteDownloadStore(db)) { assertTrue(store.playlists().isEmpty()); assertEquals(id, store.get(id).request().id()); }
    }
}
