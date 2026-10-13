package io.ajdm.ui;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RateTrackerTest {
    @Test void recentWrittenBytesDetermineSpeedAndEtaWithoutInventingAnInitialRate() {
        var tracker = new RateTracker(); var id = UUID.randomUUID();
        assertEquals(0, tracker.sample(id, "DOWNLOADING", 0, 1000, 0).bytesPerSecond());
        var reading = tracker.sample(id, "DOWNLOADING", 200, 1000, 2_000_000_000L);
        assertEquals(100, reading.bytesPerSecond()); assertEquals(8, reading.etaSeconds());
        assertEquals(0.2, reading.fraction());
    }
    @Test void pauseCounterResetAndLongPollingGapResetTheRateWindow() {
        var tracker = new RateTracker(); var id = UUID.randomUUID();
        tracker.sample(id, "DOWNLOADING", 10, 100, 0); tracker.sample(id, "DOWNLOADING", 20, 100, 1_000_000_000L);
        assertEquals(0, tracker.sample(id, "PAUSED", 20, 100, 2_000_000_000L).bytesPerSecond());
        assertEquals(0, tracker.sample(id, "DOWNLOADING", 20, 100, 3_000_000_000L).bytesPerSecond());
        assertEquals(0, tracker.sample(id, "DOWNLOADING", 10, 100, 4_000_000_000L).bytesPerSecond());
        assertEquals(0, tracker.sample(id, "DOWNLOADING", 50, 100, 40_000_000_000L).bytesPerSecond());
    }
    @Test void UnknownSizeAndMergingNeverHaveAnInventedEtaAndHistoryIsBounded() {
        var tracker = new RateTracker(); var id = UUID.randomUUID();
        for (int i = 0; i < 200; i++) tracker.sample(id, "DOWNLOADING", i, -1, i * 1_000_000_000L);
        var unknown = tracker.sample(id, "DOWNLOADING", 200, -1, 200_000_000_000L);
        assertEquals(-1, unknown.fraction()); assertEquals(-1, unknown.etaSeconds()); assertTrue(unknown.history().size() <= 60);
        assertEquals(-1, tracker.sample(id, "MERGING", 200, 200, 201_000_000_000L).etaSeconds());
        assertEquals(1, tracker.sample(id, "COMPLETED", 200, 200, 202_000_000_000L).fraction());
    }
}
