package io.ajdm.model;

import java.net.URI;
import java.util.*;

/** Selection and child admission intent are durable; resolved URLs/credentials remain in child state. */
public record PlaylistItem(UUID playlistId, int index, String mediaId, String title, Double duration, URI page,
                           String preset, String formatId, String audioId, State state, UUID childId, ErrorCode error) {
    public enum State { QUEUED, EXTRACTING, DOWNLOADING, PAUSED, COMPLETED, SKIPPED, FAILED, CANCELED }
    public PlaylistItem {
        Objects.requireNonNull(playlistId); Objects.requireNonNull(state);
        if (index < 1 || index > 1000 || mediaId == null || mediaId.length() > 256 || title == null || title.length() > 4096
                || duration != null && (!Double.isFinite(duration) || duration < 0)) throw new IllegalArgumentException("Invalid playlist item");
        if ((preset == null) == (formatId == null) || audioId != null && formatId == null) throw new IllegalArgumentException("Invalid item selection");
        if (preset != null && !Set.of("BEST", "P1080", "P720", "AUDIO").contains(preset)) throw new IllegalArgumentException("Invalid preset");
        for (String id : new String[]{formatId, audioId}) if (id != null && (id.isBlank() || id.length() > 256 || id.codePoints().anyMatch(Character::isISOControl))) throw new IllegalArgumentException("Invalid format ID");
        if (page != null) new DownloadSource(page, Map.of());
        if (page == null && state != State.SKIPPED && state != State.CANCELED) throw new IllegalArgumentException("Missing item page");
    }
    public PlaylistItem status(State next, ErrorCode reason) { return new PlaylistItem(playlistId, index, mediaId, title, duration, page, preset, formatId, audioId, next, childId, reason); }
    public PlaylistItem child(UUID child) { return new PlaylistItem(playlistId, index, mediaId, title, duration, page, preset, formatId, audioId, state, child, error); }
    public boolean terminal() { return Set.of(State.COMPLETED, State.SKIPPED, State.FAILED, State.CANCELED).contains(state); }
    @Override public String toString() { return "PlaylistItem[playlist=" + playlistId + ", index=" + index + ", page=redacted]"; }
}
