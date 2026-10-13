package io.ajdm.store;

import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaJobStoreTest {
    @TempDir Path temp;
    private MediaJob job() {
        UUID id = UUID.randomUUID(); return new MediaJob(id, UUID.randomUUID(), UUID.randomUUID(), temp.resolve("result.mp4"), temp.resolve(".ajdm-media-" + id),
                "mp4", "m4a", "mp4", ConflictPolicy.RENAME, DownloadState.QUEUED, 1000, false, null, null, null, null, -1);
    }
    private List<TransferRequest> requests(MediaJob j) {
        return List.of(new TransferRequest(j.videoId(), URI.create("https://cdn.test/video"), j.videoPath(), Map.of("Cookie", "secret"), TransferOptions.defaults()),
                new TransferRequest(j.audioId(), URI.create("https://cdn.test/audio"), j.audioPath(), Map.of(), TransferOptions.defaults()));
    }
    private List<MediaOrigin> origins() {
        return List.of(new MediaOrigin(URI.create("https://page.test/watch"), "id", "v", "mp4", "avc1", "none"),
                new MediaOrigin(URI.create("https://page.test/watch"), "id", "a", "m4a", "none", "mp4a"));
    }
    @Test void parentChildrenAndReadyHashesSurviveReopen() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var job = job();
        try (var store = new SqliteDownloadStore(db)) {
            store.createMediaJob(job, requests(job), List.of(temp.resolve("p1"), temp.resolve("p2")), origins());
            job = job.hashes("a".repeat(64), "b".repeat(64), "c".repeat(64), 100).status(DownloadState.VERIFYING, null); store.saveMediaJob(job);
        }
        try (var store = new SqliteDownloadStore(db)) {
            assertEquals(List.of(job), store.mediaJobs()); assertEquals(2, store.list().size());
            assertTrue(store.mediaOrigin(job.audioId()).isPresent()); assertFalse(store.get(job.videoId()).request().headers().containsKey("Cookie"));
        }
    }
    @Test void parentInsertFailureRollsBackBothChildrenOriginsAndHeaders() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var job = job();
        try (var store = new SqliteDownloadStore(db)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_job BEFORE INSERT ON media_job BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(DownloadException.class, () -> store.createMediaJob(job, requests(job), List.of(temp.resolve("p1"), temp.resolve("p2")), origins()));
            assertTrue(store.list().isEmpty()); assertTrue(store.mediaJobs().isEmpty()); assertTrue(store.mediaOrigin(job.videoId()).isEmpty());
        }
    }
    @Test void secondChildFailureRollsBackFirstAndParent() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var job = job();
        try (var store = new SqliteDownloadStore(db)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_audio BEFORE INSERT ON download WHEN NEW.id='" + job.audioId() + "' BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThrows(DownloadException.class, () -> store.createMediaJob(job, requests(job), List.of(temp.resolve("p1"), temp.resolve("p2")), origins()));
            assertFalse(store.exists(job.videoId())); assertTrue(store.mediaJobs().isEmpty());
        }
    }
    @Test void versionSixMigrationPreservesOriginWithoutInventingParent() throws Exception {
        Path db = temp.resolve("state/ajdm.db"); var job = job();
        try (var store = new SqliteDownloadStore(db)) { store.create(requests(job).getFirst(), temp.resolve("p1"), origins().getFirst()); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = connection.createStatement()) { sql.execute("DROP TABLE media_job"); sql.execute("ALTER TABLE download DROP COLUMN initial_connections"); sql.execute("PRAGMA user_version=6"); }
        try (var store = new SqliteDownloadStore(db)) { assertTrue(store.mediaJobs().isEmpty()); assertEquals(origins().getFirst(), store.mediaOrigin(job.videoId()).orElseThrow()); }
    }
}
