package io.ajdm.model;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Private restart-safe extraction identity; HTTP credentials are never part of this record. */
public record MediaOrigin(URI pageUri, String mediaId, String formatId, String ext, String vcodec, String acodec) {
    public MediaOrigin {
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
