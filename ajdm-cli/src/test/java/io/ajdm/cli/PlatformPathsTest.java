package io.ajdm.cli;

import com.google.gson.*;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Platform-shaped fixtures qualify AJDM's paths, not availability of external sites. */
@Timeout(30)
@SuppressWarnings("try")
class PlatformPathsTest {
    @TempDir Path temp;
    private static byte[] metadata(MediaHttpsFixture fixture, String id, boolean known) {
        var row = new JsonObject(); row.addProperty("id", id); row.addProperty("title", id);
        row.addProperty("url", fixture.url(true)); row.addProperty("protocol", "https"); row.addProperty("ext", "mp4");
        if (known) { row.addProperty("vcodec", "avc1"); row.addProperty("acodec", "mp4a"); row.addProperty("height", 720); }
        row.addProperty("cookies", "sid=secret; Domain=127.0.0.1; Path=/; Secure; Expires=4102444800; unrelated=private; Domain=elsewhere.test; Path=/");
        var headers = new JsonObject(); headers.addProperty("User-Agent", "AJDM-extractor-fixture"); row.add("http_headers", headers);
        return row.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void complete(CoreService core, UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var row = core.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            if (row.state() == DownloadState.COMPLETED) return;
            assertNotEquals(DownloadState.FAILED, row.state(), row.toString()); Thread.sleep(20);
        }
        fail("Transfer did not complete");
    }
    @ParameterizedTest @ValueSource(strings = {"https://youtu.be/fixture", "https://vt.tiktok.com/fixture/", "https://www.facebook.com/share/v/fixture/", "https://www.instagram.com/reel/fixture/", "https://x.com/account/status/123"})
    void publicMediaPagesDiscoverThenDownloadWithScopedCookiesAndNoPayloadDuringAnalysis(String page) throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0);
             var core = new CoreService(temp.resolve("state/db"), (uri, canceled) -> new MediaParser().parse(metadata(fixture, "post", false), uri));
             var server = new IpcServer(temp.resolve("ipc/socket"), core)) {
            var client = new IpcClient(temp.resolve("ipc/socket")); var request = new JsonObject(); request.addProperty("url", page);
            var qualities = client.call("formats", request); assertEquals(1, qualities.getAsJsonArray("formats").size());
            assertEquals(0, fixture.oldRequests.get()); assertFalse(qualities.toString().contains("secret"));
            request.addProperty("formatId", "source"); request.addProperty("directory", temp.resolve("files").toString());
            var id = UUID.fromString(client.call("addMedia", request).get("downloadId").getAsString()); complete(core, id);
            assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files/post.mp4")));
            assertFalse(fixture.missingHeaders); var row = client.call("list", new JsonObject()).getAsJsonArray("downloads").get(0).getAsJsonObject();
            assertEquals("MEDIA", row.get("kind").getAsString()); assertEquals("post.mp4", row.get("title").getAsString()); assertFalse(row.toString().contains("secret"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"image.iso", "movie.mkv"})
    void directBrowserFileLinksUseAjdmWithExplicitCredentialsAndVerifiedBytes(String filename) throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0);
             var core = new CoreService(temp.resolve("state/db"), (uri, canceled) -> { throw new AssertionError("Direct files must not invoke metadata extraction"); });
             var server = new IpcServer(temp.resolve("ipc/socket"), core)) {
            var client = new IpcClient(temp.resolve("ipc/socket")); var request = new JsonObject();
            request.addProperty("url", fixture.url(true)); request.addProperty("directory", temp.resolve("files").toString()); request.addProperty("filename", filename);
            request.addProperty("cookies", "sid=secret"); request.addProperty("referer", "https://browser-page.test/");
            var headers = new JsonObject(); headers.addProperty("User-Agent", "AJDM-extractor-fixture"); request.add("headers", headers);
            request.addProperty("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixture.data)));
            var id = UUID.fromString(client.call("add", request).get("downloadId").getAsString()); complete(core, id);
            assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files").resolve(filename))); assertFalse(fixture.missingHeaders);
            assertEquals("FILE", core.list().getFirst().kind());
        }
    }
    @Test void sharedPageCollectionDownloadsEveryChosenChildByIdentityAndExposesGroupDetails() throws Exception {
        try (var fixture = new MediaHttpsFixture(temp, 120_017, 0)) {
            var collection = new JsonObject(); collection.addProperty("_type", "playlist"); collection.addProperty("id", "collection"); collection.addProperty("title", "Social collection");
            var entries = new JsonArray();
            for (String id : List.of("first", "second")) {
                var entry = JsonParser.parseString(new String(metadata(fixture, id, true), StandardCharsets.UTF_8)).getAsJsonObject();
                entry.addProperty("webpage_url", "https://www.instagram.com/p/collection/"); entries.add(entry);
            }
            collection.add("entries", entries); byte[] bytes = collection.toString().getBytes(StandardCharsets.UTF_8);
            var extractor = new MediaExtractor() {
                @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws java.io.IOException { return new MediaParser().parse(bytes, page); }
                @Override public MediaInfo extractEntry(URI page, String id, BooleanSupplier canceled) throws java.io.IOException { return new MediaParser().parseEntry(bytes, page, id); }
                @Override public PlaylistInfo playlist(URI page, BooleanSupplier canceled) throws java.io.IOException { return new PlaylistParser().parse(bytes, page); }
            };
            try (var core = new CoreService(temp.resolve("state/db"), extractor); var server = new IpcServer(temp.resolve("ipc/socket"), core)) {
                var client = new IpcClient(temp.resolve("ipc/socket")); var request = new JsonObject();
                request.addProperty("url", "https://www.instagram.com/p/collection/"); request.addProperty("directory", temp.resolve("files").toString());
                var id = UUID.fromString(client.call("addPlaylist", request).get("downloadId").getAsString()); complete(core, id);
                for (String leaf : List.of("0001-first.mp4", "0002-second.mp4")) assertArrayEquals(fixture.data, Files.readAllBytes(temp.resolve("files").resolve(leaf)));
                var status = new JsonObject(); status.addProperty("downloadId", id.toString()); assertEquals(2, client.call("playlistStatus", status).get("completed").getAsInt());
                var group = core.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); assertEquals("PLAYLIST", group.kind()); assertEquals("Social collection", group.title());
                assertFalse(fixture.missingHeaders);
            }
        }
    }
}
