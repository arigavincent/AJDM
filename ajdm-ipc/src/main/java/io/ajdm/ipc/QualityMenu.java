package io.ajdm.ipc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The short quality list the browser shows under "Download with AJDM", like IDM's video menu: one entry per
 * resolution (sound paired in automatically when the site serves it separately) and the best audio-only stream.
 * Built from the core's format inventory; only downloadable (non-fragmented) streams are offered.
 */
public final class QualityMenu {
    private QualityMenu() {}

    public static JsonArray of(JsonArray formats) {
        var rows = new ArrayList<JsonObject>();
        for (var element : formats) if (element.isJsonObject() && !flag(element.getAsJsonObject(), "fragmented")) rows.add(element.getAsJsonObject());
        var audio = rows.stream().filter(row -> text(row, "kind").equals("AUDIO_ONLY")).toList();
        var best = new LinkedHashMap<Integer, JsonObject>();
        rows.stream().filter(row -> List.of("VIDEO_AUDIO", "VIDEO_ONLY").contains(text(row, "kind")) && number(row, "height") > 0)
                .map(row -> entry(row, text(row, "kind").equals("VIDEO_ONLY") ? bestAudio(audio, row) : null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble((JsonObject entry) -> number(entry, "height")).reversed().thenComparing(QualityMenu::preference))
                .forEach(entry -> best.putIfAbsent((int) number(entry, "height"), entry));
        var menu = new JsonArray();
        best.values().forEach(menu::add);
        audio.stream().max(Comparator.comparingDouble(row -> number(row, "audioBitrateKbps"))).ifPresent(row -> menu.add(entry(row, null)));
        return menu;
    }

    /** A menu entry, or null for a video-only stream with no sound to pair it with. */
    private static JsonObject entry(JsonObject video, JsonObject sound) {
        boolean audioOnly = text(video, "kind").equals("AUDIO_ONLY");
        if (text(video, "kind").equals("VIDEO_ONLY") && sound == null) return null;
        var entry = new JsonObject();
        entry.addProperty("formatId", text(video, "formatId"));
        if (sound != null) entry.addProperty("audioId", text(sound, "formatId"));
        long size = size(video), extra = sound == null ? 0 : size(sound);
        long total = size < 0 || extra < 0 ? -1 : size + extra;
        entry.addProperty("sizeBytes", total);
        entry.addProperty("approximate", flag(video, "approximateSize") || sound != null && flag(sound, "approximateSize"));
        String container = (audioOnly ? text(video, "container") : sound == null ? text(video, "container") : merged(video)).toUpperCase(Locale.ROOT);
        entry.addProperty("height", (int) number(video, "height"));
        entry.addProperty("delivery", text(video, "delivery"));
        String quality = audioOnly ? "Audio" + (number(video, "audioBitrateKbps") > 0 ? " " + Math.round(number(video, "audioBitrateKbps")) + " kb/s" : "")
                : (int) number(video, "height") + "p" + (number(video, "fps") > 30 ? Math.round(number(video, "fps")) + "" : "") + (flag(video, "hdr") ? " HDR" : "");
        entry.addProperty("label", container + "  " + quality);
        return entry;
    }
    /** File delivery (byte ranges, fastest) over HLS/DASH, then MP4 (plays everywhere), then frame rate, then size. */
    private static int preference(JsonObject entry) {
        int score = 0;
        if (!text(entry, "delivery").equals("file")) score += 4;
        if (!text(entry, "label").startsWith("MP4")) score += 2;
        return score;
    }
    private static String merged(JsonObject video) { return text(video, "container").equals("webm") ? "webm" : "mp4"; }
    private static JsonObject bestAudio(List<JsonObject> audio, JsonObject video) {
        boolean webm = text(video, "container").equals("webm");
        return audio.stream().max(Comparator.comparingInt((JsonObject row) -> {
                    String codec = text(row, "audioCodec").toLowerCase(Locale.ROOT);
                    return webm ? codec.startsWith("opus") ? 1 : 0 : codec.startsWith("mp4a") || codec.startsWith("aac") ? 1 : 0;
                }).thenComparingDouble(row -> number(row, "audioBitrateKbps"))).orElse(null);
    }
    private static long size(JsonObject row) { return row.has("sizeBytes") && !row.get("sizeBytes").isJsonNull() ? row.get("sizeBytes").getAsLong() : -1; }
    private static String text(JsonObject row, String key) { return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : ""; }
    private static double number(JsonObject row, String key) { return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsDouble() : 0; }
    private static boolean flag(JsonObject row, String key) { return row.has(key) && !row.get(key).isJsonNull() && row.get(key).getAsBoolean(); }
}
