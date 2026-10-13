package io.ajdm.media;

import io.ajdm.model.*;
import java.net.URI;
import java.util.*;

public record PlaylistInfo(String id, String title, URI page, List<Entry> entries) {
    public record Entry(int index, String id, String title, Double duration, URI page, ErrorCode unavailable) {
        public Entry {
            if (index < 1 || index > 1000 || id == null || id.length() > 256 || title == null || title.length() > 4096
                    || duration != null && (!Double.isFinite(duration) || duration < 0)) throw new IllegalArgumentException("Invalid playlist entry");
            if (page != null) new DownloadSource(page, Map.of());
            if (page == null && unavailable == null) throw new IllegalArgumentException("Entry has no page");
        }
        @Override public String toString() { return "PlaylistEntry[index=" + index + ", page=redacted]"; }
    }
    public PlaylistInfo {
        if (id == null || id.isBlank() || id.length() > 256 || title == null || title.length() > 4096 || entries.size() > 1000) throw new IllegalArgumentException("Invalid playlist");
        new DownloadSource(page, Map.of()); entries = List.copyOf(entries);
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).index() != i + 1) throw new IllegalArgumentException("Invalid playlist order");
    }
    @Override public String toString() { return "PlaylistInfo[id=" + id + ", entries=" + entries.size() + ", page=redacted]"; }
}
