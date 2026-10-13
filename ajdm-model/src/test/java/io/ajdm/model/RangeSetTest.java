package io.ajdm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RangeSetTest {
    @Test void coalescesOverlappingAndAdjacentRanges() {
        var ranges = new RangeSet();
        ranges.add(10, 20); ranges.add(0, 5); ranges.add(5, 12); ranges.add(10, 20);
        assertEquals(List.of(new ByteRange(0, 20)), ranges.ranges());
        assertEquals(20, ranges.coveredBytes());
        assertEquals(20, ranges.prefixLength());
        assertEquals(List.of(new ByteRange(20, 25)), ranges.gaps(25));
        assertEquals(List.of(), ranges.gaps(20));
    }
    @Test void rejectsInvalidBoundsAndOutOfRepresentationCoverage() {
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(-1, 5));
        var ranges = new RangeSet();
        assertThrows(IllegalArgumentException.class, () -> ranges.add(3, 2));
        assertThrows(IllegalArgumentException.class, () -> ranges.gaps(-1));
        ranges.add(5, 10);
        assertThrows(IllegalArgumentException.class, () -> ranges.gaps(9));
    }
    @Test void randomizedCoverageIsOrderIndependentAndPartitionsEveryByte() {
        Random random = new Random(7301);
        for (int trial = 0; trial < 500; trial++) {
            int total = 300;
            boolean[] bitmap = new boolean[total];
            List<ByteRange> input = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int start = random.nextInt(total), end = start + 1 + random.nextInt(total - start);
                input.add(new ByteRange(start, end));
                java.util.Arrays.fill(bitmap, start, end, true);
            }
            var first = new RangeSet(input);
            Collections.shuffle(input, random);
            var second = new RangeSet(input);
            assertEquals(first.ranges(), second.ranges());
            long expected = 0;
            for (boolean covered : bitmap) if (covered) expected++;
            assertEquals(expected, first.coveredBytes());
            assertEquals(total, first.coveredBytes() + first.gaps(total).stream().mapToLong(ByteRange::length).sum());
            for (ByteRange gap : first.gaps(total)) for (long b = gap.start(); b < gap.endExclusive(); b++) assertFalse(bitmap[(int) b]);
        }
    }
    @Test void preservesLargeOffsetsWithoutIntegerOverflow() {
        var ranges = new RangeSet();
        ranges.add(0, Long.MAX_VALUE - 1); ranges.add(Long.MAX_VALUE - 1, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, ranges.coveredBytes());
        assertEquals(List.of(), ranges.gaps(Long.MAX_VALUE));
    }
}
