package io.ajdm.engine;

import io.ajdm.model.DownloadSource;
import java.io.IOException;

/** Extraction boundary: return a direct URL and complete headers; AJDM performs all payload transfers. */
@FunctionalInterface
public interface SourceRefresher {
    DownloadSource refresh(SourceRefreshContext context) throws IOException, InterruptedException;
}
