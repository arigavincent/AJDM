package io.ajdm.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns successive download lists into "finished" and "failed" events: only a download seen unfinished that then
 * completes or fails produces one. Downloads already finished when AJDM opens, or rows that vanish while the core is
 * unreachable and come back unchanged, never notify.
 */
final class CompletionWatcher {
    record Event(UUID id, String name, boolean completed, String error) {}

    private final Map<UUID, String> states = new HashMap<>();

    List<Event> update(List<DownloadRow> rows) {
        var events = new ArrayList<Event>();
        for (var row : rows) {
            String previous = states.put(row.id(), row.state());
            if (previous == null || terminal(previous)) continue;
            if (row.state().equals("COMPLETED")) events.add(new Event(row.id(), row.name(), true, ""));
            else if (row.state().equals("FAILED")) events.add(new Event(row.id(), row.name(), false, row.error()));
        }
        return events;
    }

    private static boolean terminal(String state) { return state.equals("COMPLETED") || state.equals("FAILED") || state.equals("CANCELED"); }
}
