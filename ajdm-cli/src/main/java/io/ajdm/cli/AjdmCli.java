package io.ajdm.cli;

import com.google.gson.*;
import io.ajdm.core.CoreService;
import io.ajdm.engine.AjdmEngine;
import io.ajdm.ipc.*;
import io.ajdm.model.*;
import io.ajdm.media.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/** Production CLI: daemon is the composition root; all other commands are socket clients. */
@Command(name = "ajdm", mixinStandardHelpOptions = true, version = "AJDM 0.1.0-SNAPSHOT",
        description = "AJDM's own download engine and private local core.")
public final class AjdmCli implements Callable<Integer> {
    @Option(names = "--socket", scope = ScopeType.INHERIT, description = "Private Unix socket path.")
    Path socket = IpcClient.defaultSocket();
    @Spec Model.CommandSpec spec;
    private IpcClient client() { return new IpcClient(socket); }
    private PrintWriter out() { return spec.commandLine().getOut(); }
    @Override public Integer call() { spec.commandLine().usage(out()); return 2; }
    public static void main(String[] args) { System.exit(execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true))); }
    public static int execute(String[] args, PrintWriter out, PrintWriter err) {
        var root = new AjdmCli(); var command = new CommandLine(root);
        command.addSubcommand("daemon", new Daemon(root)); command.addSubcommand("add", new Add(root));
        command.addSubcommand("list", new ListCommand(root)); command.addSubcommand("formats", new Formats(root));
        command.addSubcommand("add-media", new AddMedia(root));
        command.addSubcommand("playlist", new Playlist(root)); command.addSubcommand("add-playlist", new AddPlaylist(root));
        command.addSubcommand("playlist-status", new PlaylistStatus(root));
        command.addSubcommand("ping", new Ping(root));
        for (String type : java.util.List.of("pause", "resume", "cancel", "restart", "remove")) {
            command.addSubcommand(type, new CommandLine(new Control(root, type)).setCommandName(type));
        }
        command.setOut(out); command.setErr(err);
        command.setParameterExceptionHandler((failure, arguments) -> { err.println(ErrorCode.INVALID_REQUEST.userMessage()); err.flush(); return 2; });
        command.setExecutionExceptionHandler((failure, cmd, parsed) -> {
            ErrorCode code = failure instanceof DownloadException known ? known.code()
                    : failure instanceof IllegalArgumentException ? ErrorCode.INVALID_REQUEST
                    : failure instanceof IllegalStateException ? ErrorCode.INVALID_STATE
                    : failure instanceof IOException ? ErrorCode.STATE_STORE_ERROR : ErrorCode.INVALID_STATE;
            err.println(code.userMessage()); err.flush(); return exitCode(code);
        });
        int result = command.execute(args); out.flush(); err.flush(); return result;
    }
    public static int exitCode(ErrorCode code) {
        return switch (code) {
            case INVALID_URL, UNSUPPORTED_SCHEME, INVALID_REQUEST -> 2;
            case INVALID_STATE, PAUSED, CANCELED, TARGET_EXISTS, SOURCE_CHANGED -> 3;
            case IPC_UNAVAILABLE, STATE_IN_USE -> 4;
            case INSUFFICIENT_DISK_SPACE, DISK_WRITE_ERROR, PART_FILE_MISSING, PART_FILE_TRUNCATED, STATE_STORE_ERROR -> 5;
            case FEATURE_UNAVAILABLE, EXTRACTION_FAILED, MUX_FAILED, DRM_PROTECTED -> 7;
            default -> 6;
        };
    }
    @Command(name = "daemon", mixinStandardHelpOptions = true, description = "Run the single core in the foreground; Ctrl+C saves active downloads.")
    static final class Daemon implements Callable<Integer> {
        private final AjdmCli root;
        @Option(names = "--state", description = "SQLite state file (daemon only).") Path database = AjdmEngine.defaultDatabase();
        @Option(names = "--yt-dlp", description = "Explicit trusted extraction executable.") Path extractor;
        @Option(names = "--ffmpeg", description = "Explicit trusted local mux executable.") Path muxer;
        Daemon(AjdmCli root) { this.root = root; }
        @SuppressWarnings("try") // Shutdown hook must also close these idempotent resources during JVM exit.
        @Override public Integer call() throws Exception {
            try {
                root.client().call("ping", new JsonObject()); root.out().println("AJDM is already running."); return 0;
            } catch (DownloadException e) { if (e.code() != ErrorCode.IPC_UNAVAILABLE) throw e; }
            try (var core = new CoreService(database, new PlatformMediaExtractor(new YtDlpExtractor(extractor == null ? YtDlpExtractor.defaultBinary(database) : extractor)),
                    new FfmpegMuxer(muxer == null ? FfmpegMuxer.defaultBinary(database) : muxer));
                 var server = new IpcServer(root.socket, core)) {
                Thread hook = Thread.ofPlatform().name("ajdm-core-shutdown").unstarted(() -> {
                    try { server.close(); } catch (IOException e) { /* No raw diagnostic output. */ }
                    core.close();
                });
                Runtime.getRuntime().addShutdownHook(hook);
                try { root.out().println("AJDM core ready."); root.out().flush(); server.await(); }
                finally {
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException e) { /* Shutdown hook owns this cleanup. */ }
                }
            }
            return 0;
        }
    }
    @Command(name = "add", mixinStandardHelpOptions = true, description = "Add a direct HTTP(S) file; the core continues after this client exits.")
    static final class Add implements Callable<Integer> {
        private final AjdmCli root;
        @Parameters(index = "0", paramLabel = "URL") String url;
        @Option(names = "--directory", description = "Destination folder (default ~/Downloads).") Path directory;
        @Option(names = "--filename", description = "Explicit safe filename; default from URL path.") String filename;
        @Option(names = "--conflict", defaultValue = "RENAME", description = "RENAME, ASK or explicit OVERWRITE.") ConflictPolicy conflict;
        @Option(names = "--sha256", description = "Expected SHA-256 (64 hex digits).") String checksum;
        @Option(names = "--connections", defaultValue = "4") int connections;
        @Option(names = "--adaptive", description = "Enable measured adaptive concurrency.") boolean adaptive;
        @Option(names = "--header", description = "HTTP header as Name:value; repeat for multiple headers.") java.util.List<String> headers = new ArrayList<>();
        @Option(names = "--wait", description = "Wait for completion; client exit does not stop the core.") boolean wait;
        Add(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws Exception {
            var p = new JsonObject(); p.addProperty("url", url);
            if (directory != null) p.addProperty("directory", directory.toAbsolutePath().normalize().toString());
            if (filename != null) p.addProperty("filename", filename);
            if (checksum != null) p.addProperty("sha256", checksum);
            p.addProperty("conflict", conflict.name()); p.addProperty("connections", connections); p.addProperty("adaptive", adaptive);
            p.add("headers", headerObject(headers));
            String id = Protocol.string(root.client().call("add", p), "downloadId", ""); root.out().println(id); root.out().flush();
            return wait ? awaitDownload(root, id) : 0;
        }
    }
    @Command(name = "list", mixinStandardHelpOptions = true, description = "List safe snapshots, progress and pause reasons.")
    static final class ListCommand implements Callable<Integer> {
        private final AjdmCli root;
        @Option(names = "--json", description = "Machine-readable safe snapshots.") boolean json;
        ListCommand(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws IOException {
            JsonObject result = root.client().call("list", new JsonObject());
            if (json) root.out().println(result);
            else for (JsonElement element : result.getAsJsonArray("downloads")) {
                var row = element.getAsJsonObject();
                root.out().printf("%s  %s  %s/%s  %s%n", Protocol.string(row, "id", ""), Protocol.string(row, "state", ""),
                        row.get("durableBytes"), row.get("totalBytes"), display(Protocol.string(row, "target", "")));
                if (row.has("error")) root.out().println("  " + knownCode(Protocol.string(row.getAsJsonObject("error"), "code", "")).userMessage());
            }
            return 0;
        }
    }
    @Command(name = "control", mixinStandardHelpOptions = true, description = "Control a persisted download by ID.")
    static final class Control implements Callable<Integer> {
        private final AjdmCli root; private final String type;
        @Parameters(index = "0", paramLabel = "ID") UUID id;
        @Option(names = "--conflict", description = "Resume only: RENAME, ASK or explicit OVERWRITE.") ConflictPolicy conflict;
        @Option(names = "--connections", description = "Resume only: change stopped file/media workers to 1..32, preserving partial bytes.") Integer connections;
        @Option(names = "--delete-files", description = "Remove only: explicitly delete this stopped job's downloaded and partial files.") boolean deleteFiles;
        @Option(names = "--header", description = "Resume/restart only: credentials as Name:value.") java.util.List<String> headers = new ArrayList<>();
        Control(AjdmCli root, String type) { this.root = root; this.type = type; }
        @Override public Integer call() throws IOException {
            var p = new JsonObject(); p.addProperty("downloadId", id.toString());
            if (conflict != null) p.addProperty("conflict", conflict.name());
            if (connections != null) p.addProperty("connections", connections);
            if (deleteFiles) p.addProperty("deleteFiles", true);
            if (!headers.isEmpty()) p.add("headers", headerObject(headers));
            root.out().println(Protocol.string(root.client().call(type, p), "downloadId", "")); return 0;
        }
    }
    @Command(name = "formats", mixinStandardHelpOptions = true, description = "List non-DRM direct HTTPS media formats and preview a selection.")
    static final class Formats implements Callable<Integer> {
        private final AjdmCli root;
        @Parameters(index = "0", paramLabel = "URL") String url;
        @Option(names = "--show-all", description = "Include qualities above the ceiling.") boolean showAll;
        @Option(names = "--max-height", defaultValue = "2160") int maxHeight;
        @Option(names = "--json", description = "Safe metadata JSON, excluding direct URLs/headers.") boolean json;
        @Option(names = "--preset", description = "BEST, P1080, P720 or AUDIO.") MediaSelection.Preset preset;
        @Option(names = "--format", description = "Preview a specific format ID.") String format;
        @Option(names = "--audio", description = "Preview an audio format paired with --format.") String audio;
        @Option(names = "--compatible-codecs", description = "Prefer AVC over VP9/AV1 when video quality ties.") boolean compatible;
        @Option(names = "--playlist-item", description = "Flat-list URL and inspect this one-based playlist position.") Integer playlistItem;
        Formats(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws IOException {
            var p = new JsonObject(); p.addProperty("url", url); p.addProperty("showAll", showAll); p.addProperty("maxHeight", maxHeight);
            p.addProperty("preferModernCodecs", !compatible);
            if (preset != null) p.addProperty("preset", preset.name()); if (format != null) p.addProperty("formatId", format); if (audio != null) p.addProperty("audioId", audio);
            if (playlistItem != null) p.addProperty("index", playlistItem);
            var result = root.client().call(playlistItem == null ? "formats" : "playlistFormats", p);
            if (json) { root.out().println(result); return 0; }
            root.out().println(display(Protocol.string(result, "title", "")));
            root.out().println("ID  KIND  CONTAINER  RESOLUTION  FPS  VIDEO/AUDIO CODEC  KBPS  SIZE  HDR  NOTE/LANGUAGE");
            for (var e : result.getAsJsonArray("formats")) {
                var f = e.getAsJsonObject();
                root.out().printf("%s  %s  %s  %sx%s  %s  %s/%s  %s  %s%s  %s  %s/%s%n",
                        display(Protocol.string(f, "formatId", "")), Protocol.string(f, "kind", ""), Protocol.string(f, "container", ""),
                        metric(f, "width"), metric(f, "height"), metric(f, "fps"), display(Protocol.string(f, "videoCodec", "")), display(Protocol.string(f, "audioCodec", "")),
                        metric(f, "bitrateKbps"), Protocol.bool(f, "approximateSize", false) ? "~" : "", metric(f, "sizeBytes"),
                        Protocol.bool(f, "hdr", false) ? "HDR" : "SDR", display(Protocol.string(f, "note", "")), display(Protocol.string(f, "language", "")));
            }
            int hidden = Protocol.integer(result, "hiddenCount", 0); if (hidden > 0) root.out().println(hidden + " formats above the ceiling; use --show-all.");
            if (result.has("selection")) root.out().println("Selection: " + result.getAsJsonObject("selection"));
            return 0;
        }
    }
    @Command(name = "add-media", mixinStandardHelpOptions = true, description = "Download selected streams through AJDM and mux paired video/audio locally.")
    static final class AddMedia implements Callable<Integer> {
        private final AjdmCli root;
        @Parameters(index = "0", paramLabel = "URL") String url;
        @Option(names = "--format", description = "Exact format ID from formats.") String format;
        @Option(names = "--audio", description = "Exact audio-only ID paired with --format video-only ID.") String audio;
        @Option(names = "--adaptive", description = "Measure and tune media concurrency up to --connections, subject to shared host limits.") boolean adaptive;
        @Option(names = "--preset", description = "BEST, P1080, P720 or AUDIO.") MediaSelection.Preset preset;
        @Option(names = "--directory") Path directory;
        @Option(names = "--filename") String filename;
        @Option(names = "--conflict", defaultValue = "RENAME") ConflictPolicy conflict;
        @Option(names = "--connections", defaultValue = "4") int connections;
        @Option(names = "--compatible-codecs") boolean compatible;
        @Option(names = "--wait") boolean wait;
        AddMedia(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws Exception {
            if (audio != null && format == null) throw new IllegalArgumentException("Audio requires format");
            if ((format == null) == (preset == null)) throw new IllegalArgumentException("Choose format or preset");
            var p = new JsonObject(); p.addProperty("url", url); p.addProperty("connections", connections);
            if (adaptive) p.addProperty("adaptive", true);
            if (audio != null) p.addProperty("audioId", audio);
            if (format != null) p.addProperty("formatId", format); if (preset != null) p.addProperty("preset", preset.name());
            if (directory != null) p.addProperty("directory", directory.toAbsolutePath().normalize().toString());
            if (filename != null) p.addProperty("filename", filename); p.addProperty("conflict", conflict.name()); p.addProperty("preferModernCodecs", !compatible);
            var added = root.client().call("addMedia", p); String id = Protocol.string(added, "downloadId", ""); root.out().println(id);
            root.out().println("Selected " + display(Protocol.string(added, "formatId", "")) + " (" + Protocol.string(added, "kind", "") + "), "
                    + (Protocol.bool(added, "approximateSize", false) ? "~" : "") + metric(added, "sizeBytes") + " bytes"); root.out().flush();
            return wait ? awaitDownload(root, id) : 0;
        }
    }
    @Command(name = "playlist", mixinStandardHelpOptions = true, description = "List playlist metadata without downloading payloads.")
    static final class Playlist implements Callable<Integer> {
        private final AjdmCli root;
        @Parameters(index = "0", paramLabel = "URL") String url;
        @Option(names = "--offset", defaultValue = "0") int offset;
        @Option(names = "--limit", defaultValue = "100") int limit;
        @Option(names = "--json") boolean json;
        Playlist(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws IOException {
            var p = new JsonObject(); p.addProperty("url", url); p.addProperty("offset", offset); p.addProperty("limit", limit);
            var result = root.client().call("playlist", p); if (json) root.out().println(result); else printPlaylist(root, result); return 0;
        }
    }
    @Command(name = "playlist-status", mixinStandardHelpOptions = true, description = "Inspect durable playlist item outcomes and child download IDs.")
    static final class PlaylistStatus implements Callable<Integer> {
        private final AjdmCli root;
        @Parameters(index = "0", paramLabel = "JOB_ID") UUID id;
        @Option(names = "--offset", defaultValue = "0") int offset;
        @Option(names = "--limit", defaultValue = "100") int limit;
        @Option(names = "--json") boolean json;
        PlaylistStatus(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws IOException {
            var p = new JsonObject(); p.addProperty("downloadId", id.toString()); p.addProperty("offset", offset); p.addProperty("limit", limit);
            var result = root.client().call("playlistStatus", p); if (json) root.out().println(result); else printPlaylist(root, result); return 0;
        }
    }
    @Command(name = "add-playlist", mixinStandardHelpOptions = true, description = "Download selected playlist entries through AJDM with bounded lazy extraction.")
    static final class AddPlaylist implements Callable<Integer> {
        private final AjdmCli root;
        @Parameters(index = "0", paramLabel = "URL") String url;
        @Option(names = "--items", defaultValue = "all", description = "all or positions/ranges, e.g. 1-25,30.") String items;
        @Option(names = "--preset", defaultValue = "BEST") MediaSelection.Preset preset;
        @Option(names = "--choice", description = "Repeat INDEX=PRESET or INDEX=format:VIDEO_ID[+AUDIO_ID].") java.util.List<String> choices = new ArrayList<>();
        @Option(names = "--directory") Path directory;
        @Option(names = "--conflict", defaultValue = "RENAME") ConflictPolicy conflict;
        @Option(names = "--connections", defaultValue = "4") int connections;
        @Option(names = "--compatible-codecs") boolean compatible;
        @Option(names = "--wait") boolean wait;
        AddPlaylist(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws Exception {
            var p = new JsonObject(); p.addProperty("url", url); p.addProperty("items", items); p.addProperty("preset", preset.name());
            p.addProperty("conflict", conflict.name()); p.addProperty("connections", connections); p.addProperty("preferModernCodecs", !compatible);
            if (directory != null) p.addProperty("directory", directory.toAbsolutePath().normalize().toString());
            var selected = new JsonArray(); var indices = new HashSet<Integer>();
            for (String text : choices) {
                int eq = text.indexOf('='); if (eq < 1) throw new IllegalArgumentException("Invalid per-entry choice");
                int index = Integer.parseInt(text.substring(0, eq)); if (!indices.add(index)) throw new IllegalArgumentException("Duplicate per-entry choice");
                var choice = new JsonObject(); choice.addProperty("index", index); String value = text.substring(eq + 1);
                if (value.startsWith("format:")) { String[] formats = value.substring(7).split("\\+", -1); if (formats.length > 2) throw new IllegalArgumentException("Invalid format pair");
                    choice.addProperty("formatId", formats[0]); if (formats.length == 2) choice.addProperty("audioId", formats[1]);
                } else choice.addProperty("preset", MediaSelection.Preset.valueOf(value).name()); selected.add(choice);
            }
            p.add("choices", selected); String id = Protocol.string(root.client().call("addPlaylist", p), "downloadId", ""); root.out().println(id); root.out().flush();
            if (!wait) return 0;
            var query = new JsonObject(); query.addProperty("downloadId", id);
            while (true) {
                var status = root.client().call("playlistStatus", query); String state = Protocol.string(status, "state", "");
                if (state.equals("COMPLETED")) {
                    printPlaylist(root, status);
                    if (Protocol.integer(status, "failed", 0) > 0) throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Playlist has failed downloads"); return 0;
                }
                if (Set.of("PAUSED", "CANCELED", "FAILED").contains(state)) throw new DownloadException(status.has("error") ? knownCode(Protocol.string(status.getAsJsonObject("error"), "code", "")) : ErrorCode.PAUSED, "Playlist stopped");
                Thread.sleep(250);
            }
        }
    }
    private static void printPlaylist(AjdmCli root, JsonObject result) {
        root.out().println(display(Protocol.string(result, "title", "")));
        if (result.has("state")) root.out().printf("%s: %s completed, %s skipped, %s failed of %s selected%n", Protocol.string(result, "state", ""), metric(result, "completed"), metric(result, "skipped"), metric(result, "failed"), metric(result, "selected"));
        else root.out().println(metric(result, "totalEntries") + " entries (paged)");
        for (var entry : result.getAsJsonArray("entries")) { var row = entry.getAsJsonObject(); root.out().printf("%s  %s  %s  %s%n", metric(row, "index"),
                display(Protocol.string(row, "title", "")), Protocol.string(row, "state", ""), row.has("error") ? Protocol.string(row.getAsJsonObject("error"), "code", "") : ""); }
    }
    private static int awaitDownload(AjdmCli root, String id) throws IOException, InterruptedException {
        while (true) {
            for (JsonElement element : root.client().call("list", new JsonObject()).getAsJsonArray("downloads")) {
                JsonObject row = element.getAsJsonObject(); if (!id.equals(Protocol.string(row, "id", ""))) continue;
                String state = Protocol.string(row, "state", "");
                if (state.equals("COMPLETED")) { root.out().println("Saved " + display(Protocol.string(row, "target", ""))); return 0; }
                if (Set.of("FAILED", "PAUSED", "CANCELED").contains(state)) {
                    ErrorCode code = row.has("error") ? knownCode(Protocol.string(row.getAsJsonObject("error"), "code", ""))
                            : state.equals("CANCELED") ? ErrorCode.CANCELED : ErrorCode.PAUSED;
                    throw new DownloadException(code, "Transfer stopped");
                }
            }
            Thread.sleep(250);
        }
    }
    private static String metric(JsonObject p, String key) {
        JsonElement value = p.get(key); return value == null || value.isJsonNull() || value.toString().equals("-1") ? "?" : value.toString();
    }
    @Command(name = "ping", mixinStandardHelpOptions = true, description = "Check whether the core is running.")
    static final class Ping implements Callable<Integer> {
        private final AjdmCli root; Ping(AjdmCli root) { this.root = root; }
        @Override public Integer call() throws IOException { root.client().call("ping", new JsonObject()); root.out().println("AJDM is running."); return 0; }
    }
    private static JsonObject headerObject(java.util.List<String> values) {
        var object = new JsonObject(); var names = new HashSet<String>();
        for (String header : values) {
            int colon = header.indexOf(':'); if (colon <= 0) throw new IllegalArgumentException("Invalid header");
            String name = header.substring(0, colon);
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate header");
            object.addProperty(name, header.substring(colon + 1).stripLeading());
        }
        return object;
    }
    private static ErrorCode knownCode(String code) {
        for (ErrorCode known : ErrorCode.values()) if (known.publicCode().equals(code)) return known;
        return ErrorCode.INVALID_STATE;
    }
    private static String display(String text) {
        var safe = new StringBuilder();
        text.codePoints().forEach(c -> {
            int type = Character.getType(c);
            safe.appendCodePoint(Character.isISOControl(c) || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR ? '?' : c);
        });
        return safe.toString();
    }
}
