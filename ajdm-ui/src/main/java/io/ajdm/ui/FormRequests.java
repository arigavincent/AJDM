package io.ajdm.ui;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;

final class FormRequests {
    private FormRequests() { }
    static JsonObject base(String url, String folder, int connections) {
        URI uri = URI.create(url.strip());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Enter an HTTP or HTTPS URL without embedded credentials.");
        if (folder.isBlank()) throw new IllegalArgumentException("Choose a download folder.");
        if (connections < 1 || connections > 32) throw new IllegalArgumentException("Connections must be between 1 and 32.");
        var payload = new JsonObject(); payload.addProperty("url", uri.toString());
        payload.addProperty("directory", Path.of(folder.strip()).toAbsolutePath().normalize().toString());
        payload.addProperty("connections", connections); payload.addProperty("conflict", "RENAME"); return payload;
    }
    static void filename(JsonObject payload, String filename) {
        if (filename.isBlank()) return;
        if (filename.equals(".") || filename.equals("..") || filename.contains("/") || filename.contains("\\") || filename.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a filename without folders or control characters.");
        payload.addProperty("filename", filename.strip());
    }
    static void checksum(JsonObject payload, String checksum) {
        if (checksum.isBlank()) return;
        if (!checksum.strip().matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("SHA-256 must contain 64 hexadecimal characters.");
        payload.addProperty("sha256", checksum.strip());
    }
    static void selection(JsonObject payload, String preset, String format, String audio) {
        if (!preset.equals("Custom")) { payload.addProperty("preset", preset); return; }
        if (format.isBlank()) throw new IllegalArgumentException("Select a video or audio format from the table.");
        payload.addProperty("formatId", format.strip());
        if (!audio.isBlank()) payload.addProperty("audioId", audio.strip());
    }
    static void requestDetails(JsonObject payload, String referer, String userAgent, String cookies, String authorization) {
        var values = new java.util.LinkedHashMap<String, String>();
        if (!referer.isBlank()) {
            URI page = URI.create(referer.strip()); new io.ajdm.model.DownloadSource(page, java.util.Map.of());
            values.put("Referer", page.toString());
        }
        if (!userAgent.isBlank()) values.put("User-Agent", userAgent.strip());
        if (!cookies.isBlank()) values.put("Cookie", cookies.strip());
        if (!authorization.isBlank()) values.put("Authorization", authorization.strip());
        new io.ajdm.model.DownloadSource(URI.create("https://validation.invalid/"), values);
        if (!values.isEmpty()) {
            var headers = new JsonObject(); values.forEach(headers::addProperty); payload.add("headers", headers);
        }
    }
}
