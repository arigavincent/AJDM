package io.ajdm.core;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A queue's download window, like IDM's scheduler: between {@code start} and {@code end} (a window whose end precedes
 * its start runs across midnight) on the chosen days. A night window belongs to the day it starts, so a Friday
 * 23:00-06:00 window still runs at 02:00 on Saturday. No days chosen means every day.
 */
public record Schedule(boolean enabled, LocalTime start, LocalTime end, Set<DayOfWeek> days) {
    public Schedule {
        if (start.equals(end)) throw new IllegalArgumentException("The schedule window needs different start and end times");
        start = start.withSecond(0).withNano(0); end = end.withSecond(0).withNano(0);
        days = days.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
    }
    boolean open(LocalDateTime now) {
        if (!enabled) return true;
        LocalTime time = now.toLocalTime();
        boolean crosses = !start.isBefore(end);
        boolean inside = crosses ? !time.isBefore(start) || time.isBefore(end) : !time.isBefore(start) && time.isBefore(end);
        if (!inside) return false;
        DayOfWeek startedOn = crosses && time.isBefore(end) ? now.getDayOfWeek().minus(1) : now.getDayOfWeek();
        return days.isEmpty() || days.contains(startedOn);
    }
    /** "MONDAY,FRIDAY", or "" for every day. */
    static String daysText(Set<DayOfWeek> days) {
        return days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }
    static Set<DayOfWeek> parseDays(String text) {
        if (text == null || text.isBlank()) return Set.of();
        var days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : text.split(",")) if (!part.isBlank()) days.add(DayOfWeek.valueOf(part.strip().toUpperCase(Locale.ROOT)));
        return days;
    }
}
