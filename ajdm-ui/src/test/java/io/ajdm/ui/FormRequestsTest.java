package io.ajdm.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormRequestsTest {
    @Test void browserRequestDetailsValidateSecretsWithoutInjectingEngineHeaders() {
        var payload = new com.google.gson.JsonObject();
        FormRequests.requestDetails(payload, "https://site.test/movie", "Browser", "sid=private", "Bearer private");
        assertEquals("sid=private", payload.getAsJsonObject("headers").get("Cookie").getAsString());
        assertEquals("https://site.test/movie", payload.getAsJsonObject("headers").get("Referer").getAsString());
        assertThrows(IllegalArgumentException.class, () -> FormRequests.requestDetails(new com.google.gson.JsonObject(), "https://user:secret@site.test/", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> FormRequests.requestDetails(new com.google.gson.JsonObject(), "", "", "sid=x\r\nRange: bytes=0-1", ""));
        var empty = new com.google.gson.JsonObject(); FormRequests.requestDetails(empty, "", "", "", ""); assertFalse(empty.has("headers"));
    }
    @Test void rejectsEmbeddedCredentialsInvalidSchemesAndBadWorkers() {
        for (String source : new String[]{"file:///etc/passwd", "https://user:password@example.test/file", "not a url", "https:///missing", "https://example.test/file#fragment"})
            assertThrows(IllegalArgumentException.class, () -> FormRequests.base(source, "/tmp/files", 4));
        assertThrows(IllegalArgumentException.class, () -> FormRequests.base("https://example.test/file", "/tmp/files", 33));
        assertThrows(IllegalArgumentException.class, () -> FormRequests.base("https://example.test/file", " ", 4));
    }
    @Test void directRequestsUseRenameAndValidateFilenameAndChecksum() {
        var payload = FormRequests.base("https://example.test/file?q=secret", "/tmp/files", 8);
        assertEquals(8, payload.get("connections").getAsInt()); assertEquals("RENAME", payload.get("conflict").getAsString());
        assertFalse(payload.has("adaptive")); assertFalse(payload.has("headers"));
        for (String name : new String[]{"../oops", "..", "C:\\oops", "oops\n"})
            assertThrows(IllegalArgumentException.class, () -> FormRequests.filename(payload, name));
        assertThrows(IllegalArgumentException.class, () -> FormRequests.checksum(payload, "abc"));
        FormRequests.checksum(payload, "a".repeat(64)); assertEquals("a".repeat(64), payload.get("sha256").getAsString());
    }
    @Test void presetsAndCustomFormatsNeverProduceConflictingSelectors() {
        var preset = new com.google.gson.JsonObject(); FormRequests.selection(preset, "P1080", "699", "251");
        assertEquals("P1080", preset.get("preset").getAsString()); assertFalse(preset.has("formatId"));
        var custom = new com.google.gson.JsonObject(); FormRequests.selection(custom, "Custom", "699", "251");
        assertFalse(custom.has("preset")); assertEquals("699", custom.get("formatId").getAsString()); assertEquals("251", custom.get("audioId").getAsString());
        assertThrows(IllegalArgumentException.class, () -> FormRequests.selection(custom, "Custom", "", "251"));
    }
    @Test void publicFailuresNeverEchoPrivateDetails() {
        assertFalse(DesktopGateway.failure(new IllegalArgumentException("https://private.example/?token=secret")).contains("secret"));
        assertTrue(DesktopGateway.failure(new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.IPC_UNAVAILABLE, "private")).contains("5001"));
    }
}
