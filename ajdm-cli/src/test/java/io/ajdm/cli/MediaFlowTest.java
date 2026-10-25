package io.ajdm.cli;

import com.google.gson.*;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@SuppressWarnings("try") // Server lifetime is exercised through IPC clients.
class MediaFlowTest {
    private PlatformMediaExtractor resolverExtractor(MediaHttpsFixture fixture) {
        MediaExtractor primary=(page,canceled)->{throw new DownloadException(ErrorCode.EXTRACTION_FAILED,"Primary unavailable");};
        MediaExtractor resolver=(page,canceled)->new MediaInfo("1234567890","Resolver fixture","",null,page,List.of(
                new MediaFormat("resolver-tiktok-standard","mp4","unknown","unknown",null,null,null,null,null,null,null,false,false,"Standard","",
                        new DownloadSource(java.net.URI.create(fixture.url(!fixture.expired)),Map.of("Cookie","sid=secret","User-Agent","AJDM-extractor-fixture")))),0);
        return new PlatformMediaExtractor(primary,resolver);
    }
    @Test void resolverChoiceRenewsAfterCoreReopenAndPreservesDurableRangesAndExactBytes() throws Exception {
        try(var fixture=new MediaHttpsFixture(temp,4*1024*1024+17,512*1024)) {
            String id;long saved;
            try(var core=new CoreService(database(),resolverExtractor(fixture));var server=new IpcServer(socket(),core)) {
                var client=new IpcClient(socket());var request=payload("url","https://www.tiktok.com/@creator/video/1234567890");
                assertEquals("resolver-tiktok-standard",client.call("formats",request).getAsJsonArray("formats").get(0).getAsJsonObject().get("formatId").getAsString());
                assertEquals(0,fixture.oldRequests.get());request.addProperty("formatId","resolver-tiktok-standard");request.addProperty("connections",1);request.addProperty("directory",temp.resolve("files").toString());
                id=client.call("addMedia",request).get("downloadId").getAsString();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(row(client,id).get("writtenBytes").getAsLong()<256*1024 && System.nanoTime()<deadline)Thread.sleep(10);
                client.call("pause",payload("downloadId",id));saved=row(client,id).get("durableBytes").getAsLong();assertTrue(saved>0);
            }
            fixture.expired=true;
            try(var core=new CoreService(database(),resolverExtractor(fixture));var server=new IpcServer(socket(),core)) {
                var client=new IpcClient(socket());assertEquals("PAUSED",row(client,id).get("state").getAsString());client.call("resume",payload("downloadId",id));complete(client,id);
                assertArrayEquals(fixture.data,Files.readAllBytes(temp.resolve("files/Resolver fixture.mp4")));assertTrue(fixture.newRequests.get()>0);
                assertTrue(fixture.starts.stream().anyMatch(start->start>=saved));assertFalse(fixture.missingHeaders);assertFalse(fixture.expiredProbeHadCookie);
            }
        }
    }
    @Test void anHlsMediaFormatDownloadsThroughTheCorePartByPartAndPublishesAsTransportStream() throws Exception {
        var streams = new HashMap<String, byte[]>(); var joined = new ByteArrayOutputStream(); var random = new Random(9);
        var playlist = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n");
        for (int i = 0; i < 24; i++) {
            byte[] part = new byte[30_000 + random.nextInt(20_000)]; random.nextBytes(part);
            streams.put("/v/seg-" + i + ".ts", part); joined.writeBytes(part);
            playlist.append("#EXTINF:4,\nseg-").append(i).append(".ts?token=private\n");
        }
        playlist.append("#EXT-X-ENDLIST\n");
        streams.put("/v/index.m3u8", playlist.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        streams.put("/old", new byte[]{1});
        try (var fixture = new MediaHttpsFixture(temp, streams, 0)) {
            String origin = fixture.url(false).replaceFirst("/new\\?.*$", "");
            MediaExtractor extractor = (page, canceled) -> new MediaInfo("movie", "HLS fixture", "", 96.0, page, List.of(
                    new MediaFormat("hls-720", "ts", "avc1", "mp4a", 1280, 720, 30.0, null, null, null, null, false, false, "720p", "",
                            new DownloadSource(java.net.URI.create(origin + "/v/index.m3u8"), Map.of("User-Agent", "AJDM-extractor-fixture")), true)), 0);
            try (var core = new CoreService(database(), extractor); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var request = payload("url", "https://video.example/watch?v=movie");
                request.addProperty("formatId", "hls-720"); request.addProperty("connections", 4); request.addProperty("directory", temp.resolve("files").toString());
                String id = client.call("addMedia", request).get("downloadId").getAsString();
                complete(client, id);
                assertArrayEquals(joined.toByteArray(), Files.readAllBytes(temp.resolve("files/HLS fixture.ts")));
            }
        }
    }
    @Test void splitHlsVideoAndAudioDownloadThroughTheCoreAndMuxIntoMp4() throws Exception {
        Path ffmpeg = FakeTool.ffmpeg();
        Assumptions.assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg is not installed");
        Path video = temp.resolve("src-video.ts"), audio = temp.resolve("src-audio.ts");
        for (String[] make : new String[][]{
                {"-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mpegts", video.toString()},
                {"-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "aac", "-f", "mpegts", audio.toString()}}) {
            var command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y")); command.addAll(List.of(make));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "ffmpeg lacks the test encoders");
        }
        var streams = new HashMap<String, byte[]>(); streams.put("/old", new byte[]{1});
        for (var source : Map.of("v", video, "a", audio).entrySet()) {
            byte[] bytes = Files.readAllBytes(source.getValue()); int packets = bytes.length / 188, parts = 4;
            var playlist = new StringBuilder("#EXTM3U\n");
            for (int i = 0; i < parts; i++) {
                int from = packets * i / parts * 188, to = i == parts - 1 ? bytes.length : packets * (i + 1) / parts * 188;
                streams.put("/" + source.getKey() + "/p" + i + ".ts", Arrays.copyOfRange(bytes, from, to));
                playlist.append("#EXTINF:0.75,\np").append(i).append(".ts\n");
            }
            streams.put("/" + source.getKey() + "/index.m3u8", playlist.append("#EXT-X-ENDLIST\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var fixture = new MediaHttpsFixture(temp, streams, 0)) {
            String origin = fixture.url(false).replaceFirst("/new\\?.*$", "");
            MediaExtractor extractor = (page, canceled) -> new MediaInfo("movie", "Split HLS", "", 3.0, page, List.of(
                    new MediaFormat("hls-v", "ts", "avc1.42c00d", "none", 160, 120, 10.0, null, null, null, null, false, false, "", "",
                            new DownloadSource(java.net.URI.create(origin + "/v/index.m3u8"), Map.of()), true),
                    new MediaFormat("hls-a", "ts", "none", "mp4a.40.2", null, null, null, null, 128.0, null, null, false, false, "", "",
                            new DownloadSource(java.net.URI.create(origin + "/a/index.m3u8"), Map.of()), true)), 0);
            try (var core = new CoreService(database(), extractor, new FfmpegMuxer(ffmpeg)); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var request = payload("url", "https://video.example/watch?v=movie");
                request.addProperty("formatId", "hls-v"); request.addProperty("audioId", "hls-a"); request.addProperty("connections", 2);
                request.addProperty("directory", temp.resolve("files").toString());
                String id = client.call("addMedia", request).get("downloadId").getAsString();
                complete(client, id);
                byte[] head = Arrays.copyOf(Files.readAllBytes(temp.resolve("files/Split HLS.mp4")), 12);
                assertEquals("ftyp", new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
                assertOwnerOnlyWherePosix(temp.resolve("files/Split HLS.mp4"));
            }
        }
    }
    @Test void aSingleHlsStreamIsRemuxedToMp4BesideAnExistingFileAndTheListFollowsIt() throws Exception {
        Path ffmpeg = FakeTool.ffmpeg();
        Assumptions.assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg is not installed");
        Path source = temp.resolve("src.ts");
        var make = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-f", "mpegts", source.toString())
                .redirectErrorStream(true).start();
        Assumptions.assumeTrue(make.waitFor(60, TimeUnit.SECONDS) && make.exitValue() == 0, "ffmpeg lacks the test encoders");
        byte[] bytes = Files.readAllBytes(source); int packets = bytes.length / 188;
        var streams = new HashMap<String, byte[]>(); streams.put("/old", new byte[]{1});
        var playlist = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 3; i++) {
            int from = packets * i / 3 * 188, to = i == 2 ? bytes.length : packets * (i + 1) / 3 * 188;
            streams.put("/s/p" + i + ".ts", Arrays.copyOfRange(bytes, from, to)); playlist.append("#EXTINF:1,\np").append(i).append(".ts\n");
        }
        streams.put("/s/index.m3u8", playlist.append("#EXT-X-ENDLIST\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(temp.resolve("files")); Files.writeString(temp.resolve("files/Single HLS.mp4"), "keep me");
        try (var fixture = new MediaHttpsFixture(temp, streams, 0)) {
            String origin = fixture.url(false).replaceFirst("/new\\?.*$", "");
            MediaExtractor extractor = (page, canceled) -> new MediaInfo("movie", "Single HLS", "", 3.0, page, List.of(
                    new MediaFormat("hls-av", "ts", "avc1.42c00d", "mp4a.40.2", 160, 120, 10.0, null, null, null, null, false, false, "", "",
                            new DownloadSource(java.net.URI.create(origin + "/s/index.m3u8"), Map.of()), true)), 0);
            try (var core = new CoreService(database(), extractor, new FfmpegMuxer(ffmpeg)); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var request = payload("url", "https://video.example/watch?v=movie");
                request.addProperty("formatId", "hls-av"); request.addProperty("connections", 2); request.addProperty("directory", temp.resolve("files").toString());
                String id = client.call("addMedia", request).get("downloadId").getAsString();
                complete(client, id);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!row(client, id).get("target").getAsString().endsWith(".mp4") && System.nanoTime() < deadline) Thread.sleep(50);
                Path remuxed = Path.of(row(client, id).get("target").getAsString());
                assertEquals(temp.resolve("files"), remuxed.getParent());
                assertNotEquals("Single HLS.mp4", remuxed.getFileName().toString());
                assertEquals("keep me", Files.readString(temp.resolve("files/Single HLS.mp4")));
                assertEquals("ftyp", new String(Arrays.copyOf(Files.readAllBytes(remuxed), 12), 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
                assertFalse(Files.exists(temp.resolve("files/Single HLS.ts")), "The transport stream is removed after a successful remux");
                assertOwnerOnlyWherePosix(remuxed);
                try (var leftovers = Files.list(temp.resolve("files"))) {
                    assertTrue(leftovers.noneMatch(p -> p.getFileName().toString().startsWith(".ajdm-remux-")));
                }
            }
        }
    }
    @Test void aDashRepresentationDownloadsThroughTheCoreAndPublishesInItsOwnContainer() throws Exception {
        var streams = new HashMap<String, byte[]>(); streams.put("/old", new byte[]{1});
        var joined = new ByteArrayOutputStream(); var random = new Random(21);
        var fragments = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            byte[] part = new byte[i == 0 ? 900 : 20_000 + random.nextInt(20_000)]; random.nextBytes(part);
            String name = i == 0 ? "init.mp4" : i + ".m4s";
            streams.put("/dash/v/" + name, part); joined.writeBytes(part);
            fragments.append(i == 0 ? "" : ",").append("{\"path\":\"v/").append(name).append("\"").append(i == 0 ? "" : ",\"duration\":2.0").append('}');
        }
        try (var fixture = new MediaHttpsFixture(temp, streams, 0)) {
            String origin = fixture.url(false).replaceFirst("/new\\?.*$", "");
            String json = "{\"id\":\"movie\",\"title\":\"DASH fixture\",\"formats\":[{\"format_id\":\"dash-720\",\"protocol\":\"http_dash_segments\","
                    + "\"url\":\"" + origin + "/dash/manifest.mpd\",\"ext\":\"mp4\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"height\":720,"
                    + "\"fragment_base_url\":\"" + origin + "/dash/\",\"fragments\":[" + fragments + "]}]}";
            MediaExtractor extractor = (page, canceled) -> new MediaParser().parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), page);
            try (var core = new CoreService(database(), extractor); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var request = payload("url", "https://video.example/watch?v=movie");
                request.addProperty("formatId", "dash-720"); request.addProperty("connections", 4); request.addProperty("directory", temp.resolve("files").toString());
                String id = client.call("addMedia", request).get("downloadId").getAsString();
                complete(client, id);
                assertArrayEquals(joined.toByteArray(), Files.readAllBytes(temp.resolve("files/DASH fixture.mp4")));
            }
        }
    }
    @Test void anInterruptedRemuxIsFinishedWhenTheCoreStartsAgain() throws Exception {
        Path ffmpeg = FakeTool.ffmpeg();
        Assumptions.assumeTrue(Files.isExecutable(ffmpeg), "ffmpeg is not installed");
        Path source = temp.resolve("src.ts");
        var make = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mpegts", source.toString()).redirectErrorStream(true).start();
        Assumptions.assumeTrue(make.waitFor(60, TimeUnit.SECONDS) && make.exitValue() == 0, "ffmpeg lacks the test encoders");
        var streams = new HashMap<String, byte[]>(); streams.put("/old", new byte[]{1});
        streams.put("/s/p0.ts", Files.readAllBytes(source));
        streams.put("/s/index.m3u8", "#EXTM3U\n#EXTINF:2,\np0.ts\n#EXT-X-ENDLIST\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var fixture = new MediaHttpsFixture(temp, streams, 0)) {
            String origin = fixture.url(false).replaceFirst("/new\\?.*$", "");
            MediaExtractor extractor = (page, canceled) -> new MediaInfo("movie", "Recovered", "", 2.0, page, List.of(
                    new MediaFormat("hls-v", "ts", "avc1.42c00d", "none", 160, 120, 10.0, null, null, null, null, false, false, "", "",
                            new DownloadSource(java.net.URI.create(origin + "/s/index.m3u8"), Map.of()), true)), 0);
            MediaMuxer broken = new MediaMuxer() {
                @Override public void mux(Path v, String ve, Path a, String ae, Path o, String c, java.util.function.BooleanSupplier x) { throw new AssertionError(); }
                @Override public void remux(Path in, String ext, Path out, String container, java.util.function.BooleanSupplier x) throws IOException {
                    Files.writeString(out, "partial"); throw new DownloadException(ErrorCode.MUX_FAILED, "Simulated crash");
                }
            };
            String id;
            try (var core = new CoreService(database(), extractor, broken); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var request = payload("url", "https://video.example/watch?v=movie");
                request.addProperty("formatId", "hls-v"); request.addProperty("connections", 1); request.addProperty("directory", temp.resolve("files").toString());
                id = client.call("addMedia", request).get("downloadId").getAsString();
                complete(client, id); Thread.sleep(500);
                assertTrue(row(client, id).get("target").getAsString().endsWith("Recovered.ts"));
            }
            assertTrue(Files.exists(temp.resolve("files/Recovered.ts")));
            try (var core = new CoreService(database(), extractor, new FfmpegMuxer(ffmpeg)); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!row(client, id).get("target").getAsString().endsWith(".mp4") && System.nanoTime() < deadline) Thread.sleep(50);
                assertEquals(temp.resolve("files/Recovered.mp4").toString(), row(client, id).get("target").getAsString());
                assertFalse(Files.exists(temp.resolve("files/Recovered.ts")));
                try (var leftovers = Files.list(temp.resolve("files"))) {
                    assertTrue(leftovers.noneMatch(p -> p.getFileName().toString().startsWith(".ajdm-remux-")));
                }
            }
        }
    }
    @TempDir Path temp;
    private Path socket() { return temp.resolve("ipc/ajdm.sock"); }
    /** Mirrors MediaJobs: published media is narrowed to rw------- on POSIX stores; on ACL stores it inherits the destination folder's ACL. */
    private static void assertOwnerOnlyWherePosix(Path file) throws Exception {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }
    private Path database() { return temp.resolve("state/ajdm.db"); }
    private Path tool(String directUrl) throws Exception {
        Path script = FakeTool.python(temp.resolve("fake-yt-dlp"), """
            import pathlib, sys
            root = pathlib.Path(__file__).parent
            with (root / 'extractions').open('a') as log:
                log.write('extract\\n')
            (root / 'arguments').write_text('\\n'.join(sys.argv[1:]))
            sys.stdout.write((root / 'metadata.json').read_text())
            """);
        if (!FakeTool.windows()) Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        metadata(directUrl, "video"); return script;
    }
    private void metadata(String url, String identity) throws Exception {
        var root = new JsonObject(); root.addProperty("id", identity); root.addProperty("title", "Fixture"); var headers = new JsonObject();
        headers.addProperty("Cookie", "sid=secret"); headers.addProperty("User-Agent", "AJDM-extractor-fixture"); root.add("http_headers", headers);
        var formats = new JsonArray();
        for (var entry : List.of(new String[]{"18", "1080", "mp4a"}, new String[]{"4k", "2160", "none"}, new String[]{"8k", "4320", "none"}, new String[]{"audio", "0", "opus"})) {
            var f = new JsonObject(); f.addProperty("format_id", entry[0]); f.addProperty("ext", entry[0].equals("audio") ? "webm" : "mp4");
            f.addProperty("protocol", "https"); f.addProperty("url", url); f.addProperty("vcodec", entry[0].equals("audio") ? "none" : "avc1");
            f.addProperty("acodec", entry[2]); f.addProperty("height", Integer.parseInt(entry[1])); f.addProperty("abr", 160); f.addProperty("filesize_approx", 12345); formats.add(f);
        }
        root.add("formats", formats); Files.writeString(temp.resolve("metadata.json"), root.toString());
    }
    private JsonObject payload(String key, String value) { var p = new JsonObject(); p.addProperty(key, value); return p; }
    private JsonObject row(IpcClient client, String id) throws Exception {
        for (var entry : client.call("list", new JsonObject()).getAsJsonArray("downloads")) if (id.equals(entry.getAsJsonObject().get("id").getAsString())) return entry.getAsJsonObject();
        throw new AssertionError("Missing row");
    }
    private void complete(IpcClient client, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            var state = row(client, id).get("state").getAsString(); if (state.equals("COMPLETED")) return;
            assertFalse(Set.of("FAILED", "CANCELED").contains(state), row(client, id).toString()); Thread.sleep(20);
        }
        fail("Media did not complete: " + row(client, id));
    }
    @Test void formatsTableAndIndividualSelectionUseAjdmWithSafeIpcOutput() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 320_017, 0);
             var core = new CoreService(database(), new YtDlpExtractor(tool(fixture.url(true)))); var server = new IpcServer(socket(), core)) {
            var client = new IpcClient(socket()); var p = payload("url", "https://page.test/watch?token=private");
            var formats = client.call("formats", p); assertEquals(3, formats.getAsJsonArray("formats").size()); assertEquals(1, formats.get("hiddenCount").getAsInt());
            assertFalse(formats.toString().contains("secret")); assertFalse(formats.toString().contains("private")); assertEquals(0, fixture.oldRequests.get());
            p.addProperty("showAll", true); p.addProperty("preset", "BEST"); var preview = client.call("formats", p);
            assertEquals(4, preview.getAsJsonArray("formats").size()); assertEquals("4k", preview.getAsJsonObject("selection").getAsJsonArray("formatIds").get(0).getAsString());
            var invalidPair = payload("url", "https://page.test/watch"); invalidPair.addProperty("formatId", "18"); invalidPair.addProperty("audioId", "audio");
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class, () -> client.call("addMedia", invalidPair)).code());
            assertEquals(0, core.list().size());
            var out = new StringWriter(); var err = new StringWriter();
            assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "formats", "https://page.test/watch"}, new PrintWriter(out), new PrintWriter(err)));
            assertTrue(out.toString().contains("VIDEO_AUDIO")); assertTrue(out.toString().contains("~12345")); assertFalse(out.toString().contains("secret"));
            assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "add-media", "https://page.test/watch", "--format", "18",
                    "--directory", temp.resolve("files").toString(), "--wait"}, new PrintWriter(out), new PrintWriter(err)), err.toString());
            assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files/Fixture.mp4"))); assertFalse(fixture.missingHeaders);
            assertTrue(Files.readString(temp.resolve("arguments")).contains("--skip-download"));
        }
    }
    @Test void expiredSourceReextractsChosenFormatAfterCoreReopenPreservingDurableRanges() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 4 * 1024 * 1024 + 17, 512 * 1024)) {
            Path tool = tool(fixture.url(true)); String id; long saved;
            try (var core = new CoreService(database(), new YtDlpExtractor(tool)); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var p = payload("url", "https://page.test/watch"); p.addProperty("formatId", "18"); p.addProperty("connections", 1); p.addProperty("directory", temp.resolve("files").toString());
                id = client.call("addMedia", p).get("downloadId").getAsString();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (row(client, id).get("writtenBytes").getAsLong() < 256 * 1024 && System.nanoTime() < deadline) Thread.sleep(10);
                client.call("pause", payload("downloadId", id)); saved = row(client, id).get("durableBytes").getAsLong(); assertTrue(saved > 0);
            }
            fixture.expired = true; metadata(fixture.url(false), "video"); int oldCalls = fixture.oldRequests.get();
            try (var core = new CoreService(database(), new YtDlpExtractor(tool)); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); assertEquals("PAUSED", row(client, id).get("state").getAsString()); assertEquals(oldCalls, fixture.oldRequests.get());
                client.call("resume", payload("downloadId", id)); complete(client, id);
                assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files/Fixture.mp4"))); assertTrue(fixture.newRequests.get() > 0);
                assertTrue(fixture.starts.stream().anyMatch(start -> start >= saved)); assertFalse(fixture.missingHeaders);
                assertFalse(fixture.expiredProbeHadCookie);
                assertEquals(2, Files.readAllLines(temp.resolve("extractions")).size());
            }
        }
    }
    @Test void forcedMidTransferExpiryRefreshesWithoutASecondDownloadOrBytesMismatch() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 8 * 1024 * 1024 + 17, 2 * 1024 * 1024)) {
            Path tool = tool(fixture.url(true));
            try (var core = new CoreService(database(), new YtDlpExtractor(tool)); var server = new IpcServer(socket(), core)) {
                var client = new IpcClient(socket()); var p = payload("url", "https://page.test/watch"); p.addProperty("formatId", "18"); p.addProperty("connections", 1); p.addProperty("directory", temp.resolve("files").toString());
                String id = client.call("addMedia", p).get("downloadId").getAsString(); metadata(fixture.url(false), "video"); fixture.expireAfterWindow = true;
                complete(client, id); assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files/Fixture.mp4")));
                assertEquals(1, core.list().size()); assertTrue(fixture.newRequests.get() > 0); assertEquals(2, Files.readAllLines(temp.resolve("extractions")).size());
                assertFalse(fixture.missingHeaders);
            }
        }
    }
    @Test void productionDaemonToolOverrideAndMediaOriginSurviveAForcedKill() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 8 * 1024 * 1024 + 17, 1024 * 1024)) {
            Path tool = tool(fixture.url(true)); Process first = startDaemon(tool, fixture.keystore); String id; long saved;
            try {
                var out = new StringWriter(); var err = new StringWriter();
                assertEquals(0, AjdmCli.execute(new String[]{"--socket", socket().toString(), "add-media", "https://page.test/watch", "--format", "18",
                        "--connections", "1", "--directory", temp.resolve("files").toString()}, new PrintWriter(out), new PrintWriter(err)), err.toString());
                id = out.toString().lines().findFirst().orElseThrow(); var client = new IpcClient(socket());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                while (row(client, id).get("durableBytes").getAsLong() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
                saved = row(client, id).get("durableBytes").getAsLong(); assertTrue(saved > 0);
                assertNotEquals("COMPLETED", row(client, id).get("state").getAsString());
                first.destroyForcibly(); assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            } finally { if (first.isAlive()) first.destroyForcibly().waitFor(); }
            fixture.expired = true; metadata(fixture.url(false), "video"); Process second = startDaemon(tool, fixture.keystore);
            try {
                var client = new IpcClient(socket()); assertEquals("PAUSED", row(client, id).get("state").getAsString());
                assertTrue(row(client, id).get("durableBytes").getAsLong() >= saved);
                client.call("resume", payload("downloadId", id)); complete(client, id);
                assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files/Fixture.mp4"))); assertTrue(fixture.newRequests.get() > 0);
                assertEquals(2, Files.readAllLines(temp.resolve("extractions")).size()); assertFalse(fixture.expiredProbeHadCookie); assertFalse(fixture.missingHeaders);
            } finally { second.destroy(); if (!second.waitFor(10, TimeUnit.SECONDS)) { second.destroyForcibly().waitFor(); fail("Media daemon did not stop"); } }
        }
    }
    private Process startDaemon(Path tool, Path trustStore) throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "--enable-native-access=ALL-UNNAMED",
                "-Djavax.net.ssl.trustStore=" + trustStore, "-Djavax.net.ssl.trustStorePassword=test-only-password", "-cp", System.getProperty("ajdm.test.classpath"),
                AjdmCli.class.getName(), "--socket", socket().toString(), "daemon", "--state", database().toString(), "--yt-dlp", tool.toString());
        Process process = new ProcessBuilder(command).redirectOutput(temp.resolve("daemon.out").toFile()).redirectError(temp.resolve("daemon.err").toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try { new IpcClient(socket()).call("ping", new JsonObject()); return process; }
            catch (IOException e) { Thread.sleep(20); }
        }
        process.destroyForcibly().waitFor(); fail("Media daemon failed to start"); return process;
    }
}
