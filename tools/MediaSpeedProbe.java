import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.TransferHandle;
import io.ajdm.ipc.IpcClient;
import io.ajdm.media.YtDlpExtractor;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Development sampling tool: AJDM owns HTTP; extraction is metadata-only. No user database writes. */
public class MediaSpeedProbe {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "PROBING", "EXTRACTING", "DOWNLOADING", "MERGING", "VERIFYING");
    private static final long SAMPLE_BYTES = 4L * 1024 * 1024;
    private static final long SAMPLE_NANOS = TimeUnit.SECONDS.toNanos(20);
    private record Reading(double seconds, long received, long written, int target, int busy) { }
    private record Sample(int round, String mode, double seconds, long received, long written,
                          double usefulMiBPerSecond, int target, String stopReason, String state,
                          List<Reading> readings, Object decisions) { }

    public static void main(String[] args) {
        try { run(args); }
        catch (io.ajdm.model.DownloadException failure) { System.err.println(failure.userMessage()); System.exit(1); }
        catch (Exception failure) { System.err.println("Speed comparison stopped: invalid arguments, source, files, or core status. Private source details were suppressed."); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        if (args.length != 5) {
            System.out.println("Usage: MediaSpeedProbe.java PAGE_URL FORMAT_ID YT_DLP_PATH CORE_SOCKET NEW_REPORT_DIRECTORY");
            System.out.println("Nine sequential samples: fixed4, fixed8, adaptive8, in rotated order. Each stops at 20 seconds or >=4 MiB written, plus in-flight/stop overhead.");
            System.out.println("Refuses to compete with active core downloads. Existing user jobs are never controlled. Reports do not include URLs or headers.");
            return;
        }
        var client = new IpcClient(Path.of(args[3])); requireIdle(client);
        Path directory = Path.of(args[4]).toAbsolutePath().normalize(); Files.createDirectory(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        var json = new GsonBuilder().setPrettyPrinting().create();
        var samples = new ArrayList<Sample>();
        try (var extractor = new YtDlpExtractor(Path.of(args[2]))) {
            var info = extractor.extract(URI.create(args[0]), () -> Thread.currentThread().isInterrupted());
            var format = info.format(args[1]);
            if (format.fragmented() || format.sizeBytes() < 16L * 1024 * 1024) throw new IllegalArgumentException("Choose a large direct stream");
            var selection = new JsonObject(); selection.addProperty("mediaId", info.id()); selection.addProperty("formatId", format.formatId());
            selection.addProperty("sourceBytes", format.sizeBytes()); selection.addProperty("approximateSize", format.approximateSize());
            selection.addProperty("height", format.height()); selection.addProperty("codec", format.vcodec());
            Files.writeString(directory.resolve("selection.json"), json.toJson(selection));
            System.out.printf(Locale.ROOT, "Source: format %s, %s, %.1f MiB. Payloads use AJDM's engine only.%n", format.formatId(), format.vcodec(), format.sizeBytes() / 1048576.0);
            List<String> modes = List.of("fixed4", "fixed8", "adaptive8");
            try (var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
                for (int round = 1; round <= 3; round++) for (int position = 0; position < 3; position++) {
                    requireIdle(client);
                    String mode = modes.get((position + round - 1) % modes.size());
                    var defaults = TransferOptions.defaults();
                    var options = mode.equals("adaptive8") ? defaults.withAdaptiveParallelism(8, defaults.minSegmentBytes())
                            : defaults.withConnections(mode.equals("fixed4") ? 4 : 8);
                    long started = System.nanoTime();
                    var handle = engine.start(new TransferRequest(UUID.randomUUID(), format.source().uri(), directory.resolve("sample-" + round + "-" + mode + ".bin"), format.source().headers(), options));
                    Thread shutdown = Thread.ofPlatform().unstarted(() -> {
                        try { if (!handle.completion().toCompletableFuture().isDone()) handle.pause().toCompletableFuture().join(); }
                        catch (Exception ignored) { /* Main retains the sample's private state. */ }
                    });
                    Runtime.getRuntime().addShutdownHook(shutdown);
                    var readings = new ArrayList<Reading>(); long lastLog = started;
                    String stop = "time-limit"; boolean interference = false;
                    try {
                        while (!handle.completion().toCompletableFuture().isDone()) {
                            long now = System.nanoTime(); var progress = handle.progress();
                            if (now - lastLog >= TimeUnit.SECONDS.toNanos(1)) {
                                lastLog = now; var tuning = handle.adaptation(); double seconds = (now - started) / 1e9;
                                readings.add(new Reading(seconds, progress.receivedBytes(), progress.writtenBytes(), tuning.targetConnections(), tuning.busyWorkers()));
                                System.out.printf(Locale.ROOT, "Round %d %-9s | %.2f MiB | %.3f MiB/s average | %ds remaining in sample | target %d%n",
                                        round, mode, progress.writtenBytes() / 1048576.0, progress.writtenBytes() / 1048576.0 / seconds,
                                        Math.max(0, 20 - (int) seconds), tuning.targetConnections());
                                if (!idle(client)) { interference = true; stop = "core-download-started"; break; }
                            }
                            if (progress.writtenBytes() >= SAMPLE_BYTES) { stop = "byte-limit"; break; }
                            if (now - started >= SAMPLE_NANOS) break;
                            Thread.sleep(20);
                        }
                        var beforeStop = handle.progress(); double seconds = (System.nanoTime() - started) / 1e9;
                        if (!handle.completion().toCompletableFuture().isDone()) handle.pause().toCompletableFuture().get(30, TimeUnit.SECONDS);
                        else { handle.completion().toCompletableFuture().get(); stop = "completed"; }
                        var settled = handle.progress(); var tuning = handle.adaptation();
                        samples.add(new Sample(round, mode, seconds, settled.receivedBytes(), settled.writtenBytes(),
                                beforeStop.writtenBytes() / 1048576.0 / seconds, tuning.targetConnections(), stop, settled.state().name(), readings, tuning.decisions()));
                        Files.writeString(directory.resolve("samples.json"), json.toJson(samples));
                        System.out.printf(Locale.ROOT, "Finished round %d %s: %.3f MiB/s useful average; %s.%n", round, mode, samples.getLast().usefulMiBPerSecond(), stop);
                        if (interference) throw new IllegalStateException("Another core download started; comparison invalidated");
                    } finally {
                        if (!handle.completion().toCompletableFuture().isDone()) handle.pause().toCompletableFuture().get(30, TimeUnit.SECONDS);
                        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
                    }
                    Thread.sleep(500);
                }
            }
        }
        var report = new StringBuilder("# Real-source bounded speed samples\n\nThree sequential rotated rounds, same extracted source and format; production default buffers, windows, retries and checkpoint interval. Engine limits: eight active responses per host, 32 overall. Source extraction is excluded; probing and connection startup are included. Useful average uses acknowledged bytes before requesting pause; settled bytes can be greater. Samples stop at 20 seconds or >=4 MiB written, with in-flight/stop overshoot. These are samples, not complete-file timing or SHA-256 verification. Fixed modes have no adaptive target diagnostics.\n\n| Mode | Median useful MiB/s | Minimum | Maximum |\n|---|---:|---:|---:|\n");
        for (String mode : List.of("fixed4", "fixed8", "adaptive8")) {
            double[] rates = samples.stream().filter(s -> s.mode().equals(mode)).mapToDouble(Sample::usefulMiBPerSecond).sorted().toArray();
            report.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f |%n", mode, rates[1], rates[0], rates[2]));
        }
        report.append("\nNo user jobs were paused, resumed, canceled, deleted or modified. Each sample has private recoverable partials and separate database state. Samples cannot prove full-download speed, network capacity, packet-loss behavior or superiority to IDM. Changing mobile capacity/CDN conditions can affect comparisons despite rotated order. Repeat on slow and fast networks and with more rounds before choosing a policy.\n");
        Files.writeString(directory.resolve("report.md"), report);
        System.out.println("Comparison finished: " + directory.resolve("report.md"));
    }
    private static void requireIdle(IpcClient client) throws Exception {
        if (!idle(client)) throw new IllegalStateException("Core downloads are active; retry after they stop");
    }
    private static boolean idle(IpcClient client) throws Exception {
        for (var row : client.call("list", new JsonObject()).getAsJsonArray("downloads"))
            if (ACTIVE.contains(row.getAsJsonObject().get("state").getAsString())) return false;
        return true;
    }
}
