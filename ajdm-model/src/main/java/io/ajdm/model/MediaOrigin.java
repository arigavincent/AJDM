package io.ajdm.model;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Private restart-safe extraction identity; HTTP credentials are never part of this record. {@code hls} marks a
 * segmented stream the engine downloads part by part from an HLS media playlist instead of by byte ranges: fetched
 * from the source link, or supplied in {@code playlist} (a DASH representation's fragment list from extraction).
 */
public record MediaOrigin(URI pageUri, String mediaId, String formatId, String ext, String vcodec, String acodec, boolean hls, String playlist) {
    public static final int MAX_PLAYLIST_CHARS = 4 * 1024 * 1024;
    public MediaOrigin(URI pageUri, String mediaId, String formatId, String ext, String vcodec, String acodec) {
        this(pageUri, mediaId, formatId, ext, vcodec, acodec, false, null);
    }
    public MediaOrigin(URI pageUri, String mediaId, String formatId, String ext, String vcodec, String acodec, boolean hls) {
        this(pageUri, mediaId, formatId, ext, vcodec, acodec, hls, null);
    }
    public MediaOrigin {
        if (playlist != null && (!hls || playlist.length() > MAX_PLAYLIST_CHARS || !playlist.startsWith("#EXTM3U"))) {
            throw new IllegalArgumentException("Invalid supplied stream playlist");
        }
        new DownloadSource(pageUri, Map.of());
        for (String value : new String[]{mediaId, formatId, ext, vcodec, acodec}) {
            Objects.requireNonNull(value);
            if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid media identity");
            }
        }
    }
    @Override public String toString() { return "MediaOrigin[source=redacted]"; }
}
