package io.ajdm.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueTextTest {
    private static JsonObject status(boolean scheduled, boolean budgeted, String holding) {
        var s = new JsonObject(); s.addProperty("usedTodayBytes", 300L * 1024 * 1024); s.addProperty("budgeted", budgeted); s.addProperty("dailyMiB", 500);
        s.addProperty("scheduled", scheduled); s.addProperty("start", "23:00"); s.addProperty("end", "06:00");
        if (holding != null) s.addProperty("holding", holding);
        return s;
    }
    @Test void theStatusLineSaysWhatTheQueueWaitsFor() {
        assertEquals("Data today: " + Display.mib(300L * 1024 * 1024), MainWindow.queueText(status(false, false, null)));
        assertTrue(MainWindow.queueText(status(true, true, "schedule")).endsWith("of 500 MiB · Window 23:00–06:00 · Waiting for the download window"));
        assertTrue(MainWindow.queueText(status(false, true, "budget")).contains("Daily data budget reached"));
    }
    @Test void queuedRowsShowTheirReason() {
        var reading = new RateTracker.Reading(0, -1, 0, List.of());
        assertEquals("Queued · schedule", new DownloadRow(UUID.randomUUID(), "QUEUED", 0, 0, 1, "/tmp/a", true, "", reading, "FILE", "a", "schedule").stateLabel());
        assertEquals("Queued · data budget", new DownloadRow(UUID.randomUUID(), "QUEUED", 0, 0, 1, "/tmp/a", true, "", reading, "FILE", "a", "budget").stateLabel());
        assertEquals("Queued", new DownloadRow(UUID.randomUUID(), "QUEUED", 0, 0, 1, "/tmp/a", true, "", reading, "FILE", "a", "slot").stateLabel());
        assertEquals("Downloading", new DownloadRow(UUID.randomUUID(), "DOWNLOADING", 0, 0, 1, "/tmp/a", true, "", reading, "FILE", "a").stateLabel());
    }
}
