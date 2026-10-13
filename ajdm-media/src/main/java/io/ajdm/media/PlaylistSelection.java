package io.ajdm.media;

import java.util.*;

public final class PlaylistSelection {
    private PlaylistSelection() {}
    public record Choice(String preset, String formatId, String audioId) {
        public Choice {
            if ((preset == null) == (formatId == null) || audioId != null && formatId == null) throw new IllegalArgumentException("Choose preset or formats");
            if (preset != null) MediaSelection.Preset.valueOf(preset);
            for (String id : new String[]{formatId, audioId}) if (id != null && (id.isBlank() || id.length() > 256 || id.codePoints().anyMatch(Character::isISOControl))) throw new IllegalArgumentException("Invalid format identity");
        }
        public MediaSelection.Plan plan(MediaInfo info, boolean modern) { return preset != null ? MediaSelection.preset(info, MediaSelection.Preset.valueOf(preset), modern) : MediaSelection.custom(info, formatId, audioId); }
    }
    public static List<Integer> indices(String spec, int count) {
        if (count < 0 || count > 1000 || spec == null || spec.length() > 10000) throw new IllegalArgumentException("Invalid item selection");
        var result = new TreeSet<Integer>();
        if (spec.equals("all")) { for (int i = 1; i <= count; i++) result.add(i); }
        else for (String range : spec.split(",", -1)) {
            if (!range.matches("[0-9]{1,4}(-[0-9]{1,4})?")) throw new IllegalArgumentException("Invalid item range");
            String[] values = range.split("-"); int start = Integer.parseInt(values[0]), end = values.length == 1 ? start : Integer.parseInt(values[1]);
            if (start < 1 || end < start || end > count) throw new IllegalArgumentException("Item outside playlist");
            for (int i = start; i <= end; i++) result.add(i);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Select at least one entry"); return List.copyOf(result);
    }
}
