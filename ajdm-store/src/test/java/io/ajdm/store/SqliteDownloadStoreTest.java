package io.ajdm.store;

import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SqliteDownloadStoreTest {
    @TempDir Path directory;
    @Test void pauseReasonAndCoverageCommitTogetherAndInvalidUpdateRollsBack() throws Exception {
        Path database = directory.resolve("pause-reason.db"); var request = request();
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(0, 50)), DownloadState.PAUSED, ErrorCode.SOURCE_CHANGED);
            assertThrows(DownloadException.class, () -> store.checkpoint(request.id(), List.of(new ByteRange(0, 101)), DownloadState.PAUSED, ErrorCode.DISK_WRITE_ERROR));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER injected_reason_failure BEFORE INSERT ON completed_range
                        WHEN NEW.start = 10 BEGIN SELECT RAISE(ABORT, 'injected reason insert failure'); END
                        """);
            }
            assertThrows(DownloadException.class, () -> store.checkpoint(request.id(), List.of(new ByteRange(10, 20)), DownloadState.PAUSED, ErrorCode.DISK_WRITE_ERROR));
            assertEquals(50, store.get(request.id()).durableBytes()); assertEquals(ErrorCode.SOURCE_CHANGED, store.get(request.id()).error());
        }
        try (var store = new SqliteDownloadStore(database)) {
            var saved = store.get(request.id());
            assertEquals(DownloadState.PAUSED, saved.state()); assertEquals(ErrorCode.SOURCE_CHANGED, saved.error()); assertEquals(50, saved.durableBytes());
        }
    }
    private void verified(SqliteDownloadStore store, TransferRequest request) throws Exception {
        store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
        store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
        store.state(request.id(), DownloadState.DOWNLOADING, null);
        store.checkpoint(request.id(), List.of(new ByteRange(0, 100)), DownloadState.VERIFYING);
        store.ready(request.id(), null);
    }

    @Test void versionFourMigrationKeepsVerifiedOverwriteWithoutInventingMoveIntent() throws Exception {
        Path database = directory.resolve("v4.db"); var request = request().withConflictPolicy(ConflictPolicy.OVERWRITE);
        try (var store = new SqliteDownloadStore(database)) { verified(store, request); store.ready(request.id(), "0".repeat(64)); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE download DROP COLUMN publication_intent"); statement.execute("ALTER TABLE download DROP COLUMN initial_connections"); statement.execute("PRAGMA user_version=4");
        }
        try (var store = new SqliteDownloadStore(database)) {
            var saved = store.get(request.id());
            assertTrue(saved.readyToPublish()); assertFalse(saved.publicationIntent());
            assertEquals(100, saved.durableBytes()); assertEquals("0".repeat(64), saved.actualSha256());
            assertEquals(ConflictPolicy.OVERWRITE, saved.request().conflictPolicy());
        }
    }

    @Test void publicationIntentRequiresVerifiedOverwriteAndValidProofAndSurvivesReopen() throws Exception {
        Path database = directory.resolve("journal.db"); var request = request().withConflictPolicy(ConflictPolicy.OVERWRITE);
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part"));
            assertThrows(DownloadException.class, () -> store.publicationIntent(request.id(), "0".repeat(64)));
            store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(0, 100)), DownloadState.VERIFYING);
            store.ready(request.id(), null);
            assertThrows(DownloadException.class, () -> store.publicationIntent(request.id(), "invalid"));
            assertFalse(store.get(request.id()).publicationIntent());
            store.publicationIntent(request.id(), "0".repeat(64));
            assertThrows(DownloadException.class, () -> store.publicationIntent(request.id(), "1".repeat(64)));
        }
        try (var store = new SqliteDownloadStore(database)) {
            var saved = store.get(request.id());
            assertTrue(saved.publicationIntent()); assertTrue(saved.readyToPublish()); assertEquals("0".repeat(64), saved.actualSha256());
            store.checkpoint(request.id(), List.of(new ByteRange(0, 50)), DownloadState.PAUSED);
            assertFalse(store.get(request.id()).publicationIntent()); assertNull(store.get(request.id()).actualSha256());
        }
    }

    @Test void changingAnUnmovedDestinationClearsIntentWithoutDiscardingVerifiedBytes() throws Exception {
        Path database = directory.resolve("journal-choice.db"); var request = request().withConflictPolicy(ConflictPolicy.OVERWRITE);
        try (var store = new SqliteDownloadStore(database)) {
            verified(store, request); store.publicationIntent(request.id(), "0".repeat(64));
            store.destination(request.id(), directory.resolve("target (1)"), ConflictPolicy.RENAME);
            var saved = store.get(request.id());
            assertFalse(saved.publicationIntent()); assertTrue(saved.readyToPublish());
            assertEquals("0".repeat(64), saved.actualSha256()); assertEquals(100, saved.durableBytes());
            assertThrows(DownloadException.class, () -> store.publicationIntent(request.id(), "0".repeat(64)));
        }
    }
    private TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), URI.create("https://example.com/file"), directory.resolve("target"),
                Map.of("Authorization", "Bearer secret-token", "Cookie", "sid=secret-cookie", "X-Api-Key", "secret-key",
                        "User-Agent", "AJDM test's agent"), TransferOptions.defaults());
    }
    @Test void coverageAndOptionsSurviveReopenAndInFlightStateBecomesPaused() throws Exception {
        Path database = directory.resolve("state.db"), partial = directory.resolve("part.ajdmpart");
        var request = request();
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, partial); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(40, 60), new ByteRange(0, 20)), DownloadState.DOWNLOADING);
            assertEquals(40, store.get(request.id()).durableBytes());
        }
        try (var store = new SqliteDownloadStore(database)) {
            var row = store.get(request.id());
            assertEquals(DownloadState.PAUSED, row.state()); assertEquals(request.options(), row.request().options());
            assertEquals(20, row.prefixLength()); assertTrue(row.resumeSupported());
            assertEquals(List.of(new ByteRange(0, 20), new ByteRange(40, 60)), row.completed());
            assertEquals(Map.of("User-Agent", "AJDM test's agent"), row.request().headers());
            assertEquals(1, store.list().size());
        }
        String databaseBytes = new String(Files.readAllBytes(database), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(databaseBytes.contains("secret-token")); assertFalse(databaseBytes.contains("secret-cookie"));
        assertFalse(databaseBytes.contains("secret-key"));
    }
    @Test void versionOneMigrationPreservesDownloadsAndDefaultsToOneWorker() throws Exception {
        Path database = directory.resolve("migration.db"); var request = request();
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(40, 60)), DownloadState.DOWNLOADING);
        }
        // Recreate the exact v1 shape without the two v2 option columns.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE download DROP COLUMN adaptive");
            statement.execute("ALTER TABLE download DROP COLUMN connections");
            statement.execute("ALTER TABLE download DROP COLUMN min_segment_bytes");
            statement.execute("ALTER TABLE download DROP COLUMN conflict_policy");
            statement.execute("ALTER TABLE download DROP COLUMN suggested_filename");
            statement.execute("ALTER TABLE download DROP COLUMN content_type");
            statement.execute("ALTER TABLE download DROP COLUMN publication_intent");
            statement.execute("ALTER TABLE download DROP COLUMN initial_connections"); statement.execute("PRAGMA user_version=1");
        }
        try (var store = new SqliteDownloadStore(database)) {
            var row = store.get(request.id());
            assertEquals(request.options().withParallelism(1, 1024 * 1024).withInitialConnections(2), row.request().options());
            assertEquals(List.of(new ByteRange(40, 60)), row.completed()); assertEquals(DownloadState.PAUSED, row.state());
        }
    }
    @Test void versionTwoMigrationKeepsFixedModeAndAdaptiveOptionsSurviveReopen() throws Exception {
        var base = request();
        Path database = directory.resolve("v2.db");
        try (var store = new SqliteDownloadStore(database)) { store.create(base, directory.resolve("old.part")); }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE download DROP COLUMN adaptive"); statement.execute("ALTER TABLE download DROP COLUMN initial_connections"); statement.execute("PRAGMA user_version=2");
            statement.execute("ALTER TABLE download DROP COLUMN conflict_policy");
            statement.execute("ALTER TABLE download DROP COLUMN suggested_filename");
            statement.execute("ALTER TABLE download DROP COLUMN content_type");
            statement.execute("ALTER TABLE download DROP COLUMN publication_intent");
        }
        var adaptive = new TransferRequest(UUID.randomUUID(), base.uri(), directory.resolve("adaptive.bin"), Map.of(),
                base.options().withAdaptiveParallelism(8, 65536));
        try (var store = new SqliteDownloadStore(database)) {
            assertFalse(store.get(base.id()).request().options().adaptive());
            store.create(adaptive, directory.resolve("adaptive.part"));
        }
        try (var store = new SqliteDownloadStore(database)) { assertEquals(adaptive.options(), store.get(adaptive.id()).request().options()); }
    }
    @Test void invalidCheckpointRollsBackWithoutLosingPreviousCoverage() throws Exception {
        var request = request();
        Path database = directory.resolve("state.db");
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(0, 20)), DownloadState.DOWNLOADING);
            assertThrows(DownloadException.class, () -> store.checkpoint(request.id(), List.of(new ByteRange(0, 101)), DownloadState.PAUSED));
            assertEquals(20, store.get(request.id()).durableBytes()); assertEquals(DownloadState.DOWNLOADING, store.get(request.id()).state());
            // Fail after the transaction has changed state and deleted its old ranges.
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER injected_checkpoint_failure BEFORE INSERT ON completed_range
                        WHEN NEW.start = 30 BEGIN SELECT RAISE(ABORT, 'injected insert failure'); END
                        """);
            }
            var failure = assertThrows(DownloadException.class,
                    () -> store.checkpoint(request.id(), List.of(new ByteRange(30, 50)), DownloadState.PAUSED));
            assertEquals(ErrorCode.STATE_STORE_ERROR, failure.code());
            assertEquals(List.of(new ByteRange(0, 20)), store.get(request.id()).completed());
            assertEquals(20, store.get(request.id()).durableBytes());
            assertEquals(DownloadState.DOWNLOADING, store.get(request.id()).state());
        }
    }
    @Test void aSecondEngineCannotOwnTheSameDatabase() throws Exception {
        Path database = directory.resolve("state.db");
        try (var first = new SqliteDownloadStore(database)) {
            assertEquals(0, first.list().size());
            var failure = assertThrows(DownloadException.class, () -> new SqliteDownloadStore(database));
            assertEquals(ErrorCode.STATE_IN_USE, failure.code());
        }
        try (var next = new SqliteDownloadStore(database)) { assertEquals(0, next.list().size()); }
    }
    @Test void newlyCreatedStateFilesArePrivateOnPosix() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path database = directory.resolve("private-state/ajdm.db");
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request(), directory.resolve("part"));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(database.getParent()));
            for (String suffix : List.of("", ".lock", "-wal", "-shm")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(database.resolveSibling("ajdm.db" + suffix)));
            }
        }
    }
    @Test void validatedSourceAndSafeHeadersReplaceAtomicallyWithoutLosingCoverage() throws Exception {
        Path database = directory.resolve("refresh.db"); var request = request();
        URI replacement = URI.create("https://cdn.example.test/new?signature=private-url");
        var source = new DownloadSource(replacement, Map.of("Authorization", "Bearer replacement-secret", "X-Api-Key", "replacement-key",
                "Accept", "application/octet-stream"));
        var ranges = List.of(new ByteRange(0, 20), new ByteRange(50, 80));
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"stable\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null); store.checkpoint(request.id(), ranges, DownloadState.DOWNLOADING);
            store.replaceSource(request.id(), source, new ProbeResult(replacement, 100, true, "\"stable\"", null));
            assertEquals(ranges, store.get(request.id()).completed()); assertEquals(50, store.get(request.id()).durableBytes());
            assertThrows(DownloadException.class, () -> store.replaceSource(request.id(), new DownloadSource(URI.create("https://other.test/file"), Map.of()),
                    new ProbeResult(URI.create("https://other.test/file"), 99, true, "\"wrong\"", null)));
            assertEquals(replacement, store.get(request.id()).request().uri());
            assertEquals(Map.of("Accept", "application/octet-stream"), store.get(request.id()).request().headers());
        }
        try (var store = new SqliteDownloadStore(database)) {
            var saved = store.get(request.id()); assertEquals(replacement, saved.request().uri());
            assertEquals(replacement, saved.probe().finalUri()); assertEquals(ranges, saved.completed());
            assertEquals(DownloadState.PAUSED, saved.state());
        }
        String bytes = new String(Files.readAllBytes(database), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains("replacement-secret")); assertFalse(bytes.contains("replacement-key"));
    }
    @Test void sourceUpdateRejectsHttpsDowngradeBeforeMutatingAnyField() throws Exception {
        var request = request();
        try (var store = new SqliteDownloadStore(directory.resolve("downgrade.db"))) {
            store.create(request, directory.resolve("part"));
            var uri = URI.create("http://example.test/file");
            assertThrows(DownloadException.class, () -> store.replaceSource(request.id(), new DownloadSource(uri, Map.of()),
                    new ProbeResult(uri, 100, true, "\"v1\"", null)));
            assertEquals(request.uri(), store.get(request.id()).request().uri()); assertNull(store.get(request.id()).probe());
        }
    }

    @Test void versionThreeMigrationKeepsLegacyAskPolicyAndCoverage() throws Exception {
        var request = request(); Path database = directory.resolve("v3.db");
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 100, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(40, 60)), DownloadState.DOWNLOADING);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE download DROP COLUMN conflict_policy");
            statement.execute("ALTER TABLE download DROP COLUMN suggested_filename");
            statement.execute("ALTER TABLE download DROP COLUMN content_type");
            statement.execute("ALTER TABLE download DROP COLUMN publication_intent");
            statement.execute("ALTER TABLE download DROP COLUMN initial_connections"); statement.execute("PRAGMA user_version=3");
        }
        try (var store = new SqliteDownloadStore(database)) {
            var saved = store.get(request.id());
            assertEquals(ConflictPolicy.ASK, saved.request().conflictPolicy());
            assertEquals(request.target(), saved.request().target());
            assertEquals(request.options().withInitialConnections(2), saved.request().options());
            assertEquals(List.of(new ByteRange(40, 60)), saved.completed());
            assertEquals("file", saved.probe().filename());
        }
    }

    @Test void destinationPolicyAndFilenameMetadataPersistWithoutResettingVerifiedRanges() throws Exception {
        var request = request(); Path database = directory.resolve("metadata.db");
        var probe = new ProbeResult(request.uri(), 100, true, "\"v1\"", null, "café.zip", "application/zip");
        try (var store = new SqliteDownloadStore(database)) {
            store.create(request, directory.resolve("part")); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), probe); store.state(request.id(), DownloadState.DOWNLOADING, null);
            store.checkpoint(request.id(), List.of(new ByteRange(0, 100)), DownloadState.VERIFYING);
            store.ready(request.id(), "0".repeat(64));
            store.destination(request.id(), directory.resolve("target (1)"), ConflictPolicy.RENAME);
            assertThrows(DownloadException.class, () -> store.destination(request.id(), directory.resolve("elsewhere/file"), ConflictPolicy.OVERWRITE));
            assertTrue(store.get(request.id()).readyToPublish());
        }
        try (var store = new SqliteDownloadStore(database)) {
            var saved = store.get(request.id());
            assertEquals(directory.resolve("target (1)"), saved.request().target());
            assertEquals(ConflictPolicy.RENAME, saved.request().conflictPolicy());
            assertEquals(probe, saved.probe()); assertTrue(saved.readyToPublish());
            assertEquals(100, saved.durableBytes());
        }
    }
    @Test void existingLegacyLeafNamesStayReadableWithoutRewritingTheirTargets() throws Exception {
        var request = request(); Path database = directory.resolve("legacy-name.db");
        try (var store = new SqliteDownloadStore(database)) { store.create(request, directory.resolve("part")); }
        Path legacy = directory.resolve("CON.txt");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("UPDATE download SET target_path=? WHERE id=?")) {
            statement.setString(1, legacy.toString()); statement.setString(2, request.id().toString()); statement.executeUpdate();
        }
        try (var store = new SqliteDownloadStore(database)) { assertEquals(legacy, store.get(request.id()).request().target()); }
    }

}
