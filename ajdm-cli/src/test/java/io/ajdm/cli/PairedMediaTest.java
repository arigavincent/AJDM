package io.ajdm.cli;

import com.google.gson.*;
import io.ajdm.core.*;
import io.ajdm.ipc.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(70)
@SuppressWarnings("try")
class PairedMediaTest {
    @TempDir Path temp;
    private Path db() { return temp.resolve("state/ajdm.db"); }
    private Path files() { return temp.resolve("files"); }
    private Path socket() { return temp.resolve("ipc/ajdm.sock"); }
    private JsonObject metadata(MediaHttpsFixture f, boolean old) {
        var root = new JsonObject(); root.addProperty("id", "pair"); root.addProperty("title", "Pair"); var formats = new JsonArray();
        var headers = new JsonObject(); headers.addProperty("Cookie", "sid=secret"); headers.addProperty("User-Agent", "AJDM-extractor-fixture"); root.add("http_headers", headers);
        for (boolean video : new boolean[]{true, false}) {
            var row = new JsonObject(); row.addProperty("format_id", video ? "video" : "audio"); row.addProperty("ext", video ? "mp4" : "m4a");
            row.addProperty("protocol", "https"); row.addProperty("url", video ? f.url(old) : f.audioUrl());
            row.addProperty("vcodec", video ? "avc1" : "none"); row.addProperty("acodec", video ? "none" : "mp4a");
            if (video) row.addProperty("height", 1080); else row.addProperty("abr", 128);
            formats.add(row);
        } root.add("formats", formats); return root;
    }
    private MediaExtractor extractor(MediaHttpsFixture f) { return (page, canceled) -> new MediaParser().parse(metadata(f, !f.expired).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), page); }
    private UUID add(CoreService core, ConflictPolicy policy) throws Exception {
        return core.addMedia("https://page.test/pair", "video", "audio", null, true, files(), null, policy, 1).id();
    }
    private CoreService.Row row(CoreService core, UUID id) throws Exception { return core.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); }
    private CoreService.Row waitState(CoreService core, UUID id, DownloadState state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25); CoreService.Row row;
        do { row = row(core, id); if (row.state() == state) return row; Thread.sleep(20); } while (System.nanoTime() < deadline);
        fail("Expected " + state + " but saw " + row); return row;
    }
    private MediaMuxer copying(AtomicInteger calls) {
        return (v, ve, a, ae, out, c, canceled) -> { calls.incrementAndGet(); Files.write(out, Files.readAllBytes(v), StandardOpenOption.CREATE_NEW); };
    }
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void mediaCliChoosesAndPersistsSchedulingModeForEveryStream(boolean paired, boolean adaptive) throws Exception {
        byte[] video = new byte[4 * 1024 * 1024 + 17], audio = new byte[64 * 1024 + 17];
        new Random(182).nextBytes(video); new Random(183).nextBytes(audio);
        try (var fixture = new MediaHttpsFixture(temp, Map.of("/old", video, "/audio", audio), 1024 * 1024)) {
            UUID id;
            try (var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger())); var server = new IpcServer(socket(), core)) {
                var command = new ArrayList<>(List.of("--socket", socket().toString(), "add-media", "https://page.test/pair",
                        "--format", "video", "--directory", files().toString(), "--connections", "2"));
                if (paired) command.addAll(List.of("--audio", "audio"));
                if (adaptive) command.add("--adaptive");
                var out = new StringWriter(); var err = new StringWriter();
                assertEquals(0, AjdmCli.execute(command.toArray(String[]::new), new PrintWriter(out), new PrintWriter(err)), err.toString());
                id = UUID.fromString(out.toString().lines().findFirst().orElseThrow());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (row(core, id).writtenBytes() < 256 * 1024 && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(row(core, id).writtenBytes() >= 256 * 1024);
                core.pause(id);
            }
            try (var store = new io.ajdm.store.SqliteDownloadStore(db())) {
                var records = store.list(); assertEquals(paired ? 2 : 1, records.size());
                for (var record : records) {
                    assertEquals(adaptive, record.request().options().adaptive());
                    assertEquals(2, record.request().options().connections());
                }
            }
            try (var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()))) {
                core.resume(id, Map.of("Cookie", "sid=secret", "User-Agent", "AJDM-extractor-fixture"), null, 4); waitState(core, id, DownloadState.COMPLETED);
                assertArrayEquals(video, Files.readAllBytes(files().resolve("Pair.mp4")));
            }
            try (var store = new io.ajdm.store.SqliteDownloadStore(db())) {
                for (var record : store.list()) assertEquals(adaptive, record.request().options().adaptive(), "Resume must preserve selected scheduling mode");
            }
            assertFalse(fixture.missingHeaders);
        }
    }
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void resumeCliChangesConnectionsWithoutLosingBytesOrRedownloadingCompletedAudio(boolean paired, boolean reopen) throws Exception {
        byte[] video = new byte[8 * 1024 * 1024 + 17], audio = new byte[64 * 1024 + 17]; new Random(82).nextBytes(video); new Random(83).nextBytes(audio);
        try (var fixture = new MediaHttpsFixture(temp, Map.of("/old", video, "/audio", audio), 1024 * 1024)) {
            CoreService core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()));
            try {
                UUID id = core.addMedia("https://page.test/pair", "video", paired ? "audio" : null, null, true, files(), null, ConflictPolicy.RENAME, 1).id();
                var out = new StringWriter(); var err = new StringWriter();
                try (var server = new IpcServer(socket(), core)) {
                    assertEquals(3, AjdmCli.execute(new String[]{"--socket", socket().toString(), "resume", id.toString(), "--connections", "8"}, new PrintWriter(out), new PrintWriter(err)));
                    for (String count : List.of("0", "33")) assertEquals(2, AjdmCli.execute(new String[]{"--socket", socket().toString(), "resume", id.toString(), "--connections", count}, new PrintWriter(out), new PrintWriter(err)));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (row(core, id).writtenBytes() < 500_000 && System.nanoTime() < deadline) Thread.sleep(20);
                    assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "pause", id.toString()}, new PrintWriter(out), new PrintWriter(err)));
                }
                long saved = row(core, id).durableBytes(); assertTrue(saved > 0); int audioRequests = fixture.newRequests.get();
                if (paired) assertTrue(saved > audio.length);
                if (reopen) { core.close(); core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger())); }
                assertEquals(saved, row(core, id).durableBytes()); fixture.starts.clear();
                try (var server = new IpcServer(socket(), core)) {
                    assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "resume", id.toString(), "--connections", "8"}, new PrintWriter(out), new PrintWriter(err)), err.toString());
                    try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db()); var sql = conn.createStatement(); var rows = sql.executeQuery("SELECT connections,state FROM download")) {
                        int records = 0; while (rows.next()) { records++; assertEquals(rows.getString(2).equals("COMPLETED") ? 1 : 8, rows.getInt(1)); } assertEquals(paired ? 2 : 1, records);
                    }
                    waitState(core, id, DownloadState.COMPLETED);
                }
                assertEquals(1, core.list().size()); assertArrayEquals(video, Files.readAllBytes(files().resolve("Pair.mp4")));
                if (paired) assertEquals(audioRequests, fixture.newRequests.get());
                long prefix = saved - (paired ? audio.length : 0);
                assertFalse(fixture.starts.isEmpty()); assertTrue(fixture.starts.stream().allMatch(offset -> offset >= prefix), fixture.starts.toString());
                assertTrue(fixture.starts.stream().distinct().count() >= 3, "New generation must actually run parallel ranges");
            } finally { core.close(); }
        }
    }
    private void ffmpeg(String... args) throws Exception {
        var command = new ArrayList<>(List.of(FakeTool.ffmpeg().toString(), "-hide_banner", "-nostdin", "-loglevel", "error")); command.addAll(List.of(args));
        var child = new ProcessBuilder(command).redirectError(temp.resolve("ffmpeg-test.err").toFile()).redirectOutput(temp.resolve("ffmpeg-test.out").toFile()).start();
        assertTrue(child.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, child.exitValue(), Files.readString(temp.resolve("ffmpeg-test.err")));
    }
    @ParameterizedTest @ValueSource(strings = {"mp4", "mkv", "mkv-mp3"})
    void realCliPairMuxPreservesStreamHashesAndDecodes(String container) throws Exception {
        boolean mp3 = container.equals("mkv-mp3"), webm = container.equals("mkv");
        if (mp3) container = "mkv";
        Path video = temp.resolve(webm ? "source-video.webm" : "source.mp4"), audio = temp.resolve(mp3 ? "source.mp3" : webm ? "source-audio.webm" : "source.m4a");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-t", "1", "-c:v", webm ? "libvpx-vp9" : "libx264", "-an", video.toString());
        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "1", "-c:a", mp3 ? "libmp3lame" : webm ? "libopus" : "aac", "-vn", audio.toString());
        try (var fixture = new MediaHttpsFixture(temp, Map.of("/old", Files.readAllBytes(video), "/audio", Files.readAllBytes(audio)), 0);
             var core = new CoreService(db(), (page, cancel) -> {
                 JsonObject meta = metadata(fixture, true);
                 if (webm) {
                     var v = meta.getAsJsonArray("formats").get(0).getAsJsonObject(); v.addProperty("ext", "webm"); v.addProperty("vcodec", "vp9");
                     var a = meta.getAsJsonArray("formats").get(1).getAsJsonObject(); a.addProperty("ext", "webm"); a.addProperty("acodec", "opus");
                 }
                 if (mp3) {
                     var a = meta.getAsJsonArray("formats").get(1).getAsJsonObject(); a.addProperty("ext", "mp3"); a.addProperty("acodec", "mp3");
                 }
                 return new MediaParser().parse(meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), page);
             }, new FfmpegMuxer(FakeTool.ffmpeg())); var server = new IpcServer(socket(), core)) {
            var out = new StringWriter(); var err = new StringWriter();
            assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "add-media", "https://page.test/pair", "--format", "video", "--audio", "audio",
                    "--directory", files().toString(), "--wait"}, new PrintWriter(out), new PrintWriter(err)), err.toString());
            Path result = files().resolve("Pair." + container); assertTrue(Files.size(result) > 0); assertEquals(1, core.list().size());
            ffmpeg("-i", video.toString(), "-map", "0:v:0", "-c", "copy", "-f", "streamhash", "-hash", "sha256", "-"); String videoHash = Files.readString(temp.resolve("ffmpeg-test.out"));
            ffmpeg("-i", result.toString(), "-map", "0:v:0", "-c", "copy", "-f", "streamhash", "-hash", "sha256", "-"); assertEquals(videoHash, Files.readString(temp.resolve("ffmpeg-test.out")));
            ffmpeg("-i", audio.toString(), "-map", "0:a:0", "-c", "copy", "-f", "streamhash", "-hash", "sha256", "-"); String audioHash = Files.readString(temp.resolve("ffmpeg-test.out"));
            ffmpeg("-i", result.toString(), "-map", "0:a:0", "-c", "copy", "-f", "streamhash", "-hash", "sha256", "-"); assertEquals(audioHash, Files.readString(temp.resolve("ffmpeg-test.out")));
            ffmpeg("-i", result.toString(), "-f", "null", "-"); assertFalse(fixture.missingHeaders);
            try (var paths = Files.list(files())) { assertEquals(List.of("Pair." + container), paths.map(p -> p.getFileName().toString()).toList()); }
        }
    }
    @Test void muxFailureKeepsInputsAndReopenRetryPerformsNoHttp() throws Exception {
        AtomicInteger muxCalls = new AtomicInteger(); UUID id;
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0)) {
            try (var core = new CoreService(db(), extractor(fixture), (v, ve, a, ae, out, c, cancel) -> { throw new DownloadException(ErrorCode.MUX_FAILED, "secret"); })) {
                id = add(core, ConflictPolicy.RENAME); assertEquals(ErrorCode.MUX_FAILED, waitState(core, id, DownloadState.FAILED).error());
                Path workspace = files().resolve(".ajdm-media-" + id); assertArrayEquals(fixture.data, Files.readAllBytes(workspace.resolve("video.mp4"))); assertArrayEquals(fixture.data, Files.readAllBytes(workspace.resolve("audio.m4a")));
            }
            int calls = fixture.oldRequests.get() + fixture.newRequests.get();
            try (var core = new CoreService(db(), extractor(fixture), copying(muxCalls))) {
                core.resume(id, Map.of(), null); waitState(core, id, DownloadState.COMPLETED);
                assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("Pair.mp4"))); assertEquals(calls, fixture.oldRequests.get() + fixture.newRequests.get()); assertEquals(1, muxCalls.get());
            }
        }
    }
    @Test void interruptedMuxKeepsStreamsAndCanResume() throws Exception {
        var entered = new CountDownLatch(1); UUID id; AtomicInteger calls = new AtomicInteger();
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0)) {
            try (var core = new CoreService(db(), extractor(fixture), (v, ve, a, ae, out, c, cancel) -> { entered.countDown(); Thread.sleep(30_000); })) {
                id = add(core, ConflictPolicy.RENAME); assertTrue(entered.await(10, TimeUnit.SECONDS)); core.pause(id); assertEquals(DownloadState.PAUSED, row(core, id).state());
                assertTrue(Files.exists(files().resolve(".ajdm-media-" + id + "/video.mp4")));
            }
            try (var core = new CoreService(db(), extractor(fixture), copying(calls))) { core.resume(id, Map.of(), null); waitState(core, id, DownloadState.COMPLETED); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void parentControlTerminatesRealMuxSubprocessWithCorrectStoppedState(boolean cancel) throws Exception {
        Path tool = FakeTool.python(temp.resolve("blocking-ffmpeg"), "import os,pathlib,time\npathlib.Path(__file__).with_name('mux-pid').write_text(str(os.getpid()))\ntime.sleep(30)\n");
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0);
             var core = new CoreService(db(), extractor(fixture), new FfmpegMuxer(tool))) {
            UUID id = add(core, ConflictPolicy.RENAME); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(temp.resolve("mux-pid")) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(temp.resolve("mux-pid"))); long pid = Long.parseLong(Files.readString(temp.resolve("mux-pid")));
            if (cancel) core.cancel(id); else core.pause(id);
            var row = row(core, id); assertEquals(cancel ? DownloadState.CANCELED : DownloadState.PAUSED, row.state()); assertEquals(cancel ? ErrorCode.CANCELED : null, row.error());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(Files.exists(files().resolve(".ajdm-media-" + id + "/video.mp4"))); assertFalse(Files.exists(files().resolve("Pair.mp4")));
        }
    }
    @Test void inputTamperingAfterMuxFailurePreventsRetry() throws Exception {
        UUID id;
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0)) {
            try (var core = new CoreService(db(), extractor(fixture), (v, ve, a, ae, out, c, cancel) -> { throw new DownloadException(ErrorCode.MUX_FAILED, "failed"); })) {
                id = add(core, ConflictPolicy.RENAME); waitState(core, id, DownloadState.FAILED);
            }
            Path input = files().resolve(".ajdm-media-" + id + "/video.mp4"); byte[] data = Files.readAllBytes(input); data[10] ^= 1; Files.write(input, data); AtomicInteger muxCalls = new AtomicInteger();
            try (var core = new CoreService(db(), extractor(fixture), copying(muxCalls))) {
                core.resume(id, Map.of(), null); assertEquals(ErrorCode.CHECKSUM_MISMATCH, waitState(core, id, DownloadState.FAILED).error()); assertEquals(0, muxCalls.get()); assertFalse(Files.exists(files().resolve("Pair.mp4")));
            }
        }
    }
    @Test void lateAskCollisionReusesReadyOutputAndRenameKeepsExistingFile() throws Exception {
        AtomicInteger muxCalls = new AtomicInteger();
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0);
             var core = new CoreService(db(), extractor(fixture), copying(muxCalls), (job, boundary) -> {
                 if (boundary == MediaJobObserver.Boundary.READY) Files.writeString(job.target(), "existing", StandardOpenOption.CREATE_NEW);
             })) {
            UUID id = add(core, ConflictPolicy.ASK); assertEquals(ErrorCode.TARGET_EXISTS, waitState(core, id, DownloadState.PAUSED).error());
            int requests = fixture.oldRequests.get() + fixture.newRequests.get(); core.resume(id, Map.of(), ConflictPolicy.RENAME); var row = waitState(core, id, DownloadState.COMPLETED);
            assertEquals("existing", Files.readString(files().resolve("Pair.mp4"))); assertEquals(files().resolve("Pair (1).mp4").toString(), row.target());
            assertArrayEquals(fixture.data, Files.readAllBytes(Path.of(row.target()))); assertEquals(1, muxCalls.get()); assertEquals(requests, fixture.oldRequests.get() + fixture.newRequests.get());
        }
    }
    @Test void pairedTransfersPauseTogetherRefreshOnReopenAndShareAdmission() throws Exception {
        UUID id; long saved;
        try (var fixture = new MediaHttpsFixture(temp, 8 * 1024 * 1024 + 17, 1024 * 1024)) {
            try (var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()))) {
                id = add(core, ConflictPolicy.RENAME);
                // A second paired job does not fit beside the first: it waits in the queue instead of being refused.
                UUID waiting = add(core, ConflictPolicy.RENAME);
                assertEquals(DownloadState.QUEUED, row(core, waiting).state());
                core.pause(waiting); assertEquals(DownloadState.PAUSED, row(core, waiting).state());
                core.remove(waiting, true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (row(core, id).writtenBytes() < 500_000 && System.nanoTime() < deadline) Thread.sleep(20);
                core.pause(id); saved = row(core, id).durableBytes(); assertTrue(saved > 0); assertEquals(1, core.list().size());
            }
            fixture.expired = true;
            try (var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()))) {
                assertEquals(DownloadState.PAUSED, row(core, id).state()); assertEquals(saved, row(core, id).durableBytes()); core.resume(id, Map.of(), null); waitState(core, id, DownloadState.COMPLETED);
                assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("Pair.mp4"))); assertTrue(fixture.newRequests.get() > 0);
            }
        }
    }
    @Test void cancelStopsBothChildrenAndRetainsTheirSavedFiles() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 8 * 1024 * 1024 + 17, 1024 * 1024);
             var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()))) {
            UUID id = add(core, ConflictPolicy.RENAME);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (row(core, id).writtenBytes() < 250_000 && System.nanoTime() < deadline) Thread.sleep(20);
            core.cancel(id); assertEquals(DownloadState.CANCELED, row(core, id).state()); core.cancel(id);
            int requests = fixture.oldRequests.get() + fixture.newRequests.get();
            assertThrows(IllegalStateException.class, () -> core.resume(id, Map.of(), null));
            assertThrows(IllegalStateException.class, () -> core.pause(id)); assertEquals(requests, fixture.oldRequests.get() + fixture.newRequests.get());
            try (var paths = Files.list(files().resolve(".ajdm-media-" + id))) { assertEquals(2, paths.filter(p -> p.toString().endsWith(".ajdmpart")).count()); }
        }
    }
    @Test void presetPairExplicitlyOverwritesOnlyAtVerifiedPublication() throws Exception {
        Files.createDirectories(files()); Files.writeString(files().resolve("Pair.mp4"), "existing"); AtomicInteger calls = new AtomicInteger();
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0);
             var core = new CoreService(db(), extractor(fixture), copying(calls))) {
            UUID id = core.addMedia("https://page.test/pair", null, MediaSelection.Preset.P1080, true, files(), null, ConflictPolicy.OVERWRITE, 1).id();
            waitState(core, id, DownloadState.COMPLETED); assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("Pair.mp4"))); assertEquals(1, calls.get());
        }
    }
    @Test void changedSourcePausesParentAndExplicitRestartKeepsOldWorkspace() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 8 * 1024 * 1024 + 17, 2 * 1024 * 1024);
             var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()))) {
            UUID id = add(core, ConflictPolicy.RENAME);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (row(core, id).writtenBytes() < 500_000 && System.nanoTime() < deadline) Thread.sleep(20);
            core.pause(id); long saved = row(core, id).durableBytes(); assertTrue(saved > 0);
            Path old = files().resolve(".ajdm-media-" + id); var before = new HashMap<Path, byte[]>();
            try (var paths = Files.list(old)) { for (var path : paths.toList()) before.put(path, Files.readAllBytes(path)); }
            fixture.etag = "\"new-representation\""; core.resume(id, Map.of(), null);
            assertEquals(ErrorCode.SOURCE_CHANGED, waitState(core, id, DownloadState.PAUSED).error());
            UUID next = core.restart(id, Map.of()); assertNotEquals(id, next); waitState(core, next, DownloadState.COMPLETED);
            assertEquals(saved, row(core, id).durableBytes()); assertEquals(ErrorCode.SOURCE_CHANGED, row(core, id).error());
            for (var entry : before.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
            assertEquals(2, core.list().size());
        }
    }
    @ParameterizedTest @EnumSource(MediaJobObserver.Boundary.class)
    void forcedKillAtParentBoundariesRecoversWithoutUnwantedTransfers(MediaJobObserver.Boundary boundary) throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 250_017, 0)) {
            Files.writeString(temp.resolve("metadata.json"), metadata(fixture, true).toString());
            Path extractor = FakeTool.python(temp.resolve("extractor"), "import pathlib\nprint((pathlib.Path(__file__).parent/'metadata.json').read_text())\n");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--enable-native-access=ALL-UNNAMED",
                    "-Djavax.net.ssl.trustStore=" + fixture.keystore, "-Djavax.net.ssl.trustStorePassword=test-only-password", "-cp", System.getProperty("ajdm.test.runtime.classpath"),
                    PairedMediaWorker.class.getName(), db().toString(), files().toString(), extractor.toString(), boundary.name(), temp.resolve("marker").toString())
                    .redirectOutput(temp.resolve("worker.out").toFile()).redirectError(temp.resolve("worker.err").toFile()).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (!Files.exists(temp.resolve("marker")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(Files.exists(temp.resolve("marker")), Files.readString(temp.resolve("worker.err"))); child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
            int requests = fixture.oldRequests.get() + fixture.newRequests.get(); AtomicInteger muxCalls = new AtomicInteger();
            try (var core = new CoreService(db(), extractor(fixture), copying(muxCalls))) {
                UUID id = core.list().getFirst().id(); assertEquals(DownloadState.PAUSED, row(core, id).state());
                assertEquals(boundary != MediaJobObserver.Boundary.PREPARED, row(core, id).resumeSupported());
                core.resume(id, Map.of(), null); waitState(core, id, DownloadState.COMPLETED);
                assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("Pair.mp4")));
                if (boundary != MediaJobObserver.Boundary.PREPARED) assertEquals(requests, fixture.oldRequests.get() + fixture.newRequests.get());
                assertEquals(boundary == MediaJobObserver.Boundary.PREPARED || boundary == MediaJobObserver.Boundary.MERGING ? 1 : 0, muxCalls.get());
            }
        }
    }
    @Test void pairedCheckpointForcedKillReopensPausedAndRenewsExpiredVideo() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 8 * 1024 * 1024 + 17, 1024 * 1024)) {
            Files.writeString(temp.resolve("metadata.json"), metadata(fixture, true).toString());
            Path extractor = FakeTool.python(temp.resolve("extractor"), "import pathlib\nprint((pathlib.Path(__file__).parent/'metadata.json').read_text())\n");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--enable-native-access=ALL-UNNAMED",
                    "-Djavax.net.ssl.trustStore=" + fixture.keystore, "-Djavax.net.ssl.trustStorePassword=test-only-password", "-cp", System.getProperty("ajdm.test.runtime.classpath"),
                    PairedMediaWorker.class.getName(), db().toString(), files().toString(), extractor.toString(), "DOWNLOADING", temp.resolve("marker").toString())
                    .redirectOutput(temp.resolve("worker.out").toFile()).redirectError(temp.resolve("worker.err").toFile()).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(temp.resolve("marker")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(Files.exists(temp.resolve("marker")), Files.readString(temp.resolve("worker.err"))); child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
            fixture.expired = true;
            try (var core = new CoreService(db(), extractor(fixture), copying(new AtomicInteger()))) {
                UUID id = core.list().getFirst().id(); var row = row(core, id); assertEquals(DownloadState.PAUSED, row.state()); assertTrue(row.durableBytes() > 0);
                core.resume(id, Map.of(), null); waitState(core, id, DownloadState.COMPLETED); assertTrue(fixture.newRequests.get() > 0);
                assertArrayEquals(fixture.data, Files.readAllBytes(files().resolve("Pair.mp4")));
            }
        }
    }
}
