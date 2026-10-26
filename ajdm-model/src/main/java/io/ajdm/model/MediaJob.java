package io.ajdm.model;

import java.nio.file.Path;
import java.util.*;

/** Durable paired-stream parent. Source URLs/credentials belong only to the children's private origins. */
public record MediaJob(UUID id, UUID videoId, UUID audioId, Path target, Path workspace,
                       String videoExt, String audioExt, String container, ConflictPolicy conflict,
                       DownloadState state, long estimatedBytes, boolean approximateSize, ErrorCode error,
                       String videoHash, String audioHash, String outputHash, long outputBytes) {
    public MediaJob {
        Objects.requireNonNull(id); Objects.requireNonNull(videoId); Objects.requireNonNull(audioId);
        Objects.requireNonNull(conflict); Objects.requireNonNull(state);
        if (Set.of(id, videoId, audioId).size() != 3) throw new IllegalArgumentException("Distinct media identities required");
        target = target.toAbsolutePath().normalize(); workspace = workspace.toAbsolutePath().normalize();
        if (!Objects.equals(target.getParent(), workspace.getParent()) || !workspace.getFileName().toString().equals(".ajdm-media-" + id)
                || !target.getFileName().toString().equals(SafeFilename.sanitize(target.getFileName().toString()))) throw new IllegalArgumentException("Invalid media paths");
        // "ts" is a joined HLS stream; the muxer identifies its real container before demuxing.
        if (!Set.of("mp4", "m4a", "mov", "webm", "mkv", "ts").contains(videoExt) || !Set.of("mp4", "m4a", "mov", "webm", "mkv", "mp3", "ts").contains(audioExt)
                || !Set.of("mp4", "mkv").contains(container) || estimatedBytes < -1 || outputBytes < -1) throw new IllegalArgumentException("Invalid media plan");
        for (String hash : new String[]{videoHash, audioHash, outputHash}) if (hash != null && !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid media hash");
        if (outputHash != null && (videoHash == null || audioHash == null || outputBytes <= 0)) throw new IllegalArgumentException("Incomplete ready output");
    }
    public Path videoPath() { return workspace.resolve("video." + videoExt); }
    public Path audioPath() { return workspace.resolve("audio." + audioExt); }
    public Path stagePath() { return workspace.resolve("output." + container); }
    public MediaJob status(DownloadState next, ErrorCode reason) {
        return new MediaJob(id, videoId, audioId, target, workspace, videoExt, audioExt, container, conflict, next, estimatedBytes, approximateSize, reason, videoHash, audioHash, outputHash, outputBytes);
    }
    public MediaJob destination(Path next, ConflictPolicy policy) {
        return new MediaJob(id, videoId, audioId, next, workspace, videoExt, audioExt, container, policy, state, estimatedBytes, approximateSize, error, videoHash, audioHash, outputHash, outputBytes);
    }
    public MediaJob hashes(String video, String audio, String output, long bytes) {
        return new MediaJob(id, videoId, audioId, target, workspace, videoExt, audioExt, container, conflict, state, estimatedBytes, approximateSize, error, video, audio, output, bytes);
    }
}
