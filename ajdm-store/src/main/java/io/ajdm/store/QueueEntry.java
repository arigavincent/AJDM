package io.ajdm.store;

import java.util.Objects;
import java.util.UUID;

/** A download or paired media job waiting for a slot, the schedule or the data budget. */
public record QueueEntry(UUID id, Kind kind) {
    public enum Kind { DOWNLOAD, MEDIA_JOB }
    public QueueEntry { Objects.requireNonNull(id); Objects.requireNonNull(kind); }
}
