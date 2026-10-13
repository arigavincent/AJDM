package io.ajdm.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

public record PlaylistJob(UUID id, String title, URI page, Path directory, ConflictPolicy conflict,
                          int connections, boolean modernCodecs, DownloadState state, ErrorCode error, long nextExtractionAt) {
    public PlaylistJob {
        Objects.requireNonNull(id); Objects.requireNonNull(conflict); Objects.requireNonNull(state);
        if (title == null || title.length() > 4096 || connections < 1 || connections > 32 || nextExtractionAt < 0) throw new IllegalArgumentException("Invalid playlist job");
        new DownloadSource(page, Map.of()); directory = directory.toAbsolutePath().normalize();
    }
    public PlaylistJob status(DownloadState next, ErrorCode reason) { return new PlaylistJob(id, title, page, directory, conflict, connections, modernCodecs, next, reason, nextExtractionAt); }
    public PlaylistJob nextExtraction(long time) { return new PlaylistJob(id, title, page, directory, conflict, connections, modernCodecs, state, error, time); }
    @Override public String toString() { return "PlaylistJob[id=" + id + ", page=redacted]"; }
}
