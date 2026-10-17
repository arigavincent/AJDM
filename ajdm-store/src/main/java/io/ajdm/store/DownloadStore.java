package io.ajdm.store;

import io.ajdm.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface DownloadStore extends AutoCloseable {
    void create(TransferRequest request, Path partial) throws IOException;
    void create(TransferRequest request, Path partial, MediaOrigin origin) throws IOException;
    java.util.Optional<MediaOrigin> mediaOrigin(UUID id) throws IOException;
    DownloadRecord get(UUID id) throws IOException;
    boolean exists(UUID id) throws IOException;
    List<DownloadRecord> list() throws IOException;
    void describe(UUID id, ProbeResult probe) throws IOException;
    void replaceSource(UUID id, DownloadSource source, ProbeResult probe) throws IOException;
    /**
     * Renewed link for an HLS download, whose identity is its playlist structure rather than one file's size and
     * validator: only downloads stored with HLS delivery qualify, and HTTPS is never downgraded. A non-null
     * {@code playlist} replaces a supplied (DASH) fragment list in the same commit.
     */
    void replaceStreamSource(UUID id, DownloadSource source, String playlist) throws IOException;
    /** A completed HLS download's file was remuxed beside it: point the record at the new same-folder file. */
    void relocateCompletedStream(UUID id, java.nio.file.Path target) throws IOException;
    void checkpoint(UUID id, List<ByteRange> coverage, DownloadState state) throws IOException;
    void checkpoint(UUID id, List<ByteRange> coverage, DownloadState state, ErrorCode error) throws IOException;
    void state(UUID id, DownloadState state, ErrorCode error) throws IOException;
    void ready(UUID id, String actualSha256) throws IOException;
    void publicationIntent(UUID id, String actualSha256) throws IOException;
    void destination(UUID id, Path target, ConflictPolicy policy) throws IOException;
    @Override void close() throws IOException;
}
