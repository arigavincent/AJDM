package io.ajdm.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.IpcServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
@SuppressWarnings("try") // The IPC server only needs to be open while the host talks to it.
class NativeHostTest {
    @TempDir Path temp;

    private static byte[] frames(JsonObject... messages) throws Exception {
        var out = new ByteArrayOutputStream();
        for (var message : messages) NativeHost.write(out, message);
        return out.toByteArray();
    }
    private static List<JsonObject> replies(byte[] bytes) throws Exception {
        var in = new ByteArrayInputStream(bytes); var replies = new java.util.ArrayList<JsonObject>();
        while (in.available() > 0) replies.add(NativeHost.read(in));
        return replies;
    }
    private static JsonObject message(String type, String url, boolean media) {
        var m = new JsonObject(); m.addProperty("type", type);
        if (url != null) m.addProperty("url", url);
        m.addProperty("media", media); m.addProperty("referer", "https://site.example/page"); return m;
    }

    @Test void offersFromTheBrowserReachTheCoreInboxAndBadOnesAreRefused() throws Exception {
        Path socket = temp.resolve("ipc/ajdm.sock");
        try (var core = new CoreService(temp.resolve("state/ajdm.db")); var server = new IpcServer(socket, core)) {
            var out = new ByteArrayOutputStream();
            NativeHost.serve(new ByteArrayInputStream(frames(message("ping", null, false),
                    message("offer", "https://cdn.example/debian.iso", false), message("offer", "https://www.youtube.com/watch?v=x", true),
                    message("offer", "file:///etc/passwd", false), message("shutdown", null, false))), out, socket);
            var replies = replies(out.toByteArray());
            assertEquals(5, replies.size());
            assertTrue(replies.get(0).get("ok").getAsBoolean()); assertTrue(replies.get(1).get("ok").getAsBoolean()); assertTrue(replies.get(2).get("ok").getAsBoolean());
            assertFalse(replies.get(3).get("ok").getAsBoolean(), "Only HTTP(S) links are accepted");
            assertEquals("Unknown request", replies.get(4).get("error").getAsString(), "The host never relays other commands");
            var offers = core.takeOffers();
            assertEquals(2, offers.size());
            assertEquals("https://cdn.example/debian.iso", offers.get(0).url()); assertFalse(offers.get(0).media());
            assertEquals("https://site.example/page", offers.get(0).referer());
            assertTrue(offers.get(1).media());
            assertTrue(core.takeOffers().isEmpty(), "Taking empties the inbox");
        }
    }

    private static io.ajdm.media.MediaFormat format(String id, String ext, String video, String audio, Integer height, double abr, long size, boolean fragmented) {
        return new io.ajdm.media.MediaFormat(id, ext, video, audio, height == null ? null : height * 16 / 9, height, height == null ? null : 30.0,
                1000.0, abr, size, null, false, fragmented, "", "en", new io.ajdm.model.DownloadSource(java.net.URI.create("https://example.test/" + id), java.util.Map.of()), false);
    }

    @Test void theVideoMenuPairsSoundIsServedFromOneAnalysisAndRepliesCarryTheirIds() throws Exception {
        var analyses = new java.util.concurrent.atomic.AtomicInteger();
        var extractor = new io.ajdm.media.MediaExtractor() {
            @Override public io.ajdm.media.MediaInfo extract(java.net.URI page, java.util.function.BooleanSupplier canceled) {
                analyses.incrementAndGet();
                return new io.ajdm.media.MediaInfo("v", "A video", "AJDM", 30.0, page, List.of(
                        format("137", "mp4", "avc1", "none", 1080, 0, 40_000_000, false), format("248", "webm", "vp9", "none", 1080, 0, 30_000_000, false),
                        format("22", "mp4", "avc1", "mp4a", 720, 128, 20_000_000, false), format("hls-480", "mp4", "avc1", "mp4a", 480, 128, 9_000_000, true),
                        format("140", "m4a", "none", "mp4a.40.2", null, 128, 3_000_000, false), format("251", "webm", "none", "opus", null, 160, 3_500_000, false)), 0);
            }
            @Override public void close() { }
        };
        Path socket = temp.resolve("ipc/ajdm.sock");
        try (var core = new CoreService(temp.resolve("state/ajdm.db"), extractor); var server = new IpcServer(socket, core)) {
            var ask = message("qualities", "https://www.youtube.com/watch?v=v", true); ask.addProperty("id", 7);
            var prefetch = message("prefetch", "https://www.youtube.com/watch?v=v", true);
            var out = new ByteArrayOutputStream();
            NativeHost.serve(new ByteArrayInputStream(frames(prefetch, ask, ask)), out, socket);
            var replies = replies(out.toByteArray());
            assertEquals(3, replies.size());
            var menu = replies.get(1).getAsJsonArray("qualities");
            assertEquals(7, replies.get(1).get("id").getAsInt(), "Replies name their request");
            assertEquals("A video", replies.get(1).get("title").getAsString());
            // 1080p: the MP4 video with AAC sound (not WebM); 720p combined; the fragmented 480p stream is left out; best audio last.
            assertEquals(3, menu.size(), menu::toString);
            var first = menu.get(0).getAsJsonObject();
            assertEquals("137", first.get("formatId").getAsString()); assertEquals("140", first.get("audioId").getAsString());
            assertEquals(43_000_000, first.get("sizeBytes").getAsLong()); assertEquals("MP4  1080p", first.get("label").getAsString());
            assertEquals("22", menu.get(1).getAsJsonObject().get("formatId").getAsString()); assertFalse(menu.get(1).getAsJsonObject().has("audioId"));
            assertEquals("251", menu.get(2).getAsJsonObject().get("formatId").getAsString());
            assertEquals(1, analyses.get(), "Prefetch and both menu requests share one analysis");
            // Choosing the quality and downloading uses the same analysis instead of running the extractor again.
            core.addMedia("https://www.youtube.com/watch?v=v", "137", "140", null, true, temp.resolve("files"), null, io.ajdm.model.ConflictPolicy.RENAME, 4);
            assertEquals(1, analyses.get(), "Download starts from the analysis the user just saw");
        }
    }

    @Test void aWaitingWindowGetsAnOfferedLinkImmediately() throws Exception {
        try (var core = new CoreService(temp.resolve("state/ajdm.db"))) {
            var waiter = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                long started = System.nanoTime();
                var taken = waiter.submit(() -> core.waitOffers(20_000));
                Thread.sleep(200);
                core.offer(new CoreService.Offer("https://cdn.example/movie.mkv", null, "movie.mkv", false, 734_003_200L, null, null));
                var offers = taken.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, offers.size()); assertEquals(734_003_200L, offers.getFirst().sizeBytes());
                assertTrue((System.nanoTime() - started) / 1e9 < 3, "Woken by the offer, not by the timeout");
                assertTrue(core.waitOffers(50).isEmpty(), "An empty inbox returns after the timeout");
            } finally { waiter.shutdownNow(); }
        }
    }

    @Test void withoutARunningCoreTheBrowserIsToldSoAndOversizedMessagesAreRejected() throws Exception {
        var out = new ByteArrayOutputStream();
        NativeHost.serve(new ByteArrayInputStream(frames(message("offer", "https://cdn.example/a.zip", false))), out, temp.resolve("missing.sock"));
        var reply = replies(out.toByteArray()).getFirst();
        assertFalse(reply.get("ok").getAsBoolean()); assertEquals("AJDM is not running", reply.get("error").getAsString());
        byte[] huge = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(NativeHost.MAX_MESSAGE + 1).array();
        var rejected = new ByteArrayOutputStream();
        NativeHost.serve(new ByteArrayInputStream(huge), rejected, temp.resolve("missing.sock"));
        assertEquals("Invalid message", replies(rejected.toByteArray()).getFirst().get("error").getAsString());
    }

    @Test void registrationWritesManifestsOnlyForInstalledBrowsersAndOnlyAllowsAjdm() throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home.resolve(".config/google-chrome")); Files.createDirectories(home.resolve(".mozilla"));
        Path host = temp.resolve("opt/ajdm/bin/AJDM-browser");
        var written = BrowserHosts.registerLinux(home, host);
        assertEquals(2, written.size());
        var chrome = JsonParser.parseString(Files.readString(home.resolve(".config/google-chrome/NativeMessagingHosts/io.ajdm.host.json"))).getAsJsonObject();
        assertEquals("io.ajdm.host", chrome.get("name").getAsString()); assertEquals(host.toString(), chrome.get("path").getAsString());
        assertEquals("chrome-extension://clhifbiomhnomfhpjafndoalloopnmmk/", chrome.getAsJsonArray("allowed_origins").get(0).getAsString());
        assertEquals(1, chrome.getAsJsonArray("allowed_origins").size());
        var firefox = JsonParser.parseString(Files.readString(home.resolve(".mozilla/native-messaging-hosts/io.ajdm.host.json"))).getAsJsonObject();
        assertEquals("ajdm@ariga.dev", firefox.getAsJsonArray("allowed_extensions").get(0).getAsString());
        assertFalse(Files.exists(home.resolve(".config/chromium")), "Absent browsers get nothing");
    }
}
