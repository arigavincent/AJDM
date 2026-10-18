package io.ajdm.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleTest {
    // 16 October 2026 is a Friday.
    private static LocalDateTime at(int day, int hour) { return LocalDateTime.of(2026, 10, day, hour, 0); }

    @Test void aNightWindowBelongsToTheDayItStarts() {
        var fridayNights = new Schedule(true, LocalTime.of(23, 0), LocalTime.of(6, 0), Set.of(DayOfWeek.FRIDAY));
        assertTrue(fridayNights.open(at(16, 23)), "Friday 23:00");
        assertTrue(fridayNights.open(at(17, 2)), "Saturday 02:00 is still Friday night");
        assertFalse(fridayNights.open(at(17, 23)), "Saturday night is not chosen");
        assertFalse(fridayNights.open(at(16, 2)), "Friday 02:00 belongs to Thursday night");
        var weekdays = new Schedule(true, LocalTime.of(9, 0), LocalTime.of(17, 0), Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));
        assertTrue(weekdays.open(at(19, 10))); assertFalse(weekdays.open(at(20, 10))); assertFalse(weekdays.open(at(19, 18)));
        assertTrue(new Schedule(true, LocalTime.of(9, 0), LocalTime.of(17, 0), Set.of()).open(at(18, 10)), "No days chosen: every day");
        assertTrue(new Schedule(false, LocalTime.of(9, 0), LocalTime.of(17, 0), Set.of(DayOfWeek.MONDAY)).open(at(18, 3)), "Off: always open");
    }

    @Test void theMainQueueKeepsItsDays() {
        var policy = new QueuePolicy(3, true, LocalTime.of(23, 0), LocalTime.of(6, 0), false, 500L << 20, 0, Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        var restored = QueuePolicy.from(policy.toMap());
        assertEquals(policy, restored);
        assertEquals(QueuePolicy.Wait.SCHEDULE, restored.blocked(at(16, 23), 0));
        assertNull(restored.blocked(at(17, 23), 0));
        assertEquals(Set.of(), QueuePolicy.from(Map.of()).days());
    }
}
