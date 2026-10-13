package io.ajdm.core;

import io.ajdm.model.*;
import io.ajdm.store.SqliteDownloadStore;
import io.ajdm.media.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemovalFilesTest {
    @TempDir Path temp;
    private Path db() { return temp.resolve("state/ajdm.db"); }
    private TransferRequest seed(Path folder, boolean completed) throws Exception {
        Files.createDirectories(folder); var request = TransferRequest.of(URI.create("https://cdn.test/file"), folder.resolve("result.bin"));
        Path partial = Files.createTempFile(folder, ".ajdm-", ".ajdmpart"); Files.writeString(partial, "partial");
        try (var store = new SqliteDownloadStore(db())) {
            store.create(request, partial); store.state(request.id(), DownloadState.PAUSED, null);
            if (completed) {
                store.state(request.id(), DownloadState.PROBING, null); store.state(request.id(), DownloadState.DOWNLOADING, null);
                store.state(request.id(), DownloadState.VERIFYING, null); store.state(request.id(), DownloadState.COMPLETED, null);
                Files.writeString(request.target(), "output");
            }
        }
        return request;
    }
    @Test void completedDeletionRemovesOnlyItsFilesAndDefaultRemovalKeepsFiles() throws Exception {
        var request = seed(temp.resolve("downloads"), true); Path unrelated = request.target().resolveSibling("keep.txt"); Files.writeString(unrelated, "keep");
        try (var core = new CoreService(db())) { core.remove(request.id(), true); assertTrue(core.list().isEmpty()); }
        assertFalse(Files.exists(request.target())); try (var files = Files.list(unrelated.getParent())) { assertEquals(List.of(unrelated), files.toList()); }
        request = seed(temp.resolve("downloads"), true);
        try (var core = new CoreService(db())) { core.remove(request.id(), false); assertTrue(core.list().isEmpty()); }
        assertEquals("output", Files.readString(request.target()));
    }
    @Test void pausedDeletionKeepsAnExistingDestinationThatWasNeverDownloaded() throws Exception {
        var request = seed(temp.resolve("downloads"), false); Files.writeString(request.target(), "somebody else's file");
        try (var core = new CoreService(db())) { core.remove(request.id(), true); }
        assertEquals("somebody else's file", Files.readString(request.target()));
        try (var files = Files.list(request.target().getParent())) { assertEquals(List.of(request.target()), files.toList()); }
    }
    @Test void sharedCompletedDestinationCannotBeDeletedThroughAnOlderJob() throws Exception {
        var first = seed(temp.resolve("downloads"), true); var second = seed(temp.resolve("downloads"), true);
        try (var core = new CoreService(db())) {
            assertThrows(IllegalStateException.class, () -> core.remove(first.id(), true));
            assertEquals("output", Files.readString(second.target())); assertEquals(2, core.list().size());
            core.remove(first.id(), false); core.remove(second.id(), true); assertTrue(core.list().isEmpty());
        }
    }
    @Test void symlinkOutputRejectsBeforeDeletingAnyFilesAndKeepsMetadata() throws Exception {
        var request = seed(temp.resolve("downloads"), true); Path unrelated = temp.resolve("private.bin"); Files.writeString(unrelated, "private");
        Files.delete(request.target()); Files.createSymbolicLink(request.target(), unrelated);
        try (var core = new CoreService(db())) {
            assertEquals(ErrorCode.DISK_WRITE_ERROR, assertThrows(DownloadException.class, () -> core.remove(request.id(), true)).code());
            assertEquals(1, core.list().size());
        }
        assertEquals("private", Files.readString(unrelated));
        try (var files = Files.list(request.target().getParent())) { assertEquals(2, files.count()); }
    }
    @Test void symbolicDirectoryRejectsFileDeletionButStillAllowsKeepingFiles() throws Exception {
        var request = seed(temp.resolve("downloads"), false); Path moved = temp.resolve("moved");
        Files.move(request.target().getParent(), moved); Files.createSymbolicLink(request.target().getParent(), moved);
        try (var core = new CoreService(db())) {
            assertEquals(ErrorCode.DISK_WRITE_ERROR, assertThrows(DownloadException.class, () -> core.remove(request.id(), true)).code());
            core.remove(request.id(), false); assertTrue(core.list().isEmpty());
        }
        try (var files = Files.list(moved)) { assertEquals(1, files.count()); }
    }
    @Test void pairedDeletionRemovesOwnedStreamFilesAndLeavesUnexpectedWorkspaceFiles() throws Exception {
        Path folder = temp.resolve("downloads"); Files.createDirectories(folder); UUID id = UUID.randomUUID();
        var job = new MediaJob(id, UUID.randomUUID(), UUID.randomUUID(), folder.resolve("video.mp4"), folder.resolve(".ajdm-media-" + id),
                "mp4", "m4a", "mp4", ConflictPolicy.RENAME, DownloadState.PAUSED, 12, false, null, null, null, null, -1);
        Files.createDirectory(job.workspace()); Path keep = job.workspace().resolve("keep.txt"); Files.writeString(keep, "keep");
        Files.writeString(job.target(), "pre-existing file"); Files.writeString(job.videoPath(), "video"); Files.writeString(job.audioPath(), "audio"); Files.writeString(job.stagePath(), "stage");
        var requests = List.of(TransferRequest.of(URI.create("https://cdn.test/video"), job.videoPath()), TransferRequest.of(URI.create("https://cdn.test/audio"), job.audioPath()));
        requests = List.of(new TransferRequest(job.videoId(), requests.getFirst().uri(), job.videoPath(), Map.of(), TransferOptions.defaults()), new TransferRequest(job.audioId(), requests.getLast().uri(), job.audioPath(), Map.of(), TransferOptions.defaults()));
        var origins = List.of(new MediaOrigin(URI.create("https://page.test/video"), "id", "v", "mp4", "avc1", "none"), new MediaOrigin(URI.create("https://page.test/video"), "id", "a", "m4a", "none", "mp4a"));
        var partials = List.of(Files.createTempFile(job.workspace(), ".ajdm-", ".ajdmpart"), Files.createTempFile(job.workspace(), ".ajdm-", ".ajdmpart"));
        try (var store = new SqliteDownloadStore(db())) { store.createMediaJob(job, requests, partials, origins); }
        try (var core = new CoreService(db())) {
            assertThrows(IllegalStateException.class, () -> core.remove(job.videoId(), true)); assertTrue(Files.exists(job.videoPath()));
            core.remove(job.id(), true); assertTrue(core.list().isEmpty());
        }
        assertEquals("pre-existing file", Files.readString(job.target()));
        try (var files = Files.list(job.workspace())) { assertEquals(List.of(keep), files.toList()); }
    }
    @Test @Timeout(15) void runningPlaylistCannotBeRemovedUntilItsCoordinatorStops() throws Exception {
        var extracting = new CountDownLatch(1);
        var extractor = new MediaExtractor() {
            @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws InterruptedException {
                extracting.countDown(); while (!canceled.getAsBoolean()) Thread.sleep(20); throw new InterruptedException();
            }
            @Override public PlaylistInfo playlist(URI page, BooleanSupplier canceled) {
                return new PlaylistInfo("fixture", "Fixture", page, List.of(new PlaylistInfo.Entry(1, "one", "One", 1.0, page, null)));
            }
        };
        try (var core = new CoreService(db(), extractor)) {
            UUID id = core.addPlaylist("https://page.test/list", "1", new PlaylistSelection.Choice("BEST", null, null), Map.of(), temp.resolve("files"), ConflictPolicy.RENAME, 4, true);
            assertTrue(extracting.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> core.remove(id, true));
            core.pause(id); core.remove(id, true); assertTrue(core.list().isEmpty());
        }
    }
}
