package io.ajdm.core;

import java.time.LocalTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * When queued downloads may run: at most {@code maxActive} transfers, inside an optional daily time window (which may
 * cross midnight, like a 23:00-06:00 night bundle), and while today's received bytes stay under an optional budget.
 * Stored as plain key/value settings; unreadable values fall back to the defaults (3 transfers, no window, no budget).
 */
public record QueuePolicy(int maxActive, boolean scheduled, LocalTime start, LocalTime end, boolean budgeted, long dailyBytes) {
    static final QueuePolicy DEFAULT = new QueuePolicy(3, false, LocalTime.of(23, 0), LocalTime.of(6, 0), false, 1024L * 1024 * 1024);

    public QueuePolicy {
        if (maxActive < 1 || maxActive > 10) throw new IllegalArgumentException("Simultaneous downloads must be 1 to 10");
        if (start.equals(end)) throw new IllegalArgumentException("The schedule window needs different start and end times");
        if (dailyBytes < 1024L * 1024 || dailyBytes > 1024L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Daily budget must be 1 MiB to 1 TiB");
        start = start.withSecond(0).withNano(0); end = end.withSecond(0).withNano(0);
    }

    public enum Wait { SLOT, SCHEDULE, BUDGET }

    /** Whether {@code time} falls inside the window; a window whose end precedes its start runs across midnight. */
    boolean windowOpen(LocalTime time) {
        if (!scheduled) return true;
        return start.isBefore(end) ? !time.isBefore(start) && time.isBefore(end) : !time.isBefore(start) || time.isBefore(end);
    }
    boolean budgetLeft(long usedToday) { return !budgeted || usedToday < dailyBytes; }

    /** Why nothing new may start now, or null when the schedule and budget allow it (slots are checked separately). */
    Wait blocked(LocalTime time, long usedToday) {
        if (!budgetLeft(usedToday)) return Wait.BUDGET;
        if (!windowOpen(time)) return Wait.SCHEDULE;
        return null;
    }

    static QueuePolicy from(Map<String, String> values) {
        try {
            return new QueuePolicy(Integer.parseInt(values.getOrDefault("queue.maxActive", "3")),
                    Boolean.parseBoolean(values.getOrDefault("schedule.enabled", "false")),
                    LocalTime.parse(values.getOrDefault("schedule.start", "23:00")), LocalTime.parse(values.getOrDefault("schedule.end", "06:00")),
                    Boolean.parseBoolean(values.getOrDefault("budget.enabled", "false")),
                    Long.parseLong(values.getOrDefault("budget.dailyBytes", Long.toString(DEFAULT.dailyBytes()))));
        } catch (RuntimeException invalid) { return DEFAULT; }
    }

    Map<String, String> toMap() {
        var values = new TreeMap<String, String>();
        values.put("queue.maxActive", Integer.toString(maxActive));
        values.put("schedule.enabled", Boolean.toString(scheduled));
        values.put("schedule.start", start.toString()); values.put("schedule.end", end.toString());
        values.put("budget.enabled", Boolean.toString(budgeted)); values.put("budget.dailyBytes", Long.toString(dailyBytes));
        return values;
    }
}
