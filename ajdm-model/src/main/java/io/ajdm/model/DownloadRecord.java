package io.ajdm.model;

import java.nio.file.Path;
import java.util.List;

/** Persisted metadata; HTTP credentials are excluded from request headers. */
public record DownloadRecord(TransferRequest request, Path partialPath, DownloadState state,
                             ProbeResult probe, List<ByteRange> completed, boolean readyToPublish,
                             String actualSha256, ErrorCode error, boolean publicationIntent) {
    public DownloadRecord(TransferRequest request, Path partialPath, DownloadState state, ProbeResult probe,
                          List<ByteRange> completed, boolean readyToPublish, String actualSha256, ErrorCode error) {
        this(request, partialPath, state, probe, completed, readyToPublish, actualSha256, error, false);
    }
    public DownloadRecord { completed = List.copyOf(completed); }
    public long durableBytes() { return new RangeSet(completed).coveredBytes(); }
    public long prefixLength() { return new RangeSet(completed).prefixLength(); }
    public long durableEnd() { return completed.stream().mapToLong(ByteRange::endExclusive).max().orElse(0); }
    public boolean resumeSupported() {
        return probe != null && probe.acceptRanges() && probe.contentLength() >= 0 && probe.validator() != null;
    }
}
