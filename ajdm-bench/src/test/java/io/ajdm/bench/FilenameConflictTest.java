package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FilenameConflictTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("state/ajdm.db"); }
    @Test void renamePreservesExistingFilesAndPersistsActualDestination() throws Exception {
        Path target = directory.resolve("report.zip");
        Files.writeString(target, "original"); Files.writeString(directory.resolve("report (1).zip"), "second");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database())) {
            var request = TransferRequest.of(server.uri(), target);
            var result = engine.start(request).completion().toCompletableFuture().get();
            assertEquals(directory.resolve("report (2).zip"), result.target());
            assertEquals(result.target(), engine.downloads().getFirst().request().target());
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertEquals("original", Files.readString(target));
            assertEquals("second", Files.readString(directory.resolve("report (1).zip")));
        }
    }
    @Test void lateCollisionRenamesVerifiedFileWithoutRedownloading() throws Exception {
        Path target = directory.resolve("report.zip");
        CheckpointObserver observer = (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_READY_COMMIT) Files.writeString(target, "late arrival");
        };
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database(), observer)) {
            var result = engine.start(TransferRequest.of(server.uri(), target)).completion().toCompletableFuture().get();
            assertEquals(directory.resolve("report (1).zip"), result.target());
            assertEquals("late arrival", Files.readString(target));
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
        }
    }
    @Test void askCanResumeVerifiedFileWithExplicitRenameChoice() throws Exception {
        Path target = directory.resolve("report.zip");
        CheckpointObserver observer = (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_READY_COMMIT) Files.writeString(target, "late arrival");
        };
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL)) {
            var request = TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.ASK);
            try (var engine = new AjdmEngine(database(), observer)) {
                var failure = assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
                assertEquals(ErrorCode.TARGET_EXISTS, ((DownloadException) failure.getCause()).code());
                assertTrue(engine.downloads().getFirst().readyToPublish());
            }
            int requests = server.requests();
            try (var engine = new AjdmEngine(database())) {
                var result = engine.resume(request.id(), ConflictPolicy.RENAME).completion().toCompletableFuture().get();
                assertEquals(directory.resolve("report (1).zip"), result.target());
                assertEquals(requests, server.requests());
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            }
        }
    }
    @Test void explicitOverwritePublishesOnlyAfterVerification() throws Exception {
        Path target = directory.resolve("report.zip"); Files.writeString(target, "original");
        CheckpointObserver observer = (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_READY_COMMIT) assertEquals("original", Files.readString(target));
        };
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database(), observer)) {
            var result = engine.start(TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.OVERWRITE))
                    .completion().toCompletableFuture().get();
            assertEquals(target, result.target()); assertArrayEquals(server.data(), Files.readAllBytes(target));
        }
    }
    @Test void overwriteNeverTouchesExistingFileIfVerificationFails() throws Exception {
        Path target = directory.resolve("report.zip"); Files.writeString(target, "original");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.CORRUPT_BODY); var engine = new AjdmEngine(database())) {
            var base = TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.OVERWRITE);
            var options = new TransferOptions(base.options().connectTimeout(), base.options().requestTimeout(), base.options().stallTimeout(),
                    4096, 2, 65536, 2, java.time.Duration.ZERO, java.time.Duration.ZERO, server.sha256());
            var request = new TransferRequest(base.id(), base.uri(), target, base.headers(), options, ConflictPolicy.OVERWRITE);
            var failure = assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.CHECKSUM_MISMATCH, ((DownloadException) failure.getCause()).code());
            assertEquals("original", Files.readString(target));
        }
    }
    @Test void simultaneousRenamesNeverClobberEachOther() throws Exception {
        Path target = directory.resolve("report.zip");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database())) {
            var first = engine.start(TransferRequest.of(server.uri(), target));
            var second = engine.start(TransferRequest.of(server.uri(), target));
            var a = first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var b = second.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertNotEquals(a.target(), b.target());
            assertArrayEquals(server.data(), Files.readAllBytes(a.target()));
            assertArrayEquals(server.data(), Files.readAllBytes(b.target()));
        }
    }
    @Test void overwriteRefusesDirectoryAndSymbolicLinkWithoutTouchingTheirContents() throws Exception {
        Path target = directory.resolve("report.zip"), outside = directory.resolve("other.bin");
        Files.writeString(outside, "keep outside"); Files.createDirectory(target);
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database())) {
            var request = TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.OVERWRITE);
            var failure = assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.TARGET_EXISTS, ((DownloadException) failure.getCause()).code());
            assertEquals(0, server.requests()); assertTrue(Files.isDirectory(target));
            Files.delete(target); SymbolicLinks.create(target, outside);
            var second = assertThrows(ExecutionException.class, () -> engine.start(TransferRequest.of(server.uri(), target)
                    .withConflictPolicy(ConflictPolicy.OVERWRITE)).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.TARGET_EXISTS, ((DownloadException) second.getCause()).code());
            assertEquals("keep outside", Files.readString(outside)); assertTrue(Files.isSymbolicLink(target));
        }
    }
    @Test void unrelatedStagingFileIsNeverReusedOrDeleted() throws Exception {
        Path target = directory.resolve("report.zip"); Files.writeString(target, "original");
        var stage = new java.util.concurrent.atomic.AtomicReference<Path>();
        CheckpointObserver observer = (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_READY_COMMIT) {
                try (var paths = Files.list(directory)) {
                    Path part = paths.filter(path -> path.getFileName().toString().endsWith(".ajdmpart")).findFirst().orElseThrow();
                    stage.set(part.resolveSibling(part.getFileName() + ".publish")); Files.writeString(stage.get(), "unrelated");
                }
            }
        };
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database(), observer)) {
            var failure = assertThrows(ExecutionException.class, () -> engine.start(TransferRequest.of(server.uri(), target)
                    .withConflictPolicy(ConflictPolicy.OVERWRITE)).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.TARGET_EXISTS, ((DownloadException) failure.getCause()).code());
            assertEquals("original", Files.readString(target)); assertEquals("unrelated", Files.readString(stage.get()));
        }
    }
    @Test void freshUnsafeExplicitTargetIsRejectedBeforeCreatingStateOrPartial() throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL); var engine = new AjdmEngine(database())) {
            var request = new TransferRequest(java.util.UUID.randomUUID(), server.uri(), directory.resolve("CON.txt"),
                    java.util.Map.of(), TransferOptions.defaults());
            assertThrows(IllegalArgumentException.class, () -> engine.start(request));
            assertTrue(engine.downloads().isEmpty()); assertEquals(0, server.requests());
            try (var paths = Files.list(directory)) { assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".ajdmpart"))); }
        }
    }
    @Test void failedOverwriteStageCanRecoverWithRenameAndCleanOnlyOwnedLinks() throws Exception {
        Path target = directory.resolve("report.zip"); Files.writeString(target, "original");
        CheckpointObserver observer = (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_OVERWRITE_STAGE) throw new java.io.IOException("Injected stage boundary failure");
        };
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL)) {
            var request = TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.OVERWRITE);
            Path stage;
            try (var engine = new AjdmEngine(database(), observer)) {
                var failure = assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
                assertEquals(ErrorCode.DISK_WRITE_ERROR, ((DownloadException) failure.getCause()).code());
                Path part = engine.downloads().getFirst().partialPath(); stage = part.resolveSibling(part.getFileName() + ".publish");
                assertTrue(Files.isSameFile(part, stage)); assertEquals("original", Files.readString(target));
            }
            int requests = server.requests();
            try (var engine = new AjdmEngine(database())) {
                var result = engine.resume(request.id(), ConflictPolicy.RENAME).completion().toCompletableFuture().get();
                assertEquals(directory.resolve("report (1).zip"), result.target());
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals("original", Files.readString(target)); assertFalse(Files.exists(stage)); assertEquals(requests, server.requests());
            }
        }
    }
}
