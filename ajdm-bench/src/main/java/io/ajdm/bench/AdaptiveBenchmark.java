package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Repeatable fixed/adaptive comparison; every output is independently verifiable. */
public final class AdaptiveBenchmark {
    private AdaptiveBenchmark() { }
    private record Scenario(String name, FaultyRangeServer.Mode server, int bytes, long rate) { }
    private record Sample(String scenario, int trial, boolean adaptive, long bytes, double seconds,
                          long received, int peak, int trials, int accepted, int rollbacks, int steals, String sha256) { }
    public static void run(AjdmEngine engine, Path output) throws Exception {
        Path directory = output.toAbsolutePath().normalize(); Files.createDirectories(directory);
        try (var server = new FaultyRangeServer(65536, FaultyRangeServer.Mode.NORMAL)) {
            download(engine, server, directory, "warmup", 0, false);
        }
        var scenarios = List.of(new Scenario("changing-lane", FaultyRangeServer.Mode.CHANGING_LANE, 16 * 1024 * 1024 + 17, 2 * 1024 * 1024),
                new Scenario("shared-cap", FaultyRangeServer.Mode.SHARED_CAPPED, 4 * 1024 * 1024 + 17, 512 * 1024),
                new Scenario("per-response-cap", FaultyRangeServer.Mode.CAPPED, 8 * 1024 * 1024 + 17, 256 * 1024));
        var samples = new ArrayList<Sample>();
        for (var scenario : scenarios) for (int trial = 1; trial <= 3; trial++) {
            boolean[] order = trial % 2 == 1 ? new boolean[]{false, true} : new boolean[]{true, false};
            for (boolean adaptive : order) try (var server = new FaultyRangeServer(scenario.bytes, scenario.server, scenario.rate)) {
                var sample = download(engine, server, directory, scenario.name, trial, adaptive);
                samples.add(sample);
                System.out.printf(Locale.ROOT, "%s trial %d %s: %.3f s, peak %d, steals %d, SHA-256 verified%n",
                        scenario.name, trial, adaptive ? "adaptive" : "fixed", sample.seconds, sample.peak, sample.steals);
            }
        }
        var csv = new StringBuilder("scenario,trial,mode,bytes,seconds,mib_per_second,received_bytes,peak_responses,trials,accepted,rollbacks,steals,sha256\n");
        for (var s : samples) csv.append(String.format(Locale.ROOT, "%s,%d,%s,%d,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%s%n",
                s.scenario, s.trial, s.adaptive ? "adaptive" : "fixed", s.bytes, s.seconds, s.bytes / s.seconds / 1048576.0,
                s.received, s.peak, s.trials, s.accepted, s.rollbacks, s.steals, s.sha256));
        Files.writeString(directory.resolve("samples.csv"), csv, StandardOpenOption.CREATE_NEW);
        var report = new StringBuilder("# AJDM adaptive local comparison\n\nMeasured at " + Instant.now() + ", " + System.getProperty("os.name")
                + ", Java " + System.getProperty("java.version") + ". Three alternating fixed/adaptive trials per scenario after a warmup. "
                + "Times include probing, transfer, durable checkpoints, SHA-256 verification, and publication; engine/server construction is excluded.\n\n"
                + "| Scenario | Fixed 4 median s | Adaptive up to 4 median s | Fixed/adaptive time ratio |\n|---|---:|---:|---:|\n");
        for (var scenario : scenarios) {
            double fixed = median(samples, scenario.name, false), adaptive = median(samples, scenario.name, true);
            report.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.2fx |%n", scenario.name, fixed, adaptive, fixed / adaptive));
        }
        report.append("\nAll 18 outputs matched SHA-256; useful file bytes equal received bytes. [Raw samples](samples.csv) include peaks and decision counts. "
                + "Each adaptive run has a decisions CSV recording admissions, steals, acceptance, and rollback. Ratios below 1 mean adaptive was slower.\n\n"
                + "Scenarios: changing-lane downloads 16 MiB + 17 bytes with a 2 MiB/s per-response cap. After one second, the first transfer's TCP peer slows to 128 KiB/s; "
                + "other peers keep their original cap. Java HTTP connection reuse is allowed, so a logical worker is not guaranteed to keep the same peer. "
                + "Shared-cap downloads 4 MiB + 17 bytes through a single 512 KiB/s pacing budget shared by all responses. "
                + "Per-response-cap downloads 8 MiB + 17 bytes at 256 KiB/s per response without a shared cap.\n\n"
                + "Both modes configure 16 KiB buffers, eight in-flight buffers, a 1 MiB maximum HTTP window, 256 KiB minimum segments, one-second checkpoints, and four maximum workers. "
                + "Fixed starts all four with static ranges. Adaptive starts two, uses 1 MiB request windows under this configured 1 MiB cap, steals only unrequested tails, "
                + "and uses bounded doubling trials (2 to 4 here) when enough work remains. Window sizing and the admission ramp are part of the measured adaptive policy; this report does not isolate their effects.\n\n"
                + "This local comparison does not establish performance against IDM or over real 4G/5G networks. Physical power-loss, cross-OS, and media extraction tests remain separate.\n");
        Files.writeString(directory.resolve("report.md"), report, StandardOpenOption.CREATE_NEW);
        System.out.println("Adaptive comparison report: " + directory.resolve("report.md"));
    }
    private static Sample download(AjdmEngine engine, FaultyRangeServer server, Path directory, String scenario, int trial, boolean adaptive) throws Exception {
        var options = new TransferOptions(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(3),
                16384, 8, 1024 * 1024, 2, Duration.ofMillis(10), Duration.ofMillis(100), server.sha256(), Duration.ofSeconds(1));
        options = adaptive ? options.withAdaptiveParallelism(4, 256 * 1024) : options.withParallelism(4, 256 * 1024);
        String name = scenario + "-" + trial + "-" + (adaptive ? "adaptive" : "fixed");
        long start = System.nanoTime();
        var handle = engine.start(new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve(name + ".bin"), Map.of(), options));
        var result = handle.completion().toCompletableFuture().get(120, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - start) / 1e9;
        if (handle.progress().receivedBytes() != result.bytes()) throw new IllegalStateException("Benchmark unexpectedly refetched bytes");
        var decisions = handle.adaptation().decisions();
        var trace = new StringBuilder("elapsed_ms,target_connections,reason,useful_bytes_per_second\n");
        for (var d : decisions) trace.append(String.format(Locale.ROOT, "%d,%d,%s,%.3f%n", d.elapsedMillis(), d.connections(), d.reason(), d.usefulBytesPerSecond()));
        if (adaptive) Files.writeString(directory.resolve(name + "-decisions.csv"), trace, StandardOpenOption.CREATE_NEW);
        return new Sample(scenario, trial, adaptive, result.bytes(), seconds, handle.progress().receivedBytes(), server.peakTransfers(),
                (int) decisions.stream().filter(d -> d.reason().equals("trial-start")).count(),
                (int) decisions.stream().filter(d -> d.reason().equals("trial-accepted")).count(),
                (int) decisions.stream().filter(d -> d.reason().contains("rollback")).count(),
                (int) decisions.stream().filter(d -> d.reason().equals("tail-stolen")).count(), result.sha256());
    }
    private static double median(List<Sample> samples, String scenario, boolean adaptive) {
        return samples.stream().filter(s -> s.scenario.equals(scenario) && s.adaptive == adaptive)
                .mapToDouble(Sample::seconds).sorted().toArray()[1];
    }
}
