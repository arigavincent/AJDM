package io.ajdm.model;

import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RangeMutationPropertyTest {
    @Test void mixedMutationsMatchAnIndependentBitmapAfterEveryOperation() {
        for (int seed = 0; seed < 250; seed++) {
            var random = new Random(0x414a444dL + seed);
            var actual = new RangeSet(); var expected = new BitSet(257);
            for (int operation = 0; operation < 200; operation++) {
                int first = random.nextInt(258), second = random.nextInt(258);
                int start = Math.min(first, second), end = Math.max(first, second);
                if (random.nextBoolean()) { actual.add(start, end); expected.set(start, end); }
                else { actual.remove(start, end); expected.clear(start, end); }
                String context = "seed=" + seed + " operation=" + operation;
                var coverage = new BitSet(257); var gaps = new BitSet(257);
                long previousEnd = -1;
                for (var range : actual.ranges()) {
                    assertTrue(range.start() > previousEnd, context);
                    coverage.set((int) range.start(), (int) range.endExclusive()); previousEnd = range.endExclusive();
                }
                for (var gap : actual.gaps(257)) gaps.set((int) gap.start(), (int) gap.endExclusive());
                var missing = new BitSet(257); missing.set(0, 257); missing.xor(expected);
                assertEquals(expected, coverage, context); assertEquals(missing, gaps, context);
                assertEquals(expected.cardinality(), actual.coveredBytes(), context);
                assertEquals(expected.nextClearBit(0), actual.prefixLength(), context);
            }
        }
    }
    @Test void removingAndRestoringRangesNearLongMaxDoesNotOverflow() {
        var ranges = new RangeSet(List.of(new ByteRange(0, Long.MAX_VALUE)));
        ranges.remove(Long.MAX_VALUE - 31, Long.MAX_VALUE - 3);
        assertEquals(Long.MAX_VALUE - 28, ranges.coveredBytes());
        assertEquals(List.of(new ByteRange(Long.MAX_VALUE - 31, Long.MAX_VALUE - 3)), ranges.gaps(Long.MAX_VALUE));
        ranges.add(Long.MAX_VALUE - 31, Long.MAX_VALUE - 3);
        assertEquals(List.of(new ByteRange(0, Long.MAX_VALUE)), ranges.ranges());
        ranges.remove(0, Long.MAX_VALUE); assertEquals(0, ranges.coveredBytes());
        assertEquals(List.of(new ByteRange(0, Long.MAX_VALUE)), ranges.gaps(Long.MAX_VALUE));
    }
}
