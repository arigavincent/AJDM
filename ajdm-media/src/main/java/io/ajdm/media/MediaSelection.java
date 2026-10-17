package io.ajdm.media;

import java.util.*;

public final class MediaSelection {
    public enum Preset { BEST, P1080, P720, AUDIO }
    public record Plan(List<MediaFormat> formats, String container, long sizeBytes, boolean approximateSize) {
        public Plan { formats = List.copyOf(formats); }
        public boolean requiresMux() { return formats.size() == 2; }
    }
    private MediaSelection() {}
    public static Plan preset(MediaInfo info, Preset preset, boolean modern) {
        if (preset == Preset.AUDIO) return plan(List.of(bestAudio(info, null)));
        int ceiling = switch (preset) { case BEST -> 2160; case P1080 -> 1080; case P720 -> 720; default -> throw new AssertionError(); };
        Comparator<MediaFormat> order = Comparator.comparingInt((MediaFormat f) -> f.height() == null ? 0 : f.height())
                .thenComparingDouble(f -> n(f.fps())).thenComparingInt(f -> codec(f.vcodec(), modern))
                .thenComparingDouble(f -> n(f.tbrKbps())).thenComparingInt(f -> f.hls() ? 0 : 1)
                .thenComparing(MediaFormat::formatId, Comparator.reverseOrder());
        MediaFormat video = info.formats().stream().filter(f -> !f.fragmented() && f.kind() != MediaFormat.Kind.UNKNOWN && f.video() && f.height() != null && f.height() <= ceiling)
                .max(order).orElseGet(() -> {
                    if (preset != Preset.BEST) throw new IllegalArgumentException("No format fits preset");
                    return info.formats().stream().filter(f -> !f.fragmented() && f.height() == null && (f.video() || f.kind() == MediaFormat.Kind.UNKNOWN))
                            .max(order).orElseThrow(() -> new IllegalArgumentException("No format fits preset"));
                });
        if (video.kind() == MediaFormat.Kind.UNKNOWN) return plan(List.of(video));
        return plan(video.audio() ? List.of(video) : List.of(video, bestAudio(info, video)));
    }
    public static Plan custom(MediaInfo info, String first, String second) {
        MediaFormat f = info.format(first); if (second == null) return plan(List.of(f));
        MediaFormat audio = info.format(second);
        if (f.kind() != MediaFormat.Kind.VIDEO_ONLY || audio.kind() != MediaFormat.Kind.AUDIO_ONLY) throw new IllegalArgumentException("Select one video-only and one audio-only stream");
        return plan(List.of(f, audio));
    }
    private static MediaFormat bestAudio(MediaInfo info, MediaFormat video) {
        Comparator<MediaFormat> order = Comparator.comparingDouble((MediaFormat f) -> n(f.abrKbps()))
                .thenComparingInt(f -> audioPreference(f, video)).thenComparingInt(f -> f.hls() ? 0 : 1)
                .thenComparing(MediaFormat::formatId, Comparator.reverseOrder());
        return info.formats().stream().filter(f -> !f.fragmented() && f.kind() == MediaFormat.Kind.AUDIO_ONLY).max(order)
                .orElseThrow(() -> new IllegalArgumentException("No audio-only format"));
    }
    private static int audioPreference(MediaFormat audio, MediaFormat video) {
        if (video == null) return 0;
        boolean webm = video.ext().equals("webm"); return webm ? audio.acodec().startsWith("opus") ? 1 : 0 : audio.acodec().startsWith("mp4a") || audio.acodec().startsWith("aac") ? 1 : 0;
    }
    private static int codec(String codec, boolean modern) {
        int family = codec.startsWith("av01") || codec.startsWith("av1") ? 3 : codec.startsWith("vp9") || codec.startsWith("vp09") ? 2
                : codec.startsWith("avc") || codec.startsWith("h264") ? 1 : 0;
        return modern || family == 0 ? family : 4 - family;
    }
    private static double n(Double value) { return value == null ? 0 : value; }
    private static Plan plan(List<MediaFormat> formats) {
        String container = formats.getFirst().ext();
        if (formats.size() == 2) {
            var video = formats.getFirst(); var audio = formats.getLast();
            container = (video.vcodec().startsWith("avc") || video.vcodec().startsWith("av01") || video.vcodec().startsWith("av1"))
                    && (audio.acodec().startsWith("aac") || audio.acodec().startsWith("mp4a")) ? "mp4" : "mkv";
        }
        long size = 0; boolean approximate = false;
        for (var f : formats) {
            approximate |= f.approximateSize();
            if (f.sizeBytes() < 0) size = -1;
            else if (size >= 0) { try { size = Math.addExact(size, f.sizeBytes()); } catch (ArithmeticException e) { size = -1; } }
        }
        return new Plan(formats, container, size, approximate);
    }
}
