package io.ajdm.engine;

import io.ajdm.model.DownloadException;
import io.ajdm.model.DownloadSource;
import io.ajdm.model.ProbeResult;
import io.ajdm.net.CancellationScope;
import java.util.Optional;
import java.util.UUID;

/** Attempt-local context. URL/header access is explicit; diagnostics redact credentials. */
public final class SourceRefreshContext {
    private final UUID id;
    private final DownloadSource failedSource;
    private final ProbeResult previousProbe;
    private final int httpStatus, attempt;
    private final CancellationScope cancellation;
    SourceRefreshContext(UUID id, DownloadSource failedSource, ProbeResult previousProbe, int httpStatus,
                         int attempt, CancellationScope cancellation) {
        this.id = id; this.failedSource = failedSource; this.previousProbe = previousProbe;
        this.httpStatus = httpStatus; this.attempt = attempt; this.cancellation = cancellation;
    }
    public UUID id() { return id; }
    public DownloadSource failedSource() { return failedSource; }
    public Optional<ProbeResult> previousProbe() { return Optional.ofNullable(previousProbe); }
    public int httpStatus() { return httpStatus; }
    public int attempt() { return attempt; }
    public boolean isCanceled() { return cancellation.isCanceled(); }
    public void checkCanceled() throws DownloadException { cancellation.check(); }
    @Override public String toString() {
        return "SourceRefreshContext[id=" + id + ", httpStatus=" + httpStatus + ", attempt=" + attempt + ", source=redacted]";
    }
}
