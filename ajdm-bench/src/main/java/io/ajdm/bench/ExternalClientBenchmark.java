package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.TransferRequest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Client-only capture for operator-run CDN/Windows comparisons. Never invokes IDM or extraction. */
public final class ExternalClientBenchmark {
    private ExternalClientBenchmark() {}
    public record Measurement(double seconds, long bytes, String sha256, long received, double cpuSeconds, long rssPeak) {}
    public static Measurement run(AjdmEngine engine, PerformanceMatrix.Mode mode, URI uri, Path suppliedTarget, String hash) throws Exception {
        return run(engine, mode, false, uri, suppliedTarget, hash);
    }
    /** {@code product}: the options the core gives a new direct download (see {@link #productOptions}), not the matrix tuning. */
    public static Measurement run(AjdmEngine engine, PerformanceMatrix.Mode mode, boolean product, URI uri, Path suppliedTarget, String hash) throws Exception {
        if (hash == null) throw new IllegalArgumentException("External benchmarks require an expected SHA-256");
        var options = product ? productOptions(hash, mode) : PerformanceMatrix.options(hash, mode);
        Path target = suppliedTarget.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        Path metrics = target.resolveSibling(target.getFileName() + ".benchmark.csv");
        Path telemetry = target.resolveSibling(target.getFileName() + ".telemetry.csv");
        if (Files.exists(target) || Files.exists(telemetry)) throw new java.nio.file.FileAlreadyExistsException("Benchmark output exists");
        Files.writeString(metrics, "mode,bytes,sha256,seconds,received_bytes,extra_received_bytes,process_cpu_seconds,sampled_heap_peak_bytes,sampled_rss_peak_bytes,sampled_queue_peak_bytes,sampled_durability_lag_peak_bytes,telemetry_samples\n", StandardOpenOption.CREATE_NEW);
        // A resident core finishes construction warm-up long before a user's first download (as resident IDM does);
        // per-download work, including the first request, stays inside the timer.
        engine.awaitWarmup(java.time.Duration.ofSeconds(10));
        long cpuBefore = TransferSampler.cpuNanos(), started = System.nanoTime();
        var handle = engine.start(new TransferRequest(UUID.randomUUID(), uri, target, Map.of(), options));
        Thread shutdown = Thread.ofPlatform().name("ajdm-benchmark-shutdown").unstarted(() -> {
            if (!handle.completion().toCompletableFuture().isDone()) {
                try { handle.pause().toCompletableFuture().join(); }
                catch (CompletionException ignored) { /* Main reports the transfer failure. */ }
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdown);
        var sampler = new TransferSampler(handle, null, started);
        io.ajdm.model.TransferResult result; double seconds; long cpuAfter;
        try {
            result = handle.completion().toCompletableFuture().get(30, TimeUnit.MINUTES);
            seconds = (System.nanoTime() - started) / 1e9; cpuAfter = TransferSampler.cpuNanos();
        } finally {
            try { sampler.finish(); Files.writeString(telemetry, sampler.csv(), StandardOpenOption.CREATE_NEW); }
            finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* JVM shutdown owns it. */ }
            }
        }
        double cpuSeconds = cpuBefore < 0 || cpuAfter < 0 ? -1 : (cpuAfter - cpuBefore) / 1e9;
        Files.writeString(metrics, String.format(Locale.ROOT, "%s,%d,%s,%.6f,%d,%d,%.6f,%d,%d,%d,%d,%d%n",
                mode.label(), result.bytes(), result.sha256(), seconds, handle.progress().receivedBytes(), handle.progress().receivedBytes() - result.bytes(),
                cpuSeconds, sampler.heapPeak(), sampler.rssPeak(), sampler.queuePeak(), sampler.durabilityLagPeak(), sampler.count()), StandardOpenOption.APPEND);
        System.out.printf(Locale.ROOT, "%s verified %,d bytes in %.6f s; metrics: %s%n", mode.label(), result.bytes(), seconds, metrics);
        return new Measurement(seconds, result.bytes(), result.sha256(), handle.progress().receivedBytes(), cpuSeconds, sampler.rssPeak());
    }
    /** Product default connections for a direct file: the desktop UI and IPC both send 16, adaptive only when chosen. */
    static final int PRODUCT_CONNECTIONS = 16;
    /** Mirrors CoreService's options for a new direct download; keep in sync with it. */
    static io.ajdm.model.TransferOptions productOptions(String hash, PerformanceMatrix.Mode mode) {
        if (mode == PerformanceMatrix.Mode.SINGLE) throw new IllegalArgumentException("Product settings are fixed or adaptive");
        var defaults = io.ajdm.model.TransferOptions.defaults();
        return new io.ajdm.model.TransferOptions(defaults.connectTimeout(), defaults.requestTimeout(), defaults.stallTimeout(),
                defaults.bufferBytes(), defaults.maxInflightBuffers(), defaults.windowBytes(), defaults.maxRetries(),
                defaults.retryBaseDelay(), defaults.retryMaxDelay(), hash, defaults.checkpointInterval(),
                PRODUCT_CONNECTIONS, defaults.minSegmentBytes(), mode == PerformanceMatrix.Mode.ADAPTIVE);
    }
}
