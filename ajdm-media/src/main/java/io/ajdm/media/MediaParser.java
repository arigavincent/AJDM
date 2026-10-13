package io.ajdm.media;

import com.google.gson.*;
import com.google.gson.stream.*;
import io.ajdm.model.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Bounded strict extraction output, with unknown fields ignored during model mapping. */
public final class MediaParser {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public MediaInfo parseEntry(byte[] bytes, URI page, String mediaId) throws DownloadException {
        try {
            var root = json(bytes);
            if (bool(root, "has_drm")) throw new DownloadException(ErrorCode.DRM_PROTECTED, "Protected collection");
            if (!Set.of("playlist", "multi_video").contains(string(root, "_type", "video"))) return parse(bytes, page);
            var values = root.get("entries");
            if (values == null || !values.isJsonArray() || values.getAsJsonArray().size() > 1000) throw new IOException("Invalid collection");
            var entries = values.getAsJsonArray();
            for (var value : entries) {
                if (!value.isJsonObject()) continue;
                var entry = value.getAsJsonObject();
                if (!Objects.equals(mediaId, string(entry, "id", null))) continue;
                var selected = entry.deepCopy();
                if (!selected.has("http_headers") && root.has("http_headers")) selected.add("http_headers", root.get("http_headers"));
                if (!selected.has("cookies") && root.has("cookies")) selected.add("cookies", root.get("cookies"));
                return parse(selected.toString().getBytes(StandardCharsets.UTF_8), page);
            }
            throw new DownloadException(ErrorCode.REFRESH_FAILED, "Collection entry disappeared");
        } catch (DownloadException e) { throw e; }
        catch (IOException | IllegalArgumentException | IllegalStateException e) { throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Invalid collection metadata"); }
    }
    public MediaInfo parse(byte[] bytes, URI page) throws DownloadException {
        try {
            JsonObject root = json(bytes);
            if (bool(root, "has_drm")) throw new DownloadException(ErrorCode.DRM_PROTECTED, "Protected media");
            if (Set.of("playlist", "multi_video").contains(string(root, "_type", "video"))) {
                throw new DownloadException(ErrorCode.PLAYLIST_REQUIRED, "Collection requires selection");
            }
            String id = string(root, "id", null); if (id == null || id.isBlank()) throw new IOException("Missing media identity");
            JsonElement entries = root.get("formats");
            if (entries == null || entries.isJsonNull()) {
                if (string(root, "url", null) == null) throw new IOException("Missing format list");
                var single = root.deepCopy();
                if (string(single, "format_id", null) == null) single.addProperty("format_id", "source");
                if (string(single, "protocol", null) == null) single.addProperty("protocol", URI.create(string(single, "url", null)).getScheme());
                var list = new JsonArray(); list.add(single); entries = list;
            }
            if (!entries.isJsonArray()) throw new IOException("Invalid format list");
            if (entries.getAsJsonArray().size() > 4096) throw new IOException("Format count limit");
            var formats = new ArrayList<MediaFormat>(); var ids = new HashSet<String>(); int drm = 0, excluded = 0;
            Map<String, String> topHeaders = headers(root);
            for (JsonElement entry : entries.getAsJsonArray()) {
                if (!entry.isJsonObject()) throw new IOException("Invalid format"); JsonObject f = entry.getAsJsonObject();
                if (bool(f, "has_drm")) { drm++; excluded++; continue; }
                if (!"https".equals(string(f, "protocol", ""))) { excluded++; continue; }
                String formatId = string(f, "format_id", null); if (formatId == null || !ids.add(formatId)) throw new IOException("Invalid format identity");
                String url = string(f, "url", null); if (url == null) throw new IOException("Missing direct URL");
                var merged = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER); merged.putAll(topHeaders); merged.putAll(headers(f));
                String cookie = ExtractedCookies.header(string(f, "cookies", string(root, "cookies", null)), URI.create(url));
                if (!cookie.isEmpty()) merged.put("Cookie", cookie);
                String range = string(f, "dynamic_range", "SDR");
                JsonElement fragments = f.get("fragments");
                if (fragments != null && !fragments.isJsonNull() && !fragments.isJsonArray()) throw new IOException("Invalid fragment list");
                var format = new MediaFormat(formatId, string(f, "ext", null), string(f, "vcodec", "unknown"), string(f, "acodec", "unknown"),
                        integer(f, "width"), integer(f, "height"), number(f, "fps"), number(f, "tbr"), number(f, "abr"),
                        size(f, "filesize"), size(f, "filesize_approx"), !Set.of("SDR", "unknown").contains(range),
                        fragments != null && fragments.isJsonArray() && !fragments.getAsJsonArray().isEmpty(),
                        string(f, "format_note", ""), string(f, "language", ""), new DownloadSource(URI.create(url), merged));
                formats.add(format);
            }
            if (formats.isEmpty()) throw new DownloadException(drm > 0 ? ErrorCode.DRM_PROTECTED : ErrorCode.EXTRACTION_FAILED, "No usable media formats");
            return new MediaInfo(id, string(root, "title", id), string(root, "uploader", ""), number(root, "duration"), page, formats, excluded);
        } catch (DownloadException e) { throw e; }
        catch (IOException | IllegalArgumentException | IllegalStateException | ArithmeticException e) {
            throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Invalid extraction output");
        }
    }
    static JsonObject json(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid extraction output size");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JsonObject root;
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT); JsonElement tree = read(reader, 0);
            if (!tree.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid extraction JSON");
            root = tree.getAsJsonObject();
        }
        return root;
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("Nesting limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); var keys = new HashSet<String>(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName(); if (!keys.add(key)) throw new IOException("Duplicate key");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray(); while (reader.hasNext()) array.add(read(reader, depth + 1)); reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON token");
        };
    }
    static String string(JsonObject p, String key, String fallback) {
        JsonElement v = p.get(key); if (v == null || v.isJsonNull()) return fallback;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected text");
        String s = v.getAsString(); if (s.length() > 65536) throw new IllegalArgumentException("Text limit"); return s;
    }
    static boolean bool(JsonObject p, String key) {
        JsonElement v = p.get(key); if (v == null || v.isJsonNull()) return false;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean"); return v.getAsBoolean();
    }
    static Double number(JsonObject p, String key) {
        JsonElement v = p.get(key); if (v == null || v.isJsonNull()) return null;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
        double number = v.getAsDouble(); if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException("Invalid number"); return number;
    }
    private static Integer integer(JsonObject p, String key) {
        Long value = size(p, key); return value == null ? null : Math.toIntExact(value);
    }
    private static Long size(JsonObject p, String key) {
        JsonElement v = p.get(key); if (v == null || v.isJsonNull()) return null;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected size");
        long value = v.getAsBigDecimal().longValueExact(); if (value < 0) throw new IllegalArgumentException("Negative size"); return value;
    }
    private static Map<String, String> headers(JsonObject p) {
        var result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER); JsonElement value = p.get("http_headers");
        if (value == null || value.isJsonNull()) return result;
        if (!value.isJsonObject() || value.getAsJsonObject().size() > 64) throw new IllegalArgumentException("Invalid headers");
        for (String key : value.getAsJsonObject().keySet()) {
            String text = string(value.getAsJsonObject(), key, null);
            if (text == null || result.putIfAbsent(key, text) != null) throw new IllegalArgumentException("Invalid headers");
        }
        return result;
    }
}
