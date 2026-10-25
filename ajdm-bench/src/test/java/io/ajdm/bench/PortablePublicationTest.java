package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class PortablePublicationTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("state/ajdm.db"); }
    static PublicationFiles noLinks() {
        return new PublicationFiles() {
            @Override public void link(Path target, Path part) { throw new UnsupportedOperationException("No hard links"); }
            @Override public void atomicReplace(Path part, Path target) throws IOException {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
    private AjdmEngine engine(CheckpointObserver observer, PublicationFiles files) throws IOException {
        return new AjdmEngine(database(), EngineLimits.defaults(), observer, DiskSpaceProbe.fileStore(), files);
    }

    @ParameterizedTest @ValueSource(strings = {"AFTER_PUBLICATION_INTENT", "AFTER_FINAL_LINK"})
    void interruptedOverwriteRecoversWithProofAndNoNetwork(String boundary) throws Exception {
        Path target = directory.resolve("result.bin"); Files.writeString(target, "original");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL)) {
            var request = TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.OVERWRITE);
            try (var engine = engine((phase, id, bytes) -> {
                if (phase.name().equals(boundary)) throw new IOException("Injected publication interruption");
            }, noLinks())) {
                assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
                var saved = engine.downloads().getFirst();
                assertTrue(saved.publicationIntent()); assertEquals(server.sha256(), saved.actualSha256());
                assertEquals(boundary.equals("AFTER_PUBLICATION_INTENT"), Files.exists(saved.partialPath()));
            }
            int requests = server.requests();
            try (var engine = engine(CheckpointObserver.none(), noLinks())) {
                if (boundary.equals("AFTER_FINAL_LINK")) {
                    assertThrows(IllegalStateException.class, () -> engine.resume(request.id(), ConflictPolicy.RENAME));
                    assertEquals(ConflictPolicy.OVERWRITE, engine.downloads().getFirst().request().conflictPolicy());
                }
                var handle = engine.resume(request.id());
                var result = handle.completion().toCompletableFuture().get();
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(requests, server.requests()); assertEquals(0, handle.progress().receivedBytes());
            }
        }
    }

    @Test void unsupportedAtomicMoveKeepsOriginalAndVerifiedPartial() throws Exception {
        Path target = directory.resolve("result.bin"); Files.writeString(target, "original");
        PublicationFiles files = new PublicationFiles() {
            @Override public void link(Path link, Path part) throws IOException { throw new FileSystemException(link.toString(), null, "Links unavailable"); }
            @Override public void atomicReplace(Path part, Path destination) throws IOException {
                throw new AtomicMoveNotSupportedException(part.toString(), destination.toString(), "Unavailable");
            }
        };
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL);
             var engine = engine(CheckpointObserver.none(), files)) {
            var failure = assertThrows(ExecutionException.class, () -> engine.start(TransferRequest.of(server.uri(), target)
                    .withConflictPolicy(ConflictPolicy.OVERWRITE)).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.DISK_WRITE_ERROR, ((DownloadException) failure.getCause()).code());
            assertEquals("original", Files.readString(target));
            var saved = engine.downloads().getFirst(); assertTrue(saved.readyToPublish());
            assertArrayEquals(server.data(), Files.readAllBytes(saved.partialPath()));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replacedFinalIsNeverAcceptedByLengthAloneOrModifiedDuringRecovery(boolean symlink) throws Exception {
        Path target = directory.resolve("result.bin");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL)) {
            var request = TransferRequest.of(server.uri(), target).withConflictPolicy(ConflictPolicy.OVERWRITE);
            try (var engine = engine((phase, id, bytes) -> {
                if (phase == CheckpointObserver.Phase.AFTER_FINAL_LINK) throw new IOException("Stop after move");
            }, noLinks())) {
                assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
            }
            byte[] foreign = new byte[server.data().length]; java.util.Arrays.fill(foreign, (byte) 42);
            if (symlink) {
                Path outside = directory.resolve("outside.bin"); Files.write(outside, foreign);
                Files.delete(target); SymbolicLinks.create(target, outside);
            } else Files.write(target, foreign);
            int requests = server.requests();
            try (var engine = engine(CheckpointObserver.none(), noLinks())) {
                var failure = assertThrows(ExecutionException.class, () -> engine.resume(request.id()).completion().toCompletableFuture().get());
                assertEquals(symlink ? ErrorCode.PART_FILE_MISSING : ErrorCode.CHECKSUM_MISMATCH, ((DownloadException) failure.getCause()).code());
                assertArrayEquals(foreign, Files.readAllBytes(target)); assertEquals(requests, server.requests());
                assertTrue(engine.downloads().getFirst().readyToPublish());
            }
        }
    }

    @Test void identicalFinalWithoutMoveIntentCannotSubstituteForMissingPartial() throws Exception {
        Path target = directory.resolve("result.bin");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL)) {
            var request = TransferRequest.of(server.uri(), target);
            try (var engine = new AjdmEngine(database(), (phase, id, bytes) -> {
                if (phase == CheckpointObserver.Phase.AFTER_FINAL_LINK) {
                    try (var paths = Files.list(directory)) {
                        Files.delete(paths.filter(p -> p.toString().endsWith(".ajdmpart")).findFirst().orElseThrow());
                    }
                    throw new IOException("Lost partial without move intent");
                }
            })) {
                assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
                assertTrue(engine.downloads().getFirst().readyToPublish()); assertFalse(engine.downloads().getFirst().publicationIntent());
            }
            int requests = server.requests();
            try (var engine = new AjdmEngine(database())) {
                var failure = assertThrows(ExecutionException.class, () -> engine.resume(request.id()).completion().toCompletableFuture().get());
                assertEquals(ErrorCode.PART_FILE_MISSING, ((DownloadException) failure.getCause()).code());
                assertEquals(0, engine.downloads().getFirst().durableBytes());
                assertArrayEquals(server.data(), Files.readAllBytes(target)); assertEquals(requests, server.requests());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"RENAME", "ASK"})
    void noClobberPoliciesFailClosedWithoutLinks(String policy) throws Exception {
        Path target = directory.resolve("result.bin");
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL);
             var engine = engine((phase, id, bytes) -> {
                 if (phase == CheckpointObserver.Phase.AFTER_READY_COMMIT) Files.writeString(target, "late foreign file");
             }, noLinks())) {
            assertThrows(ExecutionException.class, () -> engine.start(TransferRequest.of(server.uri(), target)
                    .withConflictPolicy(ConflictPolicy.valueOf(policy))).completion().toCompletableFuture().get());
            assertEquals("late foreign file", Files.readString(target));
            assertTrue(engine.downloads().getFirst().readyToPublish());
            assertArrayEquals(server.data(), Files.readAllBytes(engine.downloads().getFirst().partialPath()));
        }
    }
}
