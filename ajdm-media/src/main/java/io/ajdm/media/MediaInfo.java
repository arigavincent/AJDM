package io.ajdm.media;

import java.net.URI;
import java.util.List;

public record MediaInfo(String id, String title, String uploader, Double duration,
                        URI pageUri, List<MediaFormat> formats, int excludedFormats) {
    public MediaInfo { formats = List.copyOf(formats); }
    public List<MediaFormat> visible(int maxHeight, boolean showAll) {
        if (maxHeight < 1 || maxHeight > 4320) throw new IllegalArgumentException("Invalid resolution ceiling");
        return formats.stream().filter(f -> showAll || f.height() == null || f.height() <= maxHeight).toList();
    }
    public MediaFormat format(String id) {
        return formats.stream().filter(f -> f.formatId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Format is not available"));
    }
    @Override public String toString() { return "MediaInfo[formats=" + formats.size() + ", source=redacted]"; }
}
