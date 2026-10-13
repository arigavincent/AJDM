package io.ajdm.store;

import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovalStoreTest {
    @TempDir Path temp;
    private TransferRequest request(UUID id, Path target) { return new TransferRequest(id, URI.create("https://cdn.test/file"), target, Map.of("User-Agent", "fixture"), TransferOptions.defaults()); }
    private MediaJob pair(SqliteDownloadStore store) throws Exception {
        var id = UUID.randomUUID(); var job = new MediaJob(id, UUID.randomUUID(), UUID.randomUUID(), temp.resolve("out.mp4"), temp.resolve(".ajdm-media-" + id),
                "mp4", "m4a", "mp4", ConflictPolicy.RENAME, DownloadState.PAUSED, 10, false, null, null, null, null, -1);
        var origin = new MediaOrigin(URI.create("https://page.test/video"), "media", "format", "mp4", "avc1", "none");
        store.createMediaJob(job, List.of(request(job.videoId(), job.videoPath()), request(job.audioId(), job.audioPath())), List.of(temp.resolve("p1"), temp.resolve("p2")), List.of(origin, origin));
        return job;
    }
    private PlaylistJob playlist(SqliteDownloadStore store, UUID child) throws Exception {
        var job = new PlaylistJob(UUID.randomUUID(), "Fixture", URI.create("https://page.test/list"), temp, ConflictPolicy.RENAME, 4, true, DownloadState.PAUSED, null, 0);
        var item = new PlaylistItem(job.id(), 1, "item", "Fixture item", 30.0, URI.create("https://page.test/video"), "BEST", null, null, PlaylistItem.State.PAUSED, child, null);
        store.createPlaylist(job, List.of(item)); return job;
    }
    @Test void pairedRemovalCascadesPrivateMetadataAndSurvivesReopen() throws Exception {
        Path db = temp.resolve("state.db"); UUID parent;
        try (var store = new SqliteDownloadStore(db)) {
            var job = pair(store); parent = job.id();
            assertThrows(DownloadException.class, () -> store.removeStopped(job.videoId()));
            assertEquals(Set.of(job.videoId(), job.audioId()), store.removeStopped(parent));
            assertTrue(store.list().isEmpty()); assertTrue(store.mediaJobs().isEmpty()); assertTrue(store.mediaOrigin(job.videoId()).isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM download_header")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
        try (var store = new SqliteDownloadStore(db)) { assertTrue(store.list().isEmpty()); assertTrue(store.mediaJobs().isEmpty()); }
    }
    @Test void failureOnSecondChildRollsBackParentChildrenAndPlaylistReference() throws Exception {
        Path db = temp.resolve("state.db");
        try (var store = new SqliteDownloadStore(db)) {
            var job = pair(store); var playlist = playlist(store, job.id());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON download WHEN OLD.id='" + job.audioId() + "' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            }
            assertThrows(DownloadException.class, () -> store.removeStopped(job.id()));
            assertEquals(List.of(job), store.mediaJobs()); assertTrue(store.exists(job.videoId())); assertTrue(store.exists(job.audioId()));
            assertEquals(job.id(), store.playlistItems(playlist.id()).getFirst().childId());
        }
    }
    @Test void removingAChildMarksItsPlaylistItemCanceledWithoutRecreatingIt() throws Exception {
        try (var store = new SqliteDownloadStore(temp.resolve("state.db"))) {
            var job = pair(store); var playlist = playlist(store, job.id()); store.removeStopped(job.id());
            var item = store.playlistItems(playlist.id()).getFirst();
            assertNull(item.childId()); assertEquals(PlaylistItem.State.CANCELED, item.state()); assertTrue(item.terminal());
            assertEquals(List.of(playlist), store.playlists());
        }
    }
    @Test void playlistRemovalIncludesPairedChildrenButPreservesUnrelatedEntries() throws Exception {
        try (var store = new SqliteDownloadStore(temp.resolve("state.db"))) {
            var job = pair(store); var playlist = playlist(store, job.id()); var unrelated = request(UUID.randomUUID(), temp.resolve("unrelated"));
            store.create(unrelated, temp.resolve("unrelated-part"));
            assertEquals(Set.of(job.videoId(), job.audioId()), store.removeStopped(playlist.id()));
            assertTrue(store.mediaJobs().isEmpty()); assertTrue(store.playlists().isEmpty()); assertTrue(store.playlistItems(playlist.id()).isEmpty());
            assertEquals(unrelated.id(), store.list().getFirst().request().id());
        }
    }
    @Test void runningChildPreventsTheWholePlaylistRemoval() throws Exception {
        try (var store = new SqliteDownloadStore(temp.resolve("state.db"))) {
            var job = pair(store); var playlist = playlist(store, job.id()); store.state(job.audioId(), DownloadState.PROBING, null);
            assertThrows(DownloadException.class, () -> store.removeStopped(playlist.id()));
            assertEquals(List.of(job), store.mediaJobs()); assertEquals(1, store.playlistItems(playlist.id()).size()); assertEquals(2, store.list().size());
        }
    }
    @Test void playlistWithUnadmittedChildIntentCanStillBeRemoved() throws Exception {
        try (var store = new SqliteDownloadStore(temp.resolve("state.db"))) {
            var playlist = playlist(store, UUID.randomUUID()); store.removeStopped(playlist.id());
            assertTrue(store.playlists().isEmpty()); assertTrue(store.list().isEmpty());
        }
    }
}
