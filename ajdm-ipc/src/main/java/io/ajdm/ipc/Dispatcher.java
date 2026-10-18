package io.ajdm.ipc;

import com.google.gson.*;
import io.ajdm.core.CoreService;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit allowlisted service adapter. Never serializes engine requests or exception messages. */
public final class Dispatcher {
    private final CoreService core;
    public Dispatcher(CoreService core) { this.core = core; }
    public JsonObject dispatch(JsonObject request) {
        String id = "invalid";
        try {
            id = Protocol.string(request, "id", null);
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) { id = "invalid"; throw new IllegalArgumentException("Invalid request ID"); }
            if (Protocol.integer(request, "v", -1) != 1) throw new IllegalArgumentException("Unsupported version");
            String type = Protocol.string(request, "type", null);
            if (type == null || !request.has("payload") || !request.get("payload").isJsonObject()) throw new IllegalArgumentException("Invalid envelope");
            only(request, "v", "id", "type", "payload");
            JsonObject p = request.getAsJsonObject("payload"); var result = new JsonObject();
            switch (type) {
                case "shutdown" -> { only(p); result.addProperty("stopping", true); }
                case "ping" -> { only(p); result.addProperty("version", System.getProperty("ajdm.version", "0.1.0-SNAPSHOT")); }
                case "list" -> {
                    only(p); var rows = new JsonArray();
                    var queues = core.queueAssignments(); var limits = core.downloadSpeedLimits();
                    for (CoreService.Row row : core.list()) {
                        var item = new JsonObject(); item.addProperty("id", row.id().toString());
                        item.addProperty("state", row.state().name()); item.addProperty("writtenBytes", row.writtenBytes());
                        item.addProperty("durableBytes", row.durableBytes()); item.addProperty("totalBytes", row.totalBytes());
                        item.addProperty("target", row.target()); item.addProperty("resumeSupported", row.resumeSupported());
                        item.addProperty("kind", row.kind()); item.addProperty("title", row.title());
                        if (row.waiting() != null) item.addProperty("waiting", row.waiting());
                        if (row.error() != null) item.add("error", error(row.error()));
                        if (queues.containsKey(row.id())) item.addProperty("queue", queues.get(row.id()));
                        if (limits.containsKey(row.id())) item.addProperty("limitKiBps", limits.get(row.id()) / 1024);
                        rows.add(item);
                    }
                    result.add("downloads", rows);
                }
                case "offer" -> {
                    only(p, "url", "referer", "filename", "media", "sizeBytes", "formatId", "audioId");
                    long size = p.has("sizeBytes") && p.get("sizeBytes").isJsonPrimitive() ? p.get("sizeBytes").getAsLong() : -1;
                    try { core.offer(new CoreService.Offer(Protocol.string(p, "url", null), Protocol.string(p, "referer", null),
                            Protocol.string(p, "filename", null), Protocol.bool(p, "media", false), size,
                            Protocol.string(p, "formatId", null), Protocol.string(p, "audioId", null))); }
                    catch (IllegalArgumentException invalid) { throw new DownloadException(ErrorCode.INVALID_URL, "Invalid offered link"); }
                    result.addProperty("accepted", true);
                }
                case "segments" -> {
                    only(p, "downloadId");
                    var segments = core.segments(java.util.UUID.fromString(Protocol.string(p, "downloadId", "")));
                    result.addProperty("total", segments.total());
                    // At most 512 runs: the smallest gaps merge first, which a map 430 pixels wide cannot show anyway.
                    var ranges = new java.util.ArrayList<>(segments.saved());
                    while (ranges.size() > 512) {
                        int best = 0; long gap = Long.MAX_VALUE;
                        for (int i = 0; i + 1 < ranges.size(); i++) {
                            long g = ranges.get(i + 1).start() - ranges.get(i).endExclusive();
                            if (g < gap) { gap = g; best = i; }
                        }
                        ranges.set(best, new io.ajdm.model.ByteRange(ranges.get(best).start(), ranges.get(best + 1).endExclusive()));
                        ranges.remove(best + 1);
                    }
                    var list = new JsonArray();
                    for (var range : ranges) { var pair = new JsonArray(); pair.add(range.start()); pair.add(range.endExclusive()); list.add(pair); }
                    result.add("saved", list);
                    var connections = new JsonArray(); segments.connections().forEach(connections::add);
                    result.add("connections", connections);
                }
                case "takeOffers", "waitOffers" -> {
                    if (type.equals("takeOffers")) only(p); else only(p, "timeoutMs");
                    int timeout = Protocol.integer(p, "timeoutMs", 25_000);
                    if (timeout < 0 || timeout > 30_000) throw new IllegalArgumentException("Invalid timeout");
                    List<CoreService.Offer> offers;
                    if (type.equals("takeOffers")) offers = core.takeOffers();
                    else try { offers = core.waitOffers(timeout); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); offers = List.of(); }
                    var list = new JsonArray();
                    for (var offer : offers) {
                        var item = new JsonObject(); item.addProperty("url", offer.url()); item.addProperty("media", offer.media());
                        if (offer.referer() != null) item.addProperty("referer", offer.referer());
                        if (offer.filename() != null) item.addProperty("filename", offer.filename());
                        if (offer.sizeBytes() >= 0) item.addProperty("sizeBytes", offer.sizeBytes());
                        if (offer.formatId() != null) item.addProperty("formatId", offer.formatId());
                        if (offer.audioId() != null) item.addProperty("audioId", offer.audioId());
                        list.add(item);
                    }
                    result.add("offers", list);
                }
                case "prefetch" -> { only(p, "url"); core.prefetch(Protocol.string(p, "url", "")); result.addProperty("accepted", true); }
                case "queueStatus" -> {
                    only(p); var status = core.queueStatus(); var policy = status.policy();
                    result.addProperty("maxActive", policy.maxActive()); result.addProperty("scheduled", policy.scheduled());
                    result.addProperty("start", policy.start().toString()); result.addProperty("end", policy.end().toString());
                    result.addProperty("budgeted", policy.budgeted()); result.addProperty("dailyMiB", policy.dailyBytes() / (1024 * 1024));
                    result.addProperty("usedTodayBytes", status.usedToday());
                    var mainDays = new JsonArray(); policy.days().stream().sorted().forEach(day -> mainDays.add(day.name())); result.add("days", mainDays);
                    result.addProperty("limitKiBps", policy.speedLimit() / 1024);
                    if (status.holding() != null) result.addProperty("holding", status.holding());
                }
                case "configureQueue" -> {
                    only(p, "maxActive", "scheduled", "start", "end", "budgeted", "dailyMiB", "limitKiBps", "days");
                    java.time.LocalTime start, end;
                    try { start = java.time.LocalTime.parse(Protocol.string(p, "start", "23:00")); end = java.time.LocalTime.parse(Protocol.string(p, "end", "06:00")); }
                    catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Times must be HH:MM"); }
                    long dailyBytes = (long) Protocol.integer(p, "dailyMiB", 1024) * 1024 * 1024;
                    if (p.has("days")) core.configureQueue(Protocol.integer(p, "maxActive", 3), Protocol.bool(p, "scheduled", false), start, end,
                            Protocol.bool(p, "budgeted", false), dailyBytes, (long) Protocol.integer(p, "limitKiBps", 0) * 1024, days(p));
                    else if (p.has("limitKiBps")) core.configureQueue(Protocol.integer(p, "maxActive", 3), Protocol.bool(p, "scheduled", false), start, end,
                            Protocol.bool(p, "budgeted", false), dailyBytes, (long) Protocol.integer(p, "limitKiBps", 0) * 1024);
                    else core.configureQueue(Protocol.integer(p, "maxActive", 3), Protocol.bool(p, "scheduled", false), start, end,
                            Protocol.bool(p, "budgeted", false), dailyBytes);
                    result.addProperty("configured", true);
                }
                case "mediaCookies" -> {
                    only(p, "file", "browser"); String file = Protocol.string(p, "file", null), browser = Protocol.string(p, "browser", null);
                    if (file != null && browser != null) throw new DownloadException(ErrorCode.INVALID_REQUEST, "Choose one media session source");
                    if (browser != null) core.mediaBrowser(browser); else core.mediaCookies(file == null ? null : Path.of(file));
                    result.addProperty("configured", file != null || browser != null);
                }
                case "add" -> {
                    only(p, "url", "directory", "filename", "headers", "referer", "cookies", "conflict", "sha256", "connections", "adaptive", "start", "queue");
                    if (!Protocol.string(p, "queue", "default").equals("default")) {
                        throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Named queues not implemented");
                    }
                    String url = Protocol.string(p, "url", null); if (url == null) throw new IllegalArgumentException("Missing URL");
                    var headers = headers(p);
                    String referer = Protocol.string(p, "referer", null), cookies = Protocol.string(p, "cookies", null);
                    if (referer != null && headers.putIfAbsent("Referer", referer) != null) throw new IllegalArgumentException("Duplicate Referer");
                    if (cookies != null && headers.putIfAbsent("Cookie", cookies) != null) throw new IllegalArgumentException("Duplicate Cookie");
                    result.addProperty("downloadId", core.add(url,
                            Path.of(Protocol.string(p, "directory", Path.of(System.getProperty("user.home"), "Downloads").toString())),
                            Protocol.string(p, "filename", null), headers, policy(p, ConflictPolicy.RENAME),
                            Protocol.string(p, "sha256", null), Protocol.integer(p, "connections", 16), Protocol.bool(p, "adaptive", false),
                            !Protocol.bool(p, "start", true)).toString());
                }
                case "pause", "resume", "cancel", "restart" -> {
                    only(p, "downloadId", "headers", "conflict", "connections");
                    UUID download = UUID.fromString(Protocol.string(p, "downloadId", ""));
                    UUID next = download;
                    switch (type) {
                        case "pause" -> { only(p, "downloadId"); core.pause(download); }
                        case "cancel" -> { only(p, "downloadId"); core.cancel(download); }
                        case "resume" -> next = core.resume(download, headers(p), policy(p, null), p.has("connections") ? Protocol.integer(p, "connections", -1) : null);
                        case "restart" -> { only(p, "downloadId", "headers"); next = core.restart(download, headers(p)); }
                        default -> throw new AssertionError();
                    }
                    result.addProperty("downloadId", next.toString());
                }
                case "formats", "playlistFormats" -> {
                    if (type.equals("playlistFormats")) only(p, "url", "index", "maxHeight", "showAll", "preset", "formatId", "audioId", "preferModernCodecs");
                    else only(p, "url", "maxHeight", "showAll", "preset", "formatId", "audioId", "preferModernCodecs");
                    int ceiling = Protocol.integer(p, "maxHeight", 2160); boolean showAll = Protocol.bool(p, "showAll", false);
                    if (ceiling < 1 || ceiling > 4320) throw new IllegalArgumentException("Invalid ceiling");
                    String url = Protocol.string(p, "url", null); if (url == null) throw new IllegalArgumentException("Missing URL");
                    MediaInfo info = type.equals("playlistFormats") ? core.playlistFormats(url, Protocol.integer(p, "index", -1)) : core.formats(url); var visible = info.visible(ceiling, showAll); var table = new JsonArray();
                    for (MediaFormat format : visible) table.add(format(format));
                    result.addProperty("id", info.id()); result.addProperty("title", info.title()); result.addProperty("uploader", info.uploader());
                    result.addProperty("duration", info.duration()); result.add("formats", table);
                    result.addProperty("hiddenCount", info.formats().size() - visible.size()); result.addProperty("excludedCount", info.excludedFormats());
                    String preset = Protocol.string(p, "preset", null), chosen = Protocol.string(p, "formatId", null), audio = Protocol.string(p, "audioId", null);
                    if (preset != null && chosen != null || audio != null && chosen == null) throw new IllegalArgumentException("Invalid selection");
                    if (preset != null || chosen != null) {
                        var plan = preset != null ? MediaSelection.preset(info, MediaSelection.Preset.valueOf(preset), Protocol.bool(p, "preferModernCodecs", true))
                                : MediaSelection.custom(info, chosen, audio);
                        var selection = new JsonObject(); var selected = new JsonArray();
                        for (var format : plan.formats()) selected.add(format.formatId());
                        selection.add("formatIds", selected); selection.addProperty("container", plan.container());
                        selection.addProperty("requiresMux", plan.requiresMux()); selection.addProperty("sizeBytes", plan.sizeBytes());
                        selection.addProperty("approximateSize", plan.approximateSize()); result.add("selection", selection);
                    }
                }
                case "addMedia" -> {
                    only(p, "url", "formatId", "audioId", "preset", "preferModernCodecs", "directory", "filename", "conflict", "connections", "adaptive", "start");
                    String url = Protocol.string(p, "url", null); if (url == null) throw new IllegalArgumentException("Missing URL");
                    String preset = Protocol.string(p, "preset", null);
                    var added = core.addMedia(url, Protocol.string(p, "formatId", null), Protocol.string(p, "audioId", null), preset == null ? null : MediaSelection.Preset.valueOf(preset),
                            Protocol.bool(p, "preferModernCodecs", true), Path.of(Protocol.string(p, "directory", Path.of(System.getProperty("user.home"), "Downloads").toString())),
                            Protocol.string(p, "filename", null), policy(p, ConflictPolicy.RENAME), Protocol.integer(p, "connections", 8), Protocol.bool(p, "adaptive", false),
                            !Protocol.bool(p, "start", true));
                    result.addProperty("downloadId", added.id().toString()); result.addProperty("formatId", added.formatId()); result.addProperty("kind", added.kind());
                    result.addProperty("sizeBytes", added.sizeBytes()); result.addProperty("approximateSize", added.approximateSize());
                }
                case "playlist" -> {
                    only(p, "url", "offset", "limit"); var info = core.playlist(Protocol.string(p, "url", ""));
                    int offset = offset(p), limit = limit(p); result.addProperty("id", info.id()); result.addProperty("title", info.title()); result.addProperty("totalEntries", info.entries().size());
                    var rows = new JsonArray(); for (var entry : info.entries().stream().skip(offset).limit(limit).toList()) {
                        var row = new JsonObject(); row.addProperty("index", entry.index()); row.addProperty("id", entry.id()); row.addProperty("title", entry.title()); row.addProperty("duration", entry.duration());
                        if (entry.unavailable() != null) row.add("error", error(entry.unavailable())); rows.add(row);
                    } result.add("entries", rows); result.addProperty("offset", offset);
                }
                case "addPlaylist" -> {
                    only(p, "url", "items", "preset", "choices", "directory", "conflict", "connections", "preferModernCodecs");
                    var choices = new HashMap<Integer, PlaylistSelection.Choice>();
                    if (p.has("choices")) {
                        if (!p.get("choices").isJsonArray() || p.getAsJsonArray("choices").size() > 1000) throw new IllegalArgumentException("Invalid choices");
                        for (var element : p.getAsJsonArray("choices")) {
                            if (!element.isJsonObject()) throw new IllegalArgumentException("Invalid choice"); var c = element.getAsJsonObject(); only(c, "index", "preset", "formatId", "audioId");
                            int index = Protocol.integer(c, "index", -1); var choice = new PlaylistSelection.Choice(Protocol.string(c, "preset", null), Protocol.string(c, "formatId", null), Protocol.string(c, "audioId", null));
                            if (index < 1 || choices.putIfAbsent(index, choice) != null) throw new IllegalArgumentException("Duplicate choice");
                        }
                    }
                    var playlistId = core.addPlaylist(Protocol.string(p, "url", ""), Protocol.string(p, "items", "all"), new PlaylistSelection.Choice(Protocol.string(p, "preset", "BEST"), null, null), choices,
                            Path.of(Protocol.string(p, "directory", Path.of(System.getProperty("user.home"), "Downloads").toString())), policy(p, ConflictPolicy.RENAME), Protocol.integer(p, "connections", 8), Protocol.bool(p, "preferModernCodecs", true));
                    result.addProperty("downloadId", playlistId.toString());
                }
                case "playlistStatus" -> {
                    only(p, "downloadId", "offset", "limit"); var playlistId = UUID.fromString(Protocol.string(p, "downloadId", "")); var job = core.playlistJob(playlistId); var items = core.playlistItems(playlistId);
                    var state = core.playlistState(job); result.addProperty("downloadId", playlistId.toString()); result.addProperty("title", job.title()); result.addProperty("state", state.name());
                    result.addProperty("selected", items.size()); if (job.error() != null) result.add("error", error(job.error()));
                    for (var itemState : PlaylistItem.State.values()) result.addProperty(itemState.name().toLowerCase(Locale.ROOT), items.stream().filter(i -> i.state() == itemState).count());
                    int offset = offset(p), limit = limit(p); var rows = new JsonArray();
                    for (var item : items.stream().skip(offset).limit(limit).toList()) {
                        var row = new JsonObject(); row.addProperty("index", item.index()); row.addProperty("title", item.title()); row.addProperty("id", item.mediaId()); row.addProperty("duration", item.duration());
                        row.addProperty("preset", item.preset()); row.addProperty("formatId", item.formatId()); row.addProperty("audioId", item.audioId());
                        row.addProperty("state", state == DownloadState.PAUSED && !item.terminal() ? "PAUSED" : item.state().name());
                        if (item.childId() != null) row.addProperty("downloadId", item.childId().toString()); if (item.error() != null) row.add("error", error(item.error())); rows.add(row);
                    } result.add("entries", rows); result.addProperty("offset", offset);
                }
                case "remove" -> {
                    only(p, "downloadId", "deleteFiles");
                    UUID download = UUID.fromString(Protocol.string(p, "downloadId", ""));
                    core.remove(download, Protocol.bool(p, "deleteFiles", false)); result.addProperty("downloadId", download.toString());
                }
                case "setSpeedLimit" -> {
                    only(p, "downloadId", "limitKiBps");
                    int kib = Protocol.integer(p, "limitKiBps", 0); if (kib < 0) throw new IllegalArgumentException("Invalid limit");
                    core.downloadSpeedLimit(java.util.UUID.fromString(Protocol.string(p, "downloadId", "")), (long) kib * 1024);
                    result.addProperty("configured", true);
                }
                case "queues" -> {
                    only(p); var list = new JsonArray();
                    for (var queue : core.namedQueues()) list.add(queueJson(queue.name(), queue.schedule()));
                    result.add("queues", list);
                }
                case "saveQueue" -> {
                    only(p, "name", "scheduled", "start", "end", "days");
                    java.time.LocalTime start, end;
                    try { start = java.time.LocalTime.parse(Protocol.string(p, "start", "23:00")); end = java.time.LocalTime.parse(Protocol.string(p, "end", "06:00")); }
                    catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Times must be HH:MM"); }
                    core.saveQueue(new CoreService.NamedQueue(Protocol.string(p, "name", ""), new io.ajdm.core.Schedule(Protocol.bool(p, "scheduled", false), start, end, days(p))));
                    result.addProperty("configured", true);
                }
                case "deleteQueue" -> { only(p, "name"); core.deleteQueue(Protocol.string(p, "name", "")); result.addProperty("deleted", true); }
                case "assignQueue" -> {
                    only(p, "downloadIds", "queue");
                    if (!p.has("downloadIds") || !p.get("downloadIds").isJsonArray() || p.getAsJsonArray("downloadIds").size() > 1000) throw new IllegalArgumentException("Invalid downloads");
                    var ids = new java.util.ArrayList<java.util.UUID>();
                    for (var element : p.getAsJsonArray("downloadIds")) ids.add(java.util.UUID.fromString(element.getAsString()));
                    core.assignQueue(ids, Protocol.string(p, "queue", "")); result.addProperty("assigned", ids.size());
                }
                case "startQueue", "stopQueue" -> {
                    only(p, "queue"); String queue = Protocol.string(p, "queue", "");
                    result.addProperty("count", type.equals("startQueue") ? core.startQueue(queue) : core.stopQueue(queue));
                }
                case "subscribe" -> throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Unsupported feature");
                default -> throw new IllegalArgumentException("Unknown command");
            }
            var response = envelope(id); response.addProperty("ok", true); response.add("payload", result); return response;
        } catch (DownloadException e) { return failure(id, e.code()); }
        catch (IllegalArgumentException | ArithmeticException e) { return failure(id, ErrorCode.INVALID_REQUEST); }
        catch (IllegalStateException e) { return failure(id, ErrorCode.INVALID_STATE); }
        catch (IOException e) { return failure(id, ErrorCode.STATE_STORE_ERROR); }
    }
    private static java.util.Set<java.time.DayOfWeek> days(JsonObject p) {
        var days = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        if (!p.has("days")) return days;
        if (!p.get("days").isJsonArray() || p.getAsJsonArray("days").size() > 7) throw new IllegalArgumentException("Invalid days");
        for (var day : p.getAsJsonArray("days")) days.add(java.time.DayOfWeek.valueOf(day.getAsString().toUpperCase(java.util.Locale.ROOT)));
        return days;
    }
    private static JsonObject queueJson(String name, io.ajdm.core.Schedule schedule) {
        var item = new JsonObject(); item.addProperty("name", name); item.addProperty("scheduled", schedule.enabled());
        item.addProperty("start", schedule.start().toString()); item.addProperty("end", schedule.end().toString());
        var days = new JsonArray(); schedule.days().stream().sorted().forEach(day -> days.add(day.name())); item.add("days", days);
        return item;
    }
    private static int offset(JsonObject p) { int value = Protocol.integer(p, "offset", 0); if (value < 0 || value > 1000) throw new IllegalArgumentException("Invalid offset"); return value; }
    private static int limit(JsonObject p) { int value = Protocol.integer(p, "limit", 100); if (value < 1 || value > 100) throw new IllegalArgumentException("Invalid limit"); return value; }
    private static JsonObject format(MediaFormat f) {
        var row = new JsonObject(); row.addProperty("formatId", f.formatId()); row.addProperty("container", f.ext()); row.addProperty("kind", f.kind().name());
        row.addProperty("width", f.width()); row.addProperty("height", f.height()); row.addProperty("fps", f.fps());
        row.addProperty("videoCodec", f.vcodec()); row.addProperty("audioCodec", f.acodec()); row.addProperty("bitrateKbps", f.tbrKbps()); row.addProperty("audioBitrateKbps", f.abrKbps());
        row.addProperty("sizeBytes", f.sizeBytes()); row.addProperty("approximateSize", f.approximateSize()); row.addProperty("hdr", f.hdr());
        row.addProperty("fragmented", f.fragmented());
        row.addProperty("delivery", f.hls() ? "hls" : f.dashPlaylist() != null ? "dash" : "file");
        row.addProperty("note", f.note()); row.addProperty("language", f.language()); return row;
    }
    private static ConflictPolicy policy(JsonObject p, ConflictPolicy fallback) {
        String name = Protocol.string(p, "conflict", null); return name == null ? fallback : ConflictPolicy.valueOf(name);
    }
    private static Map<String, String> headers(JsonObject p) {
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (p.has("headers")) {
            if (!p.get("headers").isJsonObject() || p.getAsJsonObject("headers").size() > 64) throw new IllegalArgumentException("Invalid headers");
            for (var entry : p.getAsJsonObject("headers").entrySet()) {
                String value = Protocol.string(p.getAsJsonObject("headers"), entry.getKey(), null);
                if (value == null || headers.putIfAbsent(entry.getKey(), value) != null) throw new IllegalArgumentException("Invalid headers");
            }
        }
        return headers;
    }
    private static void only(JsonObject p, String... allowed) {
        var keys = Set.of(allowed); if (!keys.containsAll(p.keySet())) throw new IllegalArgumentException("Unknown field");
    }
    static JsonObject envelope(String id) {
        var response = new JsonObject(); response.addProperty("v", 1); response.addProperty("id", id); return response;
    }
    public static JsonObject failure(String id, ErrorCode code) {
        var response = envelope(id); response.addProperty("ok", false); response.add("error", error(code)); return response;
    }
    private static JsonObject error(ErrorCode code) {
        var error = new JsonObject(); error.addProperty("code", code.publicCode());
        error.addProperty("message", code.message()); error.addProperty("action", code.action()); return error;
    }
}
