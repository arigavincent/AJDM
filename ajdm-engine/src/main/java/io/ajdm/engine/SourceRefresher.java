package io.ajdm.engine;

import io.ajdm.model.DownloadSource;
import java.io.IOException;

/** Extraction boundary: return a direct URL and complete headers; AJDM performs all payload transfers. */
@FunctionalInterface
public interface SourceRefresher {
    DownloadSource refresh(SourceRefreshContext context) throws IOException, InterruptedException;

    /** A renewed source plus, for a stream whose fragment list came from extraction (DASH), its renewed playlist. */
    record StreamSource(DownloadSource source, String playlist) {
        @Override public String toString() { return "StreamSource[source=redacted]"; }
    }
    /** Renewal for a supplied-playlist stream; refreshers that cannot renew fragment lists return no playlist. */
    default StreamSource refreshStream(SourceRefreshContext context) throws IOException, InterruptedException {
        return new StreamSource(refresh(context), null);
    }
}
