package io.ajdm.media;

import com.google.gson.*;
import io.ajdm.model.*;
import java.net.URI;
import java.util.*;

/** Flat inventory only; unusable entries keep their position and a catalog-owned reason. */
public final class PlaylistParser {
    public PlaylistInfo parse(byte[] bytes, URI page) throws DownloadException {
        try {
            JsonObject root = MediaParser.json(bytes);
            if (MediaParser.bool(root, "has_drm")) throw new DownloadException(ErrorCode.DRM_PROTECTED, "Protected playlist");
            if (!Set.of("playlist", "multi_video").contains(MediaParser.string(root, "_type", ""))) throw new IllegalArgumentException("Not a playlist");
            String id = MediaParser.string(root, "id", null); var rows = root.get("entries");
            if (rows == null || !rows.isJsonArray() || rows.getAsJsonArray().size() > 1000) throw new IllegalArgumentException("Invalid entry list");
            var entries = new ArrayList<PlaylistInfo.Entry>();
            for (JsonElement row : rows.getAsJsonArray()) {
                int index = entries.size() + 1;
                if (row.isJsonNull()) { entries.add(new PlaylistInfo.Entry(index, "", "Unavailable entry", null, null, ErrorCode.EXTRACTION_FAILED)); continue; }
                if (!row.isJsonObject()) throw new IllegalArgumentException("Invalid entry"); var e = row.getAsJsonObject();
                String entryId = MediaParser.string(e, "id", ""), title = MediaParser.string(e, "title", entryId);
                ErrorCode reason = null; String availability = MediaParser.string(e, "availability", "unknown");
                if (Set.of("private", "premium_only", "subscriber_only", "needs_auth").contains(availability)) reason = ErrorCode.AUTH_REQUIRED;
                if (MediaParser.bool(e, "has_drm")) reason = ErrorCode.DRM_PROTECTED;
                if (Set.of("playlist", "multi_video").contains(MediaParser.string(e, "_type", "url"))) reason = ErrorCode.FEATURE_UNAVAILABLE;
                URI uri = null;
                String url = MediaParser.string(e, "webpage_url", MediaParser.string(e, "url", null));
                if (url == null && e.has("formats")) url = page.toString();
                if ((url == null || !url.startsWith("https://") && !url.startsWith("http://")) && MediaParser.string(e, "ie_key", "").equalsIgnoreCase("Youtube") && entryId.matches("[a-zA-Z0-9_-]{11}")) url = "https://www.youtube.com/watch?v=" + entryId;
                try { if (url == null) throw new IllegalArgumentException("No page"); uri = URI.create(url); new DownloadSource(uri, Map.of()); }
                catch (IllegalArgumentException invalid) { uri = null; if (reason == null) reason = ErrorCode.INVALID_URL; }
                entries.add(new PlaylistInfo.Entry(index, entryId, title, MediaParser.number(e, "duration"), uri, reason));
            }
            return new PlaylistInfo(id, MediaParser.string(root, "title", id), page, entries);
        } catch (DownloadException e) { throw e; }
        catch (java.io.IOException | IllegalArgumentException | IllegalStateException | ArithmeticException e) { throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Invalid playlist extraction"); }
    }
}
