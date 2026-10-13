package io.ajdm.model;

import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;

/** Pure domain coverage. Instances are confined to one owner or protected by an external lock. */
public final class RangeSet {
    private final TreeMap<Long, Long> intervals = new TreeMap<>();
    public RangeSet() {}
    public RangeSet(List<ByteRange> ranges) { ranges.forEach(range -> add(range.start(), range.endExclusive())); }
    public void add(long start, long endExclusive) {
        if (start < 0 || endExclusive < start) throw new IllegalArgumentException("Invalid byte range");
        if (start == endExclusive) return;
        var previous = intervals.floorEntry(start);
        if (previous != null && previous.getValue() >= start) {
            start = previous.getKey(); endExclusive = Math.max(endExclusive, previous.getValue());
            intervals.remove(previous.getKey());
        }
        var next = intervals.ceilingEntry(start);
        while (next != null && next.getKey() <= endExclusive) {
            endExclusive = Math.max(endExclusive, next.getValue()); intervals.remove(next.getKey());
            next = intervals.ceilingEntry(start);
        }
        intervals.put(start, endExclusive);
    }
    public List<ByteRange> ranges() { return intervals.entrySet().stream().map(e -> new ByteRange(e.getKey(), e.getValue())).toList(); }
    public void remove(long start, long endExclusive) {
        if (start < 0 || endExclusive < start) throw new IllegalArgumentException("Invalid byte range");
        if (start == endExclusive) return;
        for (ByteRange range : ranges()) {
            if (range.endExclusive() <= start || range.start() >= endExclusive) continue;
            intervals.remove(range.start());
            if (range.start() < start) intervals.put(range.start(), start);
            if (range.endExclusive() > endExclusive) intervals.put(endExclusive, range.endExclusive());
        }
    }
    public List<ByteRange> gaps(long total) {
        if (total < 0 || !intervals.isEmpty() && intervals.lastEntry().getValue() > total) {
            throw new IllegalArgumentException("Coverage outside representation");
        }
        var gaps = new ArrayList<ByteRange>();
        long position = 0;
        for (var entry : intervals.entrySet()) {
            if (position < entry.getKey()) gaps.add(new ByteRange(position, entry.getKey()));
            position = entry.getValue();
        }
        if (position < total) gaps.add(new ByteRange(position, total));
        return List.copyOf(gaps);
    }
    public long coveredBytes() { return intervals.entrySet().stream().mapToLong(e -> e.getValue() - e.getKey()).sum(); }
    public long prefixLength() { return intervals.getOrDefault(0L, 0L); }
}
