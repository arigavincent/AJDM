package io.ajdm.ipc;

import com.google.gson.*;
import io.ajdm.bench.FaultyRangeServer;
import io.ajdm.core.CoreService;
import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
@SuppressWarnings("try") // Server lifetime is the test fixture; requests exercise it through separate clients.
class IpcIntegrationTest {
    @Test void mediaSessionIpcRequiresAnExplicitAllowlistedSourceAndClearRemovesIt() throws Exception {
        var selected = new java.util.concurrent.atomic.AtomicReference<String>();
        var extractor = new io.ajdm.media.MediaExtractor() {
            public io.ajdm.media.MediaInfo extract(URI page, java.util.function.BooleanSupplier canceled) throws DownloadException { throw new DownloadException(ErrorCode.MEDIA_ACCESS_BLOCKED, "secret"); }
            public void configureBrowser(io.ajdm.media.MediaBrowser browser) { selected.set(browser.toolName()); }
            public void configureCookies(Path file) { selected.set(file == null ? null : "file"); }
        };
        try (var core = new CoreService(database(), extractor); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); assertNull(selected.get());
            assertTrue(client.call("mediaCookies", p("browser", "firefox")).get("configured").getAsBoolean());
            assertEquals("firefox", selected.get());
            var conflict = p("browser", "chrome"); conflict.addProperty("file", "/private/cookies.txt");
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class, () -> client.call("mediaCookies", conflict)).code());
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class, () -> client.call("mediaCookies", p("browser", "firefox:secret"))).code());
            assertEquals("firefox", selected.get());
            assertFalse(client.call("mediaCookies", new JsonObject()).get("configured").getAsBoolean()); assertNull(selected.get());
            var failure = assertThrows(DownloadException.class, () -> client.call("formats", p("url", "https://www.facebook.com/watch/?v=123")));
            assertEquals(ErrorCode.MEDIA_ACCESS_BLOCKED, failure.code()); assertFalse(failure.userMessage().contains("secret"));
        }
    }
    @TempDir Path temp;
    private Path socket() { return temp.resolve("ipc/ajdm.sock"); }
    private Path database() { return temp.resolve("state/ajdm.db"); }
    private static JsonObject p(String name, String value) { var p = new JsonObject(); p.addProperty(name, value); return p; }
    private JsonObject row(IpcClient client, String id) throws Exception {
        for (var e : client.call("list", new JsonObject()).getAsJsonArray("downloads")) {
            var row = e.getAsJsonObject(); if (id.equals(row.get("id").getAsString())) return row;
        }
        throw new AssertionError("Missing download");
    }
    private JsonObject awaitState(IpcClient client, String id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonObject row;
        do { row = row(client, id); if (state.equals(row.get("state").getAsString())) return row; Thread.sleep(10); }
        while (System.nanoTime() < deadline);
        fail("Expected " + state + ", actual " + row); return row;
    }
    private static JsonObject queue(boolean scheduled, java.time.LocalTime start, java.time.LocalTime end, boolean budgeted, int dailyMiB) {
        var q = new JsonObject(); q.addProperty("maxActive", 3); q.addProperty("scheduled", scheduled);
        q.addProperty("start", start.withSecond(0).withNano(0).toString()); q.addProperty("end", end.withSecond(0).withNano(0).toString());
        q.addProperty("budgeted", budgeted); q.addProperty("dailyMiB", dailyMiB); return q;
    }
    @Test void theSpeedLimitPacesEveryTransfer() throws Exception {
        var now = java.time.LocalTime.now();
        try (var fixture = new FaultyRangeServer(1024 * 1024, FaultyRangeServer.Mode.NORMAL);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket());
            var limit = queue(false, now, now.plusHours(1), false, 1024); limit.addProperty("limitKiBps", 512);
            client.call("configureQueue", limit);
            assertEquals(512, client.call("queueStatus", new JsonObject()).get("limitKiBps").getAsInt());
            var add = p("url", fixture.uri().toString()); add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("connections", 4);
            long started = System.nanoTime();
            String id = client.call("add", add).get("downloadId").getAsString();
            awaitState(client, id, "COMPLETED");
            double seconds = (System.nanoTime() - started) / 1e9;
            assertTrue(seconds >= 1.4, "1 MiB at 512 KiB/s took only " + seconds + " s");
        }
    }
    @Test void theSegmentMapReportsSavedCoverage() throws Exception {
        try (var fixture = new FaultyRangeServer(300_000, FaultyRangeServer.Mode.NORMAL);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString()); add.addProperty("directory", temp.resolve("files").toString());
            String id = client.call("add", add).get("downloadId").getAsString();
            awaitState(client, id, "COMPLETED");
            var map = client.call("segments", p("downloadId", id));
            assertEquals(300_000, map.get("total").getAsLong());
            assertEquals(1, map.getAsJsonArray("saved").size());
            assertEquals(0, map.getAsJsonArray("saved").get(0).getAsJsonArray().get(0).getAsLong());
            assertEquals(300_000, map.getAsJsonArray("saved").get(0).getAsJsonArray().get(1).getAsLong());
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class,
                    () -> client.call("segments", p("downloadId", java.util.UUID.randomUUID().toString()))).code());
        }
    }
    @Test void outsideTheScheduleNewDownloadsWaitAndTheQueueSurvivesARestart() throws Exception {
        var now = java.time.LocalTime.now();
        try (var fixture = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.NORMAL)) {
            String id;
            try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket());
                client.call("configureQueue", queue(true, now.plusHours(2), now.plusHours(3), false, 1024));
                var add = p("url", fixture.uri().toString()); add.addProperty("directory", temp.resolve("files").toString());
                id = client.call("add", add).get("downloadId").getAsString();
                var waiting = row(client, id);
                assertEquals("QUEUED", waiting.get("state").getAsString()); assertEquals("schedule", waiting.get("waiting").getAsString());
                var status = client.call("queueStatus", new JsonObject());
                assertTrue(status.get("scheduled").getAsBoolean()); assertEquals("schedule", status.get("holding").getAsString());
                assertEquals(0, fixture.requests(), "Nothing is fetched outside the window");
            }
            try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket());
                assertEquals("QUEUED", row(client, id).get("state").getAsString(), "The queue survives a restart");
                client.call("configureQueue", queue(false, now, now.plusHours(1), false, 1024));
                awaitState(client, id, "COMPLETED");
                assertTrue(Files.exists(temp.resolve("files")));
            }
        }
    }
    @Test void theDailyBudgetPausesRunningDownloadsAndRequeuesThem() throws Exception {
        var now = java.time.LocalTime.now();
        try (var fixture = new FaultyRangeServer(16 * 1024 * 1024, FaultyRangeServer.Mode.CAPPED, 256 * 1024);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket());
            client.call("configureQueue", queue(false, now, now.plusHours(1), true, 1));
            var add = p("url", fixture.uri().toString()); add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("connections", 4);
            String id = client.call("add", add).get("downloadId").getAsString();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            JsonObject row = row(client, id);
            while (!(row.get("state").getAsString().equals("QUEUED") && row.has("waiting")) && System.nanoTime() < deadline) { Thread.sleep(50); row = row(client, id); }
            assertEquals("budget", row.get("waiting").getAsString(), "Reaching the budget pauses and requeues the download: " + row);
            long used = client.call("queueStatus", new JsonObject()).get("usedTodayBytes").getAsLong();
            assertTrue(used >= 1024 * 1024 && used < 8 * 1024 * 1024, "Used " + used);
            int requests = fixture.requests(); Thread.sleep(1500);
            assertEquals(requests, fixture.requests(), "No transfer runs over budget");
            client.call("configureQueue", queue(false, now, now.plusHours(1), false, 1));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (row(client, id).get("state").getAsString().equals("QUEUED") && System.nanoTime() < deadline) Thread.sleep(50);
            assertNotEquals("QUEUED", row(client, id).get("state").getAsString(), "Lifting the budget starts it again");
            client.call("cancel", p("downloadId", id));
        }
    }
    @Test void clientExitDoesNotStopTransferAndListDoesNotExposeSecrets() throws Exception {
        try (var fixture = new FaultyRangeServer(200_017, FaultyRangeServer.Mode.NORMAL);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri() + "?token=topsecret");
            add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("filename", "result.bin");
            add.addProperty("sha256", fixture.sha256()); add.addProperty("cookies", "sid=cookiesecret");
            String id = client.call("add", add).get("downloadId").getAsString();
            var fresh = new IpcClient(socket()); assertTrue(fresh.call("ping", new JsonObject()).has("version"));
            var row = awaitState(fresh, id, "COMPLETED");
            assertArrayEquals(fixture.data(), Files.readAllBytes(Path.of(row.get("target").getAsString())));
            String listed = fresh.call("list", new JsonObject()).toString();
            assertFalse(listed.contains("topsecret")); assertFalse(listed.contains("cookiesecret")); assertFalse(listed.contains("url"));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(socket()));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(socket().getParent()));
        }
        assertFalse(Files.exists(socket()));
    }
    @Test void controlsRemainDurableAcrossCoreRestartAndCancelDoesNotFetch() throws Exception {
        String id;
        try (var fixture = new FaultyRangeServer(5 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW)) {
            try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString());
                add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("connections", 1);
                id = client.call("add", add).get("downloadId").getAsString(); assertTrue(fixture.awaitTransfer());
                client.call("pause", p("downloadId", id)); assertEquals("PAUSED", row(client, id).get("state").getAsString());
                client.call("pause", p("downloadId", id));
            }
            int requests = fixture.requests();
            try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); assertEquals("PAUSED", row(client, id).get("state").getAsString());
                assertEquals(requests, fixture.requests()); client.call("cancel", p("downloadId", id));
                assertEquals("CANCELED", row(client, id).get("state").getAsString()); assertEquals(requests, fixture.requests());
                client.call("cancel", p("downloadId", id));
                assertEquals(ErrorCode.INVALID_STATE, assertThrows(DownloadException.class, () -> client.call("resume", p("downloadId", id))).code());
                try (var files = Files.list(temp.resolve("files"))) {
                    assertEquals(1, files.filter(f -> f.toString().endsWith(".ajdmpart")).count());
                }
            }
        }
    }
    @Test void interruptionAfterRecordCreationIsPausedWithoutInventingAScheduler() throws Exception {
        Path folder = temp.resolve("files"); Files.createDirectories(folder);
        var request = TransferRequest.of(URI.create("https://example.test/file"), folder.resolve("file"));
        Path partial = Files.createTempFile(folder, ".ajdm-", ".ajdmpart");
        try (var store = new io.ajdm.store.SqliteDownloadStore(database())) { store.create(request, partial); }
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); String id = request.id().toString();
            assertEquals("PAUSED", row(client, id).get("state").getAsString());
            client.call("cancel", p("downloadId", id)); assertEquals("CANCELED", row(client, id).get("state").getAsString());
            assertTrue(Files.exists(partial));
        }
    }
    @Test void pauseResumeAndActiveCancelAreAcknowledgedAfterDurableStop() throws Exception {
        try (var fixture = new FaultyRangeServer(5 * 1024 * 1024, FaultyRangeServer.Mode.SLOW);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString());
            add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("connections", 1);
            String id = client.call("add", add).get("downloadId").getAsString(); assertTrue(fixture.awaitTransfer());
            client.call("pause", p("downloadId", id));
            client.call("resume", p("downloadId", id)); client.call("cancel", p("downloadId", id));
            assertEquals("CANCELED", row(client, id).get("state").getAsString());
        }
    }
    @Test void duplicateServerCannotUnlinkLiveSocket() throws Exception {
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var failure = assertThrows(DownloadException.class, () -> new IpcServer(socket(), core));
            assertEquals(ErrorCode.STATE_IN_USE, failure.code()); assertTrue(new IpcClient(socket()).call("ping", new JsonObject()).has("version"));
        }
    }
    @Test void staleSocketRecoversOnlyWithLockOwnership() throws Exception {
        Files.createDirectories(socket().getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try (var old = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) { old.bind(UnixDomainSocketAddress.of(socket())); Endpoint.secureSocket(socket()); }
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            assertTrue(new IpcClient(socket()).call("ping", new JsonObject()).has("version"));
        }
    }
    @Test void unresponsiveLiveSocketWithoutLockIsNotDeleted() throws Exception {
        Files.createDirectories(socket().getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try (var foreign = ServerSocketChannel.open(StandardProtocolFamily.UNIX); var core = new CoreService(database())) {
            foreign.bind(UnixDomainSocketAddress.of(socket())); Endpoint.secureSocket(socket());
            Object key = Files.readAttributes(socket(), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            assertEquals(ErrorCode.STATE_IN_USE, assertThrows(DownloadException.class, () -> new IpcServer(socket(), core)).code());
            assertEquals(key, Files.readAttributes(socket(), java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        }
    }
    @Test void aFourthTransferWaitsInTheQueueAndStartsWhenASlotFrees() throws Exception {
        try (var fixture = new FaultyRangeServer(8 * 1024 * 1024, FaultyRangeServer.Mode.CAPPED, 128 * 1024);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString());
            add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("connections", 1);
            var ids = new ArrayList<String>();
            for (int i = 0; i < 3; i++) ids.add(client.call("add", add).get("downloadId").getAsString());
            String fourth = client.call("add", add).get("downloadId").getAsString(); ids.add(fourth);
            assertEquals(4, core.list().size());
            assertEquals("QUEUED", row(client, fourth).get("state").getAsString());
            assertEquals("slot", row(client, fourth).get("waiting").getAsString());
            client.call("cancel", p("downloadId", ids.getFirst()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (row(client, fourth).get("state").getAsString().equals("QUEUED") && System.nanoTime() < deadline) Thread.sleep(50);
            assertNotEquals("QUEUED", row(client, fourth).get("state").getAsString(), "The queue starts it once a slot is free");
            assertFalse(row(client, fourth).has("waiting"));
            for (String id : ids) client.call("cancel", p("downloadId", id));
        }
    }
    @Test void regularFilesAndSymlinksAreNeverRemovedAsStaleSockets() throws Exception {
        Files.createDirectories(socket().getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(socket(), "keep"); Files.setPosixFilePermissions(socket(), PosixFilePermissions.fromString("rw-------"));
        try (var core = new CoreService(database())) { assertThrows(java.io.IOException.class, () -> new IpcServer(socket(), core)); }
        assertEquals("keep", Files.readString(socket()));
        Path linked = temp.resolve("linked"); Files.createSymbolicLink(linked, socket().getParent());
        try (var core = new CoreService(database())) { assertThrows(java.io.IOException.class, () -> new IpcServer(linked.resolve("other.sock"), core)); }
    }
    @Test void refusesInsecureDirectoriesAndLockSymlinks() throws Exception {
        Files.createDirectories(socket().getParent()); Files.setPosixFilePermissions(socket().getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        try (var core = new CoreService(database())) { assertThrows(java.io.IOException.class, () -> new IpcServer(socket(), core)); }
        Files.setPosixFilePermissions(socket().getParent(), PosixFilePermissions.fromString("rwx------"));
        Path foreign = temp.resolve("foreign"); Files.writeString(foreign, "keep");
        Files.createSymbolicLink(socket().resolveSibling("ajdm.sock.lock"), foreign);
        try (var core = new CoreService(database())) { assertThrows(java.io.IOException.class, () -> new IpcServer(socket(), core)); }
        assertEquals("keep", Files.readString(foreign));
    }
    @Test void slowPeerExpiresAndHealthyClientStillWorks() throws Exception {
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core, Duration.ofMillis(100));
             var slow = SocketChannel.open(UnixDomainSocketAddress.of(socket()))) {
            slow.write(ByteBuffer.wrap(new byte[]{0}));
            Thread.sleep(200);
            assertTrue(new IpcClient(socket()).call("ping", new JsonObject()).has("version"));
            slow.configureBlocking(false); var bytes = ByteBuffer.allocate(4096);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); int count;
            do { count = slow.read(bytes); if (count == -1) break; Thread.sleep(5); } while (System.nanoTime() < deadline);
            assertEquals(-1, count);
        }
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 1048577, Integer.MAX_VALUE})
    void invalidFrameLengthsAreRejectedWithoutAllocation(int length) throws Exception {
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core);
             var channel = SocketChannel.open(UnixDomainSocketAddress.of(socket()))) {
            channel.write(ByteBuffer.allocate(4).putInt(length).flip());
            var response = Frames.read(channel, Duration.ofSeconds(2));
            assertFalse(response.get("ok").getAsBoolean()); assertEquals("AJDM-1003", response.getAsJsonObject("error").get("code").getAsString());
            assertTrue(new IpcClient(socket()).call("ping", new JsonObject()).has("version"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"v\":2,\"id\":\"x\",\"type\":\"ping\",\"payload\":{}}",
        "{\"v\":\"1\",\"id\":\"x\",\"type\":\"ping\",\"payload\":{}}",
        "{\"v\":1,\"id\":\"https://host/?secret\",\"type\":\"ping\",\"payload\":{}}",
        "{\"v\":1,\"id\":\"x\",\"type\":\"ping\",\"payload\":{\"extra\":\"secret\"}}",
        "{\"v\":1,\"id\":\"x\",\"type\":\"add\",\"payload\":{\"url\":\"https://host/?secret\",\"filename\":\"../escape\"}}",
        "{\"v\":1,\"id\":\"x\",\"type\":\"add\",\"payload\":{\"url\":\"https://host/?secret\",\"connections\":1.5}}"})
    void invalidCommandsAreSafeAndDoNotCreateRecords(String request) throws Exception {
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core);
             var channel = SocketChannel.open(UnixDomainSocketAddress.of(socket()))) {
            Frames.write(channel, Protocol.parse(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Duration.ofSeconds(2));
            String response = Frames.read(channel, Duration.ofSeconds(2)).toString();
            assertTrue(response.contains("AJDM-1003")); assertFalse(response.contains("secret")); assertEquals(0, core.list().size());
        }
    }
    @Test void missingExtractorAndUnimplementedQueuesReturnExplicitErrors() throws Exception {
        try (var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket());
            assertEquals(ErrorCode.EXTRACTION_FAILED, assertThrows(DownloadException.class, () -> client.call("formats", p("url", "https://host/video"))).code());
            var named = p("url", "https://host/file"); named.addProperty("queue", "nightly");
            assertEquals(ErrorCode.FEATURE_UNAVAILABLE, assertThrows(DownloadException.class, () -> client.call("add", named)).code());
            assertEquals(0, core.list().size());
        }
    }
    @Test void downloadLaterSavesAStoppedDownloadThatResumesOnRequest() throws Exception {
        try (var fixture = new FaultyRangeServer(300_000, FaultyRangeServer.Mode.NORMAL);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString());
            add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("start", false);
            String id = client.call("add", add).get("downloadId").getAsString();
            Thread.sleep(1500);
            assertEquals("PAUSED", core.list().getFirst().state().name(), "Download Later neither starts nor queues it");
            assertEquals(0, fixture.requests(), "Nothing was fetched");
            client.call("resume", p("downloadId", id));
            awaitState(client, id, "COMPLETED");
        }
    }
    @Test void askCollisionCanResumeWithExplicitRenameWithoutDownloadingAgain() throws Exception {
        Path folder = temp.resolve("files"); Files.createDirectories(folder);
        try (var fixture = new FaultyRangeServer(2 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString());
            add.addProperty("directory", folder.toString()); add.addProperty("conflict", "ASK");
            String id = client.call("add", add).get("downloadId").getAsString();
            assertTrue(fixture.awaitTransfer()); Files.writeString(folder.resolve("file"), "keep");
            var failed = awaitState(client, id, "FAILED"); assertEquals("AJDM-2007", failed.getAsJsonObject("error").get("code").getAsString());
            int requests = fixture.requests(); var resume = p("downloadId", id); resume.addProperty("conflict", "RENAME");
            client.call("resume", resume); var completed = awaitState(client, id, "COMPLETED");
            assertEquals(requests, fixture.requests()); assertEquals("keep", Files.readString(folder.resolve("file")));
            assertArrayEquals(fixture.data(), Files.readAllBytes(Path.of(completed.get("target").getAsString())));
        }
    }
    @Test void sourceChangeRestartCreatesNewIdAndKeepsOldPartial() throws Exception {
        try (var fixture = new FaultyRangeServer(5 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW);
             var core = new CoreService(database()); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var add = p("url", fixture.uri().toString());
            add.addProperty("directory", temp.resolve("files").toString()); add.addProperty("connections", 1);
            String old = client.call("add", add).get("downloadId").getAsString(); assertTrue(fixture.awaitTransfer());
            Thread.sleep(100); client.call("pause", p("downloadId", old));
            Path partial;
            try (var files = Files.list(temp.resolve("files"))) { partial = files.filter(f -> f.toString().endsWith(".ajdmpart")).findFirst().orElseThrow(); }
            byte[] bytes = Files.readAllBytes(partial); fixture.etag("\"changed\"");
            client.call("resume", p("downloadId", old));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!row(client, old).has("error") && System.nanoTime() < deadline) Thread.sleep(10);
            var stopped = row(client, old); assertEquals("PAUSED", stopped.get("state").getAsString());
            assertEquals("AJDM-3001", stopped.getAsJsonObject("error").get("code").getAsString());
            String next = client.call("restart", p("downloadId", old)).get("downloadId").getAsString(); assertNotEquals(old, next);
            var result = awaitState(client, next, "COMPLETED");
            assertArrayEquals(bytes, Files.readAllBytes(partial)); assertEquals("PAUSED", row(client, old).get("state").getAsString());
            assertArrayEquals(fixture.data(), Files.readAllBytes(Path.of(result.get("target").getAsString())));
        }
    }
}
