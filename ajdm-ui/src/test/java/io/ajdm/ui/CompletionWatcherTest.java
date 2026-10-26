package io.ajdm.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompletionWatcherTest {
    private static DownloadRow row(UUID id, String state, String name) {
        return new DownloadRow(id, state, 0, 0, 100, "/tmp/" + name, true, state.equals("FAILED") ? "AJDM-3002: limit" : "",
                new RateTracker.Reading(0, -1, 0, List.of()), "FILE", name);
    }

    @Test void onlyDownloadsSeenUnfinishedThatThenFinishOrFailNotify() {
        var watcher = new CompletionWatcher();
        UUID old = UUID.randomUUID(), movie = UUID.randomUUID(), iso = UUID.randomUUID(), merge = UUID.randomUUID();
        assertTrue(watcher.update(List.of(row(old, "COMPLETED", "old.mp4"), row(movie, "DOWNLOADING", "movie.mp4"),
                row(iso, "DOWNLOADING", "debian.iso"), row(merge, "MERGING", "show.mp4"))).isEmpty(), "Already-finished downloads stay quiet");
        var events = watcher.update(List.of(row(old, "COMPLETED", "old.mp4"), row(movie, "COMPLETED", "movie.mp4"),
                row(iso, "FAILED", "debian.iso"), row(merge, "COMPLETED", "show.mp4")));
        assertEquals(3, events.size());
        assertTrue(events.stream().anyMatch(e -> e.id().equals(movie) && e.completed()));
        assertTrue(events.stream().anyMatch(e -> e.id().equals(iso) && !e.completed() && e.error().startsWith("AJDM-3002")));
        assertTrue(events.stream().anyMatch(e -> e.id().equals(merge) && e.completed()), "A remux finishing counts as complete");
        assertTrue(watcher.update(List.of(row(movie, "COMPLETED", "movie.mp4"))).isEmpty(), "Each finish notifies once");
    }

    @Test void rowsMissingWhileTheCoreIsUnreachableComeBackWithoutFalseAlarms() {
        var watcher = new CompletionWatcher(); UUID id = UUID.randomUUID();
        watcher.update(List.of(row(id, "PAUSED", "a.bin")));
        assertTrue(watcher.update(List.of()).isEmpty());
        assertTrue(watcher.update(List.of(row(id, "PAUSED", "a.bin"))).isEmpty());
        assertEquals(1, watcher.update(List.of(row(id, "COMPLETED", "a.bin"))).size(), "Its later finish still notifies");
    }
}
