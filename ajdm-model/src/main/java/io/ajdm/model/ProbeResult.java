package io.ajdm.model;

import java.net.URI;

public record ProbeResult(URI finalUri, long contentLength, boolean acceptRanges,
                          String etag, String lastModified, String filename, String contentType) {
    public ProbeResult {
        filename = filename == null ? SafeFilename.fromUri(finalUri) : SafeFilename.sanitize(filename);
    }
    public ProbeResult(URI finalUri, long contentLength, boolean acceptRanges, String etag, String lastModified) {
        this(finalUri, contentLength, acceptRanges, etag, lastModified, null, null);
    }
    @Override public String toString() { return "ProbeResult[source=redacted, size=" + contentLength + ", ranges=" + acceptRanges + "]"; }
    public String validator() {
        return etag != null && !etag.startsWith("W/") ? etag : lastModified;
    }
}
