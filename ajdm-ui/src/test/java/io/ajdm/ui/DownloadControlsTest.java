package io.ajdm.ui;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadControlsTest {
    private DownloadRow row(String state, String errorCode) {
        var row = new JsonObject(); row.addProperty("id", UUID.randomUUID().toString()); row.addProperty("state", state);
        row.addProperty("writtenBytes", 0); row.addProperty("durableBytes", 0); row.addProperty("totalBytes", 100);
        row.addProperty("target", "/tmp/fixture.bin"); row.addProperty("resumeSupported", true);
        if (errorCode != null) { var error = new JsonObject(); error.addProperty("code", errorCode); row.add("error", error); }
        return DownloadRow.parse(row, new RateTracker(), System.nanoTime());
    }
    @Test void actionsFollowEverySupportedState() {
        for (String state : new String[]{"QUEUED", "PROBING", "EXTRACTING", "DOWNLOADING", "MERGING", "VERIFYING"}) {
            var row = row(state, null); assertTrue(row.active()); assertFalse(row.removable()); assertFalse(row.resumable()); assertFalse(row.terminal());
        }
        for (String state : new String[]{"PAUSED", "FAILED"}) {
            var row = row(state, null); assertFalse(row.active()); assertTrue(row.removable()); assertTrue(row.resumable()); assertFalse(row.terminal());
        }
        for (String state : new String[]{"COMPLETED", "CANCELED"}) {
            var row = row(state, null); assertTrue(row.removable()); assertTrue(row.terminal()); assertFalse(row.resumable()); assertFalse(row.active());
        }
    }
    @Test void changedSourceOffersRestartInsteadOfUnsafeResume() {
        var changed = row("PAUSED", "AJDM-3001"); assertTrue(changed.restartable()); assertFalse(changed.resumable());
        assertFalse(row("PAUSED", "AJDM-2003").restartable()); assertFalse(row("COMPLETED", "AJDM-3001").restartable());
    }
}
