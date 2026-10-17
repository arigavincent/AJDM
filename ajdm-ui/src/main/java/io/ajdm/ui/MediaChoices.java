package io.ajdm.ui;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Presentation and explicit stream pairing from the safe IPC inventory. Never handles source URLs. */
final class MediaChoices {
    private MediaChoices() { }
    static String text(JsonObject row, String key) { return AddDownloadDialog.string(row, key); }
    static double number(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsDouble() : 0;
    }
    static boolean flag(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() && row.get(key).getAsBoolean();
    }
    static boolean available(JsonObject row) {
        return !flag(row, "fragmented") && List.of("VIDEO_ONLY", "VIDEO_AUDIO", "AUDIO_ONLY", "UNKNOWN").contains(text(row, "kind"));
    }
    static String quality(JsonObject row) {
        if (text(row, "kind").equals("AUDIO_ONLY")) return "Audio only" + rate(row, "audioBitrateKbps");
        int height = (int) number(row, "height");
        String name = switch (height) {
            case 720 -> "720p (HD)"; case 1080 -> "1080p (Full HD)"; case 1440 -> "1440p (QHD)";
            case 2160 -> "2160p (4K)"; case 4320 -> "4320p (8K)";
            default -> height > 0 ? height + "p" : "Resolution unknown";
        };
        double fps = number(row, "fps");
        String note=text(row,"formatId").startsWith("resolver-tiktok-")?text(row,"note").strip():"";
        return (note.isBlank()?"":note+" · ") + name + (fps > 0 ? " · " + decimal(fps) + " fps" : "") + (flag(row, "hdr") ? " · HDR" : "")
                + (text(row, "delivery").equals("hls") ? " · stream" : "");
    }
    static String audioLabel(JsonObject row) {
        String language = text(row, "language");
        String readable = language.isBlank() || language.equals("und") ? "" : Locale.forLanguageTag(language.replace('_', '-')).getDisplayName(Locale.ENGLISH);
        String note = text(row, "note").strip().replace("DRC", "volume balanced");
        return (readable.isBlank() ? "" : readable + " · ") + codec(text(row, "audioCodec")) + rate(row, "audioBitrateKbps") + (note.isBlank() ? "" : " · " + note);
    }
    static String encoding(JsonObject row) {
        return codec(text(row, text(row, "kind").equals("AUDIO_ONLY") ? "audioCodec" : "videoCodec"));
    }
    static String size(JsonObject row) {
        if (!row.has("sizeBytes") || row.get("sizeBytes").isJsonNull() || row.get("sizeBytes").getAsLong() < 0) return "Unknown size";
        return (flag(row, "approximateSize") ? "About " : "") + Display.mib(row.get("sizeBytes").getAsLong());
    }
    static JsonObject bestAudio(List<JsonObject> inventory, JsonObject video) {
        return inventory.stream().filter(row -> text(row, "kind").equals("AUDIO_ONLY") && !flag(row, "fragmented"))
                .max(Comparator.comparingDouble((JsonObject row) -> number(row, "audioBitrateKbps"))
                        .thenComparingInt(row -> compatible(row, video))
                        .thenComparing(row -> text(row, "formatId"), Comparator.reverseOrder())).orElse(null);
    }
    private static int compatible(JsonObject audio, JsonObject video) {
        String codec = text(audio, "audioCodec");
        return text(video, "container").equals("webm") ? codec.startsWith("opus") ? 1 : 0
                : codec.startsWith("aac") || codec.startsWith("mp4a") ? 1 : 0;
    }
    private static String rate(JsonObject row, String key) { double rate = number(row, key); return rate > 0 ? " · " + decimal(rate) + " kb/s" : ""; }
    private static String decimal(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String codec(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.startsWith("av01") || value.startsWith("av1")) return "AV1";
        if (value.startsWith("avc") || value.startsWith("h264")) return "H.264";
        if (value.startsWith("hev") || value.startsWith("hvc") || value.startsWith("hevc") || value.startsWith("h265")) return "H.265";
        if (value.startsWith("vp09") || value.startsWith("vp9")) return "VP9";
        if (value.startsWith("mp4a") || value.startsWith("aac")) return "AAC";
        if (value.startsWith("opus")) return "Opus";
        if (value.startsWith("vorbis")) return "Vorbis";
        return value.isBlank() || value.equals("none") || value.equals("unknown") ? "Unknown" : raw;
    }
}
