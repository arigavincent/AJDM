package io.ajdm.media;

import io.ajdm.model.DownloadSource;
import java.util.Objects;

/** Source access is explicit; diagnostics never contain the resolved URL or headers. */
public record MediaFormat(String formatId, String ext, String vcodec, String acodec,
                          Integer width, Integer height, Double fps, Double tbrKbps, Double abrKbps,
                          Long filesize, Long filesizeApprox, boolean hdr, boolean fragmented, String note, String language,
                          DownloadSource source) {
    public enum Kind { VIDEO_AUDIO, VIDEO_ONLY, AUDIO_ONLY, UNKNOWN }
    public MediaFormat {
        Objects.requireNonNull(source);
        if (!"https".equalsIgnoreCase(source.uri().getScheme()) || formatId == null || formatId.isBlank()
                || formatId.length() > 256 || formatId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid direct media source");
        }
        if (ext == null || !ext.matches("[a-zA-Z0-9]{1,16}")) throw new IllegalArgumentException("Invalid media extension");
    }
    public boolean video() { return vcodec != null && !SetHolder.ABSENT.contains(vcodec); }
    public boolean audio() { return acodec != null && !SetHolder.ABSENT.contains(acodec); }
    public Kind kind() {
        if ("unknown".equals(vcodec) || "unknown".equals(acodec) || !video() && !audio()) return Kind.UNKNOWN;
        return video() ? audio() ? Kind.VIDEO_AUDIO : Kind.VIDEO_ONLY : Kind.AUDIO_ONLY;
    }
    private static final class SetHolder { private static final java.util.Set<String> ABSENT = java.util.Set.of("none", "unknown"); }
    public long sizeBytes() { return filesize != null ? filesize : filesizeApprox == null ? -1 : filesizeApprox; }
    public boolean approximateSize() { return filesize == null && filesizeApprox != null; }
    @Override public String toString() { return "MediaFormat[kind=" + kind() + ", source=redacted]"; }
}
