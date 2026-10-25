package io.ajdm.bench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPacingTest {
    private static final long MS = 1_000_000L;
    @Test void timerOversleepIsCaughtUpButAStalledReaderEarnsNoBurst() {
        long scheduled = 1_000 * MS;
        assertEquals(scheduled, BenchmarkServer.paced(scheduled, scheduled - 5 * MS), "Ahead of schedule waits for its slot");
        assertEquals(scheduled, BenchmarkServer.paced(scheduled, scheduled + 16 * MS), "A coarse-timer oversleep keeps the schedule");
        assertEquals(scheduled, BenchmarkServer.paced(scheduled, scheduled + BenchmarkServer.CATCH_UP), "The catch-up bound is inclusive");
        long stalled = scheduled + BenchmarkServer.CATCH_UP + MS;
        assertEquals(stalled, BenchmarkServer.paced(scheduled, stalled), "A stalled reader restarts from now");
    }
}
