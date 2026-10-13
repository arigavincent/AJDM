package io.ajdm.engine;

import java.io.IOException;
import java.util.UUID;

/** Diagnostics/fault-injection boundary; implementations must not perform transfer or database writes. */
@FunctionalInterface
public interface CheckpointObserver {
    enum Phase { BEFORE_FILE_FORCE, AFTER_FILE_FORCE, AFTER_DB_COMMIT, AFTER_READY_COMMIT, AFTER_FINAL_LINK, AFTER_SOURCE_REFRESH_COMMIT,
        AFTER_DESTINATION_COMMIT, AFTER_OVERWRITE_STAGE, AFTER_PREALLOCATION, AFTER_PUBLICATION_INTENT, AFTER_REPROBE_COMMIT }
    void at(Phase phase, UUID id, long bytes) throws IOException;
    static CheckpointObserver none() { return (phase, id, bytes) -> {}; }
}
