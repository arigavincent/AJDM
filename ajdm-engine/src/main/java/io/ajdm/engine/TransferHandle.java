package io.ajdm.engine;

import io.ajdm.model.ProgressSnapshot;
import io.ajdm.model.TransferResult;
import java.util.UUID;
import java.util.Optional;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public interface TransferHandle {
    UUID id();
    ProgressSnapshot progress();
    CompletionStage<TransferResult> completion();
    /** Present once the engine creates a partial file; a successful transfer normally removes it. */
    Optional<Path> partialPath();
    /** Completes only after the attempt stops and the paused state is durably saved. */
    CompletionStage<Void> pause();
    /** Submitted positional-write bytes whose disk jobs have not settled, across all transfer modes. */
    default long queuedWriteBytes() { return 0; }
    /** Live adaptive diagnostics; fixed transfers return an empty snapshot. */
    default AdaptationSnapshot adaptation() { return AdaptationSnapshot.empty(); }
    void cancel();
}
