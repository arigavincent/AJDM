package io.ajdm.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TransferRequest(UUID id, URI uri, Path target, Map<String, String> headers,
                              TransferOptions options, ConflictPolicy conflictPolicy) {
    public TransferRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(uri); Objects.requireNonNull(target);
        Objects.requireNonNull(options); Objects.requireNonNull(conflictPolicy);
        var source = new DownloadSource(uri, headers);
        if (target.getFileName() == null || target.getFileName().toString().equals(".") || target.getFileName().toString().equals("..")) {
            throw new IllegalArgumentException("Target must name a file");
        }
        target = target.toAbsolutePath().normalize();
        if (target.getFileName() == null) throw new IllegalArgumentException("Target must name a file");
        headers = source.headers();
    }
    /** Legacy explicit destinations keep their no-clobber policy. */
    public TransferRequest(UUID id, URI uri, Path target, Map<String, String> headers, TransferOptions options) {
        this(id, uri, target, headers, options, ConflictPolicy.ASK);
    }
    @Override public String toString() { return "TransferRequest[id=" + id + ", source=redacted]"; }
    public static TransferRequest of(URI uri, Path target) {
        var request = new TransferRequest(UUID.randomUUID(), uri, target, Map.of(), TransferOptions.defaults(), ConflictPolicy.RENAME);
        request.requireSafeTarget(); return request;
    }
    public static TransferRequest inDirectory(URI uri, Path directory, ProbeResult probe) {
        return of(uri, directory.resolve(probe.filename()));
    }
    public TransferRequest withConflictPolicy(ConflictPolicy policy) {
        return new TransferRequest(id, uri, target, headers, options, policy);
    }
    public TransferRequest withTarget(Path destination) {
        var request = new TransferRequest(id, uri, destination, headers, options, conflictPolicy);
        request.requireSafeTarget(); return request;
    }
    /** Check fresh destinations without preventing reads of legacy persisted local paths. */
    public void requireSafeTarget() {
        if (!target.getFileName().toString().equals(SafeFilename.sanitize(target.getFileName().toString()))) {
            throw new IllegalArgumentException("Target must have a portable safe filename");
        }
    }
}
