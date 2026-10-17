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
