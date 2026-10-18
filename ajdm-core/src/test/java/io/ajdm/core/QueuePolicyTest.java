package io.ajdm.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueuePolicyTest {
    private static final long MIB = 1024L * 1024;

    @Test void aNightWindowRunsAcrossMidnightAndADayWindowDoesNot() {
        var night = new QueuePolicy(3, true, LocalTime.of(23, 0), LocalTime.of(6, 0), false, 500 * MIB);
        assertTrue(night.windowOpen(LocalTime.of(23, 0))); assertTrue(night.windowOpen(LocalTime.of(2, 30)));
        assertFalse(night.windowOpen(LocalTime.of(6, 0))); assertFalse(night.windowOpen(LocalTime.of(12, 0)));
        var day = new QueuePolicy(3, true, LocalTime.of(9, 0), LocalTime.of(17, 30), false, 500 * MIB);
        assertTrue(day.windowOpen(LocalTime.of(9, 0))); assertFalse(day.windowOpen(LocalTime.of(17, 30))); assertFalse(day.windowOpen(LocalTime.of(3, 0)));
        assertTrue(QueuePolicy.DEFAULT.windowOpen(LocalTime.NOON), "No schedule means always open");
    }

    @Test void theBudgetBlocksBeforeTheScheduleAndOnlyWhenEnabled() {
        var policy = new QueuePolicy(2, true, LocalTime.of(23, 0), LocalTime.of(6, 0), true, 500 * MIB);
        assertEquals(QueuePolicy.Wait.BUDGET, policy.blocked(LocalTime.of(1, 0), 500 * MIB));
        assertEquals(QueuePolicy.Wait.SCHEDULE, policy.blocked(LocalTime.NOON, 10 * MIB));
        assertNull(policy.blocked(LocalTime.of(1, 0), 499 * MIB));
        assertNull(QueuePolicy.DEFAULT.blocked(LocalTime.NOON, 1L << 50));
    }

    @Test void settingsRoundTripAndDamagedValuesFallBackToDefaults() {
        var policy = new QueuePolicy(5, true, LocalTime.of(22, 30), LocalTime.of(5, 45), true, 750 * MIB);
        assertEquals(policy, QueuePolicy.from(policy.toMap()));
        assertEquals(QueuePolicy.DEFAULT, QueuePolicy.from(Map.of()));
        assertEquals(QueuePolicy.DEFAULT, QueuePolicy.from(Map.of("queue.maxActive", "99")));
        assertEquals(QueuePolicy.DEFAULT, QueuePolicy.from(Map.of("schedule.start", "25:00")));
        assertThrows(IllegalArgumentException.class, () -> new QueuePolicy(3, true, LocalTime.NOON, LocalTime.NOON, false, 500 * MIB));
        assertThrows(IllegalArgumentException.class, () -> new QueuePolicy(3, false, LocalTime.NOON, LocalTime.MIDNIGHT, true, 10));
    }
}
