package io.ajdm.model;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RangeRemovalTest {
    @Test void randomizedAddAndRemoveMatchAnIndependentBitmap() {
        var random = new Random(3837);
        var ranges = new RangeSet();
        boolean[] expected = new boolean[512];
        for (int operation = 0; operation < 2000; operation++) {
            int start = random.nextInt(513), end = start + random.nextInt(513 - start);
            boolean add = random.nextBoolean();
            if (add) ranges.add(start, end); else ranges.remove(start, end);
            java.util.Arrays.fill(expected, start, end, add);
            int count = 0;
            for (int position = 0; position < expected.length; position++) {
                boolean actual = false;
                for (var range : ranges.ranges()) if (position >= range.start() && position < range.endExclusive()) actual = true;
                assertEquals(expected[position], actual);
                if (expected[position]) count++;
            }
            assertEquals(count, ranges.coveredBytes());
        }
        var edge = new RangeSet(List.of(new ByteRange(0, Long.MAX_VALUE)));
        edge.remove(1, Long.MAX_VALUE - 1);
        assertEquals(List.of(new ByteRange(0, 1), new ByteRange(Long.MAX_VALUE - 1, Long.MAX_VALUE)), edge.ranges());
    }
}
