package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Exploratory same-JVM HTTP matrix. Six rounds exercise all permutations of three transfer modes. */
public final class PerformanceMatrix {
    private PerformanceMatrix() {}
    public enum Mode { SINGLE, FIXED, ADAPTIVE;
        public String label() { return name().toLowerCase(Locale.ROOT); }
    }
    public record Sample(String scenario, int trial, int order, Mode mode, long bytes, double seconds,
                         long received, long offered, int allRequests, int payloadRequests, int peakResponses, int drops,
                         double cpuSeconds, long heapPeak, long rssPeak, long gcMillis, long queuePeak, long durabilityLagPeak,
                         int telemetrySamples, int trials, int accepted, int rollbacks, int steals, String sha256) {}
    public static List<Mode> order(int trial) {
        if (trial < 1 || trial > 60) throw new IllegalArgumentException("Expected 1–60 rounds");
        return switch ((trial - 1) % 6) {
            case 0 -> List.of(Mode.SINGLE, Mode.FIXED, Mode.ADAPTIVE);
            case 1 -> List.of(Mode.FIXED, Mode.ADAPTIVE, Mode.SINGLE);
            case 2 -> List.of(Mode.ADAPTIVE, Mode.SINGLE, Mode.FIXED);
            case 3 -> List.of(Mode.ADAPTIVE, Mode.FIXED, Mode.SINGLE);
            case 4 -> List.of(Mode.FIXED, Mode.SINGLE, Mode.ADAPTIVE);
            default -> List.of(Mode.SINGLE, Mode.ADAPTIVE, Mode.FIXED);
        };
    }
    public static void run(AjdmEngine engine, Path output, int rounds) throws Exception { run(engine, output, rounds, NetworkProfile.catalog()); }
    static void run(AjdmEngine engine, Path output, int rounds, List<NetworkProfile> profiles) throws Exception {
        order(rounds);
        if (profiles.isEmpty() || profiles.stream().map(NetworkProfile::name).distinct().count() != profiles.size()) {
            throw new IllegalArgumentException("Distinct profiles are required");
        }
        Path directory = output.toAbsolutePath().normalize(); Files.createDirectories(directory.getParent()); Files.createDirectory(directory);
        var metadata = new Properties(); metadata.setProperty("started_at", Instant.now().toString());
        metadata.setProperty("os", System.getProperty("os.name")); metadata.setProperty("os_version", System.getProperty("os.version"));
        metadata.setProperty("arch", System.getProperty("os.arch")); metadata.setProperty("java", System.getProperty("java.version"));
        metadata.setProperty("max_heap_bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        metadata.setProperty("jvm_arguments", String.join(" ", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()));
        metadata.setProperty("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        metadata.setProperty("rounds", Integer.toString(rounds)); metadata.setProperty("http", "HTTP/1.1 loopback");
        metadata.setProperty("timing", "probe through durable publication and engine SHA-256; server construction and independent rehash excluded");
        metadata.setProperty("resource_scope", "whole JVM including server/sampler; RSS Linux-only; 50ms sampled peaks");
        metadata.setProperty("engine_policy", "unchanged production defaults; options standardized for comparison; no persistent host hints");
        try (var writer = Files.newBufferedWriter(directory.resolve("environment.properties"), StandardOpenOption.CREATE_NEW)) { metadata.store(writer, "AJDM performance matrix"); }
        writeProfiles(directory, profiles);
        Files.writeString(directory.resolve("samples.csv"), "scenario,trial,order,mode,bytes,seconds,mib_per_second,received_bytes,extra_received_bytes,offered_payload_bytes,total_requests,payload_requests,peak_responses,dropped_bodies,process_cpu_seconds,sampled_heap_peak_bytes,sampled_rss_peak_bytes,gc_millis,sampled_queue_peak_bytes,sampled_durability_lag_peak_bytes,telemetry_samples,trials,accepted,rollbacks,steals,sha256\n", StandardOpenOption.CREATE_NEW);
        var samples = new ArrayList<Sample>();
        Files.writeString(directory.resolve("report.md"), report(samples, profiles, rounds, false), StandardOpenOption.CREATE_NEW);
        for (Mode mode : Mode.values()) {
            try (var warmup = new BenchmarkServer(new NetworkProfile("warmup-" + mode.label(), 512 * 1024 + 17, 0, List.of(), Duration.ZERO, java.util.Set.of()))) {
                download(engine, warmup, directory, "warmup", 0, mode.ordinal() + 1, mode);
            }
        }
        for (var profile : profiles) for (int trial = 1; trial <= rounds; trial++) {
            int position = 0;
            for (Mode mode : order(trial)) {
                Sample sample;
                try (var server = new BenchmarkServer(profile)) {
                    sample = download(engine, server, directory, profile.name(), trial, ++position, mode);
                }
                samples.add(sample); Files.writeString(directory.resolve("samples.csv"), row(sample), StandardOpenOption.APPEND);
                Files.writeString(directory.resolve("report.md"), report(samples, profiles, rounds, false));
                System.out.printf(Locale.ROOT, "%s round %d/%d %s: %.3f s, %.3f MiB/s, extra %d bytes, peak %d, SHA-256 verified%n",
                        profile.name(), trial, rounds, mode.label(), sample.seconds(), sample.bytes() / sample.seconds() / 1048576.0,
                        sample.received() - sample.bytes(), sample.peakResponses());
            }
        }
        Files.writeString(directory.resolve("report.md"), report(samples, profiles, rounds, true));
        System.out.println("Performance matrix complete: " + directory.resolve("report.md"));
    }
    static TransferOptions options(String hash, Mode mode) {
        var base = new TransferOptions(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(3),
                16384, 8, 1024 * 1024, 3, Duration.ofMillis(10), Duration.ofMillis(100), hash, Duration.ofSeconds(1));
        return mode == Mode.ADAPTIVE ? base.withAdaptiveParallelism(4, 256 * 1024) : base.withParallelism(mode == Mode.SINGLE ? 1 : 4, 256 * 1024);
    }
    private static Sample download(AjdmEngine engine, BenchmarkServer server, Path directory, String scenario, int trial, int position, Mode mode) throws Exception {
        String name = scenario + "-" + trial + "-" + mode.label(); Path target = directory.resolve(name + ".bin");
        long cpuBefore = TransferSampler.cpuNanos(), gcBefore = TransferSampler.gcMillis(), started = System.nanoTime();
        var handle = engine.start(new TransferRequest(UUID.randomUUID(), server.uri(), target, Map.of(), options(server.sha256(), mode)));
        var sampler = new TransferSampler(handle, server, started);
        io.ajdm.model.TransferResult result;
        double seconds; long cpuAfter, gcAfter;
        try {
            result = handle.completion().toCompletableFuture().get(180, TimeUnit.SECONDS);
            seconds = (System.nanoTime() - started) / 1e9; cpuAfter = TransferSampler.cpuNanos(); gcAfter = TransferSampler.gcMillis();
        } finally { sampler.finish(); }
        var decisions = handle.adaptation().decisions();
        Files.writeString(directory.resolve(name + "-telemetry.csv"), sampler.csv(), StandardOpenOption.CREATE_NEW);
        var trace = new StringBuilder("elapsed_ms,target_connections,reason,useful_bytes_per_second\n");
        for (var event : decisions) trace.append(String.format(Locale.ROOT, "%d,%d,%s,%.3f%n", event.elapsedMillis(), event.connections(), event.reason(), event.usefulBytesPerSecond()));
        Files.writeString(directory.resolve(name + "-decisions.csv"), trace, StandardOpenOption.CREATE_NEW);
        // The engine's SHA pass is included in seconds. This separate rehash and size check are outside the timed interval.
        String actual = sha256(target);
        if (!server.sha256().equals(actual) || Files.size(target) != result.bytes() || !actual.equals(result.sha256())
                || handle.progress().writtenBytes() != result.bytes() || handle.progress().durableBytes() != result.bytes()
                || handle.progress().receivedBytes() < result.bytes()) throw new IllegalStateException("Benchmark integrity or byte accounting failed");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.activeResponses() > 0 && System.nanoTime() < until) Thread.sleep(2);
        if (server.activeResponses() != 0) throw new IllegalStateException("Benchmark response bodies did not settle");
        var requests = new StringBuilder("ordinal,start,end_exclusive,elapsed_ms,offered_bytes,intentional_drop\n");
        for (var request : server.requests()) requests.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%s%n",
                request.ordinal(), request.start(), request.endExclusive(), request.elapsedMillis(), request.offeredBytes(), request.intentionalDrop()));
        Files.writeString(directory.resolve(name + "-requests.csv"), requests, StandardOpenOption.CREATE_NEW);
        return new Sample(scenario, trial, position, mode, result.bytes(), seconds, handle.progress().receivedBytes(), server.offeredBytes(),
                server.allRequests(), server.payloadRequests(), server.peakResponses(), server.droppedBodies(),
                cpuBefore < 0 || cpuAfter < 0 ? -1 : (cpuAfter - cpuBefore) / 1e9, sampler.heapPeak(), sampler.rssPeak(), Math.max(0, gcAfter - gcBefore),
                sampler.queuePeak(), sampler.durabilityLagPeak(), sampler.count(),
                (int) decisions.stream().filter(d -> d.reason().equals("trial-start")).count(),
                (int) decisions.stream().filter(d -> d.reason().equals("trial-accepted")).count(),
                (int) decisions.stream().filter(d -> d.reason().contains("rollback")).count(),
                (int) decisions.stream().filter(d -> d.reason().equals("tail-stolen")).count(), actual);
    }
    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536]; int read;
            while ((read = stream.read(bytes)) >= 0) if (read > 0) digest.update(bytes, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String row(Sample s) {
        return String.format(Locale.ROOT, "%s,%d,%d,%s,%d,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",
                s.scenario(), s.trial(), s.order(), s.mode().label(), s.bytes(), s.seconds(), s.bytes() / s.seconds() / 1048576.0,
                s.received(), s.received() - s.bytes(), s.offered(), s.allRequests(), s.payloadRequests(), s.peakResponses(), s.drops(),
                s.cpuSeconds(), s.heapPeak(), s.rssPeak(), s.gcMillis(), s.queuePeak(), s.durabilityLagPeak(), s.telemetrySamples(),
                s.trials(), s.accepted(), s.rollbacks(), s.steals(), s.sha256());
    }
    private static void writeProfiles(Path directory, List<NetworkProfile> profiles) throws IOException {
        var csv = new StringBuilder("scenario,bytes,per_response_bytes_per_second,response_delay_ms,shared_schedule_ms_bytes_per_second,dropped_response_ordinals\n");
        for (var p : profiles) {
            String schedule = p.sharedRates().stream().map(step -> step.after().toMillis() + ":" + step.bytesPerSecond()).collect(java.util.stream.Collectors.joining(";"));
            String drops = p.droppedBodies().stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(";"));
            csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%s,%s%n", p.name(), p.bytes(), p.perResponseRate(), p.responseDelay().toMillis(), schedule, drops));
        }
        Files.writeString(directory.resolve("profiles.csv"), csv, StandardOpenOption.CREATE_NEW);
    }
    private static String report(List<Sample> samples, List<NetworkProfile> profiles, int rounds, boolean complete) {
        var text = new StringBuilder("# AJDM three-mode performance matrix\n\nStatus: " + (complete ? "complete" : "in progress") + ". " + samples.size() + "/" + profiles.size() * rounds * 3 + " verified measured outputs.\n\n"
                + "Single (one fixed worker), fixed (four workers), and adaptive (up to four) share transfer options and a fresh fixture per run. Each six-round block covers all mode permutations. "
                + "A warmup per mode precedes measurements. Times run from engine.start through probe, durable publication, and engine SHA-256; the independent output rehash is excluded. "
                + "JVM/filesystem caches are warm and not flushed. This is exploratory loopback evidence, not a real mobile or IDM acceptance result.\n\n"
                + "Percentiles use linear interpolation at (n-1)*q. Each cell shows median [p10, p90] seconds; smaller is faster.\n\n"
                + "| Scenario | Single | Fixed | Adaptive | Fixed/adaptive median time ratio |\n|---|---:|---:|---:|---:|\n");
        for (var profile : profiles) {
            text.append("| ").append(profile.name());
            for (Mode mode : Mode.values()) {
                double[] values = samples.stream().filter(s -> s.scenario().equals(profile.name()) && s.mode() == mode).mapToDouble(Sample::seconds).toArray();
                text.append(" | ").append(values.length == 0 ? "pending" : String.format(Locale.ROOT, "%.3f [%.3f, %.3f] (n=%d)",
                        BenchmarkStatistics.percentile(values, .5), BenchmarkStatistics.percentile(values, .1), BenchmarkStatistics.percentile(values, .9), values.length));
            }
            double[] fixed = samples.stream().filter(s -> s.scenario().equals(profile.name()) && s.mode() == Mode.FIXED).mapToDouble(Sample::seconds).toArray();
            double[] adaptive = samples.stream().filter(s -> s.scenario().equals(profile.name()) && s.mode() == Mode.ADAPTIVE).mapToDouble(Sample::seconds).toArray();
            text.append(" | ").append(fixed.length == 0 || adaptive.length == 0 ? "pending" : String.format(Locale.ROOT, "%.3fx", BenchmarkStatistics.percentile(fixed, .5) / BenchmarkStatistics.percentile(adaptive, .5))).append(" |\n");
        }
        return text.append("\n[Samples](samples.csv), [profiles](profiles.csv), and [environment](environment.properties) retain conditions and byte/resource accounting. Each run also saves telemetry, controller decisions, request boundaries, and an independently hash-verified output. "
                + "Artifacts are committed incrementally; an interrupted report stays marked in progress. Ratios below 1 mean adaptive was slower.\n\n"
                + "Both parallel modes use 16 KiB buffers, eight in-flight buffers, a 1 MiB window maximum, 256 KiB minimum segments, one-second checkpoints, and at most four workers. "
                + "Single uses the same fixed settings with one worker. Adaptive starts at up to two with its existing measured trials and 1 MiB windows under this configured 1 MiB cap. Policy/window effects are measured together. "
                + "Shared engine caps remain eight per host, 32 globally, 64 MiB in-flight.\n\n"
                + "Profiles pace 4 KiB chunks before writes. A shared reservation budget counts all offered payload bytes, including retries; rate steps use reservation time relative to the first payload request. "
                + "Header delay applies to HEAD/probes and payload requests and is not a network RTT model. Dropped-body ordinals are deterministic HTTP response faults, not packet loss; requested windows differ by mode, so truncated byte counts can differ. "
                + "Offered bytes mean successful server writes and can exceed application-received bytes after cancellation; neither metric counts HTTP/TLS/IP overhead. Extra received bytes include replay and duplicate payload, not useful file bytes.\n\n"
                + "CPU/GC, sampled heap, and sampled RSS include the server and sampler in the same JVM. RSS is Linux-only; -1 means unavailable. CPU seconds can exceed wall seconds on multiple cores. "
                + "50 ms samples provide observed queue/heap/RSS/durability-lag peaks, not guaranteed maxima. Queued disk bytes count submitted unfinished write jobs in every engine mode. "
                + "The durability lag is acknowledged coverage minus flushed coverage and is not disk backlog.\n\n"
                + "The architecture's final acceptance matrix needs at least ten alternating AJDM/IDM runs on the same Windows host with explicit cold/warm conditions, actual latency/loss, CDN and media sources, and real 4G/5G. "
                + "This matrix supplies no IDM timings, packet-loss measurements, extraction/mux time, or physical link-utilization claim.\n").toString();
    }
}
