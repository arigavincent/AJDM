package io.ajdm.model;

/** Half-open interval [start, endExclusive). */
public record ByteRange(long start, long endExclusive) {
    public ByteRange {
        if (start < 0 || endExclusive <= start) throw new IllegalArgumentException("Invalid byte range");
    }
    public long length() { return endExclusive - start; }
}
