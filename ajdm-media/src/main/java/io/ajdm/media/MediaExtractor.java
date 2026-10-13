package io.ajdm.media;

import java.io.IOException;
import java.net.URI;
import java.util.function.BooleanSupplier;

/** Metadata boundary only. Returned sources are downloaded by AJDM. */
public interface MediaExtractor extends AutoCloseable {
    MediaInfo extract(URI page, BooleanSupplier canceled) throws IOException, InterruptedException;
    default MediaInfo extractEntry(URI page, String mediaId, BooleanSupplier canceled) throws IOException, InterruptedException {
        return extract(page, canceled);
    }
    /** A chosen stable format may have its own metadata provider. Selection remains exact. */
    default MediaInfo extractSelected(URI page,String mediaId,String formatId,BooleanSupplier canceled) throws IOException,InterruptedException {
        return mediaId==null?extract(page,canceled):extractEntry(page,mediaId,canceled);
    }
    default void configureCookies(java.nio.file.Path file) throws IOException {
        throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.FEATURE_UNAVAILABLE, "Extractor authentication unavailable");
    }
    default void configureBrowser(MediaBrowser browser) throws IOException {
        throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.FEATURE_UNAVAILABLE, "Browser sessions unavailable");
    }
    default PlaylistInfo playlist(URI page, BooleanSupplier canceled) throws IOException, InterruptedException {
        throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.FEATURE_UNAVAILABLE, "Flat playlist extraction unavailable");
    }
    @Override default void close() {}
}
