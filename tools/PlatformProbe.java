import com.google.gson.*;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.IpcClient;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded live qualification, isolated from the user's state. AJDM owns every payload. */
public final class PlatformProbe {
    private static boolean idle(IpcClient live) throws Exception {
        for (var entry : live.call("list", new JsonObject()).getAsJsonArray("downloads")) {
            String state = entry.getAsJsonObject().get("state").getAsString();
            if (!Set.of("COMPLETED", "CANCELED", "PAUSED", "FAILED").contains(state)) return false;
        }
        return true;
    }
    public static void main(String[] args) throws Exception {
        String page = args[0]; Path directory = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(directory)) throw new IllegalArgumentException("Use a new private report directory");
        Files.createDirectory(directory); Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var report = new JsonObject(); report.addProperty("source", page);
        var live = new IpcClient(Path.of(args[2])); UUID id = null;
        try (var core = new CoreService(directory.resolve("state/db"), new PlatformMediaExtractor(new YtDlpExtractor(Path.of("/home/ariga/.local/bin/yt-dlp"))), new FfmpegMuxer(Path.of("/usr/bin/ffmpeg")))) {
            if (!idle(live)) throw new IllegalStateException("User transfer active");
            report.addProperty("phase", "analysis");
            var info = core.formats(page); report.addProperty("directFormats", info.formats().size());
            var choices = info.formats().stream().filter(f -> !f.fragmented() && f.kind() != MediaFormat.Kind.AUDIO_ONLY
                    && (f.filesize() == null || f.filesize() <= 32L * 1024 * 1024)).toList();
            var selected = args.length > 3 ? info.format(args[3]) : choices.stream().min(Comparator.comparingInt((MediaFormat f) -> f.height() == null ? Integer.MAX_VALUE : f.height())
                    .thenComparingDouble(f -> f.tbrKbps() == null ? Double.MAX_VALUE : f.tbrKbps())).orElseThrow();
            String audio = selected.kind() == MediaFormat.Kind.VIDEO_ONLY ? info.formats().stream()
                    .filter(f -> !f.fragmented() && f.kind() == MediaFormat.Kind.AUDIO_ONLY).findFirst().orElseThrow().formatId() : null;
            var plan = MediaSelection.custom(info, selected.formatId(), audio);
            report.addProperty("format", selected.formatId()); report.addProperty("height", selected.height());
            if (!idle(live)) throw new IllegalStateException("User transfer active");
            report.addProperty("phase", "admission");
            id = core.addMedia(page, selected.formatId(), audio, null, true, directory.resolve("files"), "sample." + plan.container(), ConflictPolicy.RENAME, 4).id();
            report.addProperty("phase", "payload");
            long start = System.nanoTime(), deadline = start + TimeUnit.SECONDS.toNanos(180), nextPoll = start;
            CoreService.Row row;
            while (true) {
                row = core.list().stream().filter(r -> r.kind().equals("MEDIA")).findFirst().orElseThrow();
                if (row.state() == DownloadState.COMPLETED || row.state() == DownloadState.FAILED || row.state() == DownloadState.PAUSED) break;
                if (System.nanoTime() >= nextPoll) {
                    if (!idle(live)) { core.pause(id); report.addProperty("stopped", "user download started"); break; }
                    nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                }
                if (System.nanoTime() >= deadline || row.writtenBytes() > 64L * 1024 * 1024) {
                    core.pause(id); report.addProperty("stopped", "qualification sample limit"); break;
                }
                Thread.sleep(100);
            }
            report.addProperty("state", row.state().name()); report.addProperty("bytes", row.writtenBytes());
            report.addProperty("seconds", (System.nanoTime() - start) / 1e9);
            if (row.error() != null) report.addProperty("error", row.error().publicCode());
            if (row.state() == DownloadState.COMPLETED) {
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(Path.of(row.target()))) {
                    byte[] buffer = new byte[64 * 1024]; int n;
                    while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
                }
                report.addProperty("sha256", HexFormat.of().formatHex(digest.digest()));
                report.addProperty("file", Path.of(row.target()).getFileName().toString());
            }
        } catch (DownloadException failure) { report.addProperty("error", failure.code().publicCode()); }
        catch (Exception failure) { report.addProperty("error", "qualification could not complete"); }
        Files.writeString(directory.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println(report);
    }
}
