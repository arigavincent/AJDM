package io.ajdm.ui;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

record DownloadRow(UUID id, String state, long written, long durable, long total, String target,
                   boolean resumeSupported, String error, RateTracker.Reading reading, String kind, String title) {
    DownloadRow(UUID id, String state, long written, long durable, long total, String target,
                boolean resumeSupported, String error, RateTracker.Reading reading) {
        this(id, state, written, durable, total, target, resumeSupported, error, reading, "FILE", Path.of(target).getFileName().toString());
    }
    static final Set<String> ACTIVE = Set.of("QUEUED", "PROBING", "EXTRACTING", "DOWNLOADING", "MERGING", "VERIFYING");
    static DownloadRow parse(JsonObject row, RateTracker tracker, long now) {
        var id = UUID.fromString(row.get("id").getAsString());
        String state = row.get("state").getAsString();
        long written = row.get("writtenBytes").getAsLong(), total = row.get("totalBytes").getAsLong();
        String error = "";
        if (row.has("error")) {
            String code = row.getAsJsonObject("error").get("code").getAsString();
            // Display only this client's known catalog, never a remote/raw exception or source URL.
            for (var known : io.ajdm.model.ErrorCode.values()) if (known.publicCode().equals(code)) error = known.userMessage();
        }
        return new DownloadRow(id, state, written, row.get("durableBytes").getAsLong(), total,
                row.get("target").getAsString(), row.get("resumeSupported").getAsBoolean(), error,
                tracker.sample(id, state, written, total, now), row.has("kind") ? row.get("kind").getAsString() : "FILE",
                row.has("title") ? row.get("title").getAsString() : Path.of(row.get("target").getAsString()).getFileName().toString());
    }
    String name() { return title; }
    boolean active() { return ACTIVE.contains(state); }
    boolean resumable() { return (state.equals("PAUSED") || state.equals("FAILED")) && !restartable(); }
    boolean terminal() { return state.equals("COMPLETED") || state.equals("CANCELED"); }
    boolean removable() { return Set.of("PAUSED", "FAILED", "COMPLETED", "CANCELED").contains(state); }
    boolean restartable() { return state.equals("PAUSED") && error.startsWith(io.ajdm.model.ErrorCode.SOURCE_CHANGED.publicCode() + ":"); }
}
