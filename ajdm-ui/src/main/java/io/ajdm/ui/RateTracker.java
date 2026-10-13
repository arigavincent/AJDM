package io.ajdm.ui;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rates come only from observed written-byte deltas, never file size or configured workers. */
final class RateTracker {
    record Reading(double bytesPerSecond, long etaSeconds, double fraction, List<Double> history) { }
    private record Point(long bytes, long nanos) { }
    private static final class Window {
        final ArrayDeque<Point> points = new ArrayDeque<>();
        final ArrayDeque<Double> history = new ArrayDeque<>();
        String state = "";
    }
    private final Map<UUID, Window> windows = new HashMap<>();
    Reading sample(UUID id, String state, long bytes, long total, long now) {
        var window = windows.computeIfAbsent(id, ignored -> new Window());
        var last = window.points.peekLast();
        boolean downloading = state.equals("DOWNLOADING");
        if (!downloading || !window.state.equals("DOWNLOADING") || last != null &&
                (bytes < last.bytes() || now <= last.nanos() || now - last.nanos() > 15_000_000_000L)) window.points.clear();
        window.state = state;
        double rate = 0;
        if (downloading) {
            window.points.addLast(new Point(bytes, now));
            while (window.points.size() > 2 && now - window.points.peekFirst().nanos() > 5_000_000_000L) window.points.removeFirst();
            var first = window.points.peekFirst();
            if (now > first.nanos()) rate = Math.max(0, (bytes - first.bytes()) / ((now - first.nanos()) / 1e9));
        }
        window.history.addLast(rate);
        while (window.history.size() > 60) window.history.removeFirst();
        double fraction = state.equals("COMPLETED") ? 1 : total > 0 ? Math.clamp((double) bytes / total, 0, 1) : -1;
        long eta = downloading && rate > 0 && total > 0 ? (long) Math.ceil(Math.max(0, total - bytes) / rate) : -1;
        return new Reading(rate, eta, fraction, List.copyOf(window.history));
    }
    void retain(Set<UUID> ids) { windows.keySet().retainAll(ids); }
    void clear() { windows.clear(); }
}
