package io.ajdm.bench;

import static org.junit.jupiter.api.Assertions.*;

import io.ajdm.engine.HlsDownload;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import io.ajdm.net.CancellationScope;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsDownloadTest {
    @TempDir Path directory;

    private static TransferOptions options(int connections) {
        return new TransferOptions(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
                4096, 8, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), null, Duration.ofMillis(50))
                .withParallelism(connections, 65536);
    }
    private HlsDownload.Result download(HlsFixture origin, String playlist, String name, Path work, int connections) throws Exception {
        var request = new TransferRequest(UUID.randomUUID(), URI.create(origin.origin() + playlist), directory.resolve(name), Map.of(), options(connections));
        return new HlsDownload(request, work, new CancellationScope(), null).run();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test void partsDownloadInParallelAndJoinInPlaylistOrder() throws Exception {
        try (var origin = new HlsFixture(directory)) {
            byte[] expected = origin.segments("/v", 40, "?token=a");
            Path work = directory.resolve("work");
            var result = download(origin, "/v/index.m3u8", "movie.ts", work, 4);
            assertArrayEquals(expected, Files.readAllBytes(result.target()));
            assertEquals(sha256(expected), result.sha256());
            assertEquals(40, result.parts()); assertEquals(160.0, result.durationSeconds(), 1e-9);
            for (int i = 0; i < 40; i++) assertEquals(1, origin.requests("/v/seg-" + i + ".ts"), "Part " + i);
            assertFalse(Files.exists(work), "Work files must be removed after publication");
        }
    }

    @Test void anInitializationSectionAndByteRangesOfOneFileAreJoined() throws Exception {
        try (var origin = new HlsFixture(directory)) {
            byte[] file = new byte[300_000]; new java.util.Random(5).nextBytes(file);
            origin.resources.put("/f/main.mp4", file);
            origin.text("/f/index.m3u8", """
                    #EXTM3U
                    #EXT-X-MAP:URI="main.mp4",BYTERANGE="1000@0"
                    #EXTINF:4,
                    #EXT-X-BYTERANGE:150000@1000
                    main.mp4
                    #EXTINF:4,
                    #EXT-X-BYTERANGE:149000
                    main.mp4
                    #EXT-X-ENDLIST
                    """);
            var result = download(origin, "/f/index.m3u8", "movie.mp4", directory.resolve("work"), 3);
            assertArrayEquals(file, Files.readAllBytes(result.target()));
            assertEquals(3, origin.requests("/f/main.mp4"));
        }
    }

    @Test void transientServerErrorsAreRetried() throws Exception {
        try (var origin = new HlsFixture(directory)) {
            byte[] expected = origin.segments("/v", 12, "");
            origin.fail("/v/seg-5.ts", 2, 503);
            var result = download(origin, "/v/index.m3u8", "movie.ts", directory.resolve("work"), 4);
            assertArrayEquals(expected, Files.readAllBytes(result.target()));
            assertEquals(3, origin.requests("/v/seg-5.ts"));
        }
    }

    @Test void anInterruptedStreamResumesWithoutRefetchingJoinedPartsEvenWithRenewedLinks() throws Exception {
        try (var origin = new HlsFixture(directory)) {
            byte[] expected = origin.segments("/v", 40, "?token=old");
            origin.fail("/v/seg-25.ts", 1, 404);
            Path work = directory.resolve("work");
            var failure = assertThrows(DownloadException.class, () -> download(origin, "/v/index.m3u8", "movie.ts", work, 4));
            assertEquals(ErrorCode.NOT_FOUND, failure.code());
            assertFalse(Files.exists(directory.resolve("movie.ts")));
            int before = 0; for (int i = 0; i < 40; i++) before += origin.requests("/v/seg-" + i + ".ts");
            // The link was renewed: same stream, new signatures. Joined parts and finished parts are reused.
            origin.segments("/v", 40, "?token=new");
            var result = download(origin, "/v/index.m3u8", "movie.ts", work, 4);
            assertArrayEquals(expected, Files.readAllBytes(result.target()));
            for (int i = 0; i < 25; i++) assertEquals(1, origin.requests("/v/seg-" + i + ".ts"), "Part " + i + " was fetched again");
            int after = 0; for (int i = 0; i < 40; i++) after += origin.requests("/v/seg-" + i + ".ts");
            assertTrue(after - before <= 40 - 25 + 1, "Second run fetched " + (after - before) + " parts");
        }
    }

    @Test void cancelingStopsPromptlyAndALaterRunFinishesTheStream() throws Exception {
        try (var origin = new HlsFixture(directory)) {
            byte[] expected = origin.segments("/v", 30, "");
            origin.bytesPerSecond = 400_000;
            Path work = directory.resolve("work");
            var scope = new CancellationScope();
            var request = new TransferRequest(UUID.randomUUID(), URI.create(origin.origin() + "/v/index.m3u8"), directory.resolve("movie.ts"), Map.of(), options(3));
            var seen = new java.util.concurrent.atomic.AtomicInteger();
            var download = new HlsDownload(request, work, scope, (done, total, joined, bytes) -> seen.set(done));
            var running = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(download::run);
            long until = System.nanoTime() + 20_000_000_000L;
            while (seen.get() < 5 && System.nanoTime() < until) Thread.sleep(20);
            long canceledAt = System.nanoTime();
            scope.cancel();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> running.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ErrorCode.CANCELED, ((DownloadException) failure.getCause()).code());
            assertTrue(System.nanoTime() - canceledAt < 3_000_000_000L, "Cancel took too long");
            assertFalse(Files.exists(directory.resolve("movie.ts")));
            origin.bytesPerSecond = 0;
            var result = download(origin, "/v/index.m3u8", "movie.ts", work, 3);
            assertArrayEquals(expected, Files.readAllBytes(result.target()));
            assertEquals(1, origin.requests("/v/seg-0.ts"), "Joined parts are not fetched again");
        }
    }

    @Test void theEngineDownloadsAnHlsMediaFormatPausesResumesAndPublishes() throws Exception {
        try (var origin = new HlsFixture(directory); var engine = new io.ajdm.engine.AjdmEngine(directory.resolve("state/ajdm.db"))) {
            byte[] expected = origin.segments("/v", 40, "?token=a");
            origin.bytesPerSecond = 600_000;
            var mediaOrigin = new io.ajdm.model.MediaOrigin(URI.create("https://page.example/watch"), "movie", "hls-720", "mp4", "avc1", "mp4a", true);
            var request = new TransferRequest(UUID.randomUUID(), URI.create(origin.origin() + "/v/index.m3u8"), directory.resolve("movie.ts"), Map.of(), options(4));
            io.ajdm.engine.SourceRefresher noRefresh = context -> { throw new DownloadException(ErrorCode.REFRESH_FAILED, "unused"); };
            var handle = engine.startMedia(request, mediaOrigin, noRefresh);
            long until = System.nanoTime() + 20_000_000_000L;
            while (handle.progress().durableBytes() < expected.length / 4 && System.nanoTime() < until) Thread.sleep(20);
            handle.pause().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(io.ajdm.model.DownloadState.PAUSED, engine.downloads().getFirst().state());
            long durable = engine.downloads().getFirst().durableBytes();
            assertTrue(durable > 0 && durable < expected.length, "Durable " + durable);
            int firstRun = origin.requests("/v/seg-0.ts");
            origin.bytesPerSecond = 0;
            var resumed = engine.resumeMedia(request.id(), Map.of(), io.ajdm.model.ConflictPolicy.ASK, noRefresh);
            var result = resumed.completion().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertArrayEquals(expected, Files.readAllBytes(result.target()));
            assertEquals(expected.length, result.bytes());
            assertEquals(firstRun, origin.requests("/v/seg-0.ts"), "A joined part was fetched again after resume");
            assertEquals(io.ajdm.model.DownloadState.COMPLETED, engine.downloads().getFirst().state());
            try (var files = Files.list(directory)) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".hls")), "Stream work directory must be removed");
            }
        }
    }

    @Test void deniedTargetExistingAndLiveStreamsFailWithoutPublishing() throws Exception {
        try (var origin = new HlsFixture(directory)) {
            origin.segments("/v", 6, "");
            origin.fail("/v/seg-2.ts", 100, 403);
            var denied = assertThrows(DownloadException.class, () -> download(origin, "/v/index.m3u8", "denied.ts", directory.resolve("w1"), 2));
            assertEquals(ErrorCode.ACCESS_DENIED, denied.code());
            assertFalse(Files.exists(directory.resolve("denied.ts")));

            Files.writeString(directory.resolve("taken.ts"), "keep");
            var exists = assertThrows(DownloadException.class, () -> download(origin, "/v/index.m3u8", "taken.ts", directory.resolve("w2"), 2));
            assertEquals(ErrorCode.TARGET_EXISTS, exists.code());
            assertEquals("keep", Files.readString(directory.resolve("taken.ts")));

            origin.text("/live/index.m3u8", "#EXTM3U\n#EXTINF:4,\nseg-0.ts\n");
            var live = assertThrows(DownloadException.class, () -> download(origin, "/live/index.m3u8", "live.ts", directory.resolve("w3"), 2));
            assertEquals(ErrorCode.FEATURE_UNAVAILABLE, live.code());
        }
    }
}
