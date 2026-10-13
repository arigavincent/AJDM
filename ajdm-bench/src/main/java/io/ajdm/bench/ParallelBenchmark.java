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

/** Controlled comparison of AJDM modes only; no external downloader is invoked. */
public final class ParallelBenchmark {
    private ParallelBenchmark() {}
    private record Sample(int trial, int connections, long bytes, double seconds, long received, int peak, String sha256) {}
    public static void run(AjdmEngine engine, Path output) throws Exception {
        Path directory = output.toAbsolutePath().normalize(); Files.createDirectories(directory);
        try (var warmup = new FaultyRangeServer(65536, FaultyRangeServer.Mode.NORMAL)) {
            download(engine, warmup, directory.resolve("warmup.bin"), 1, 0);
        }
        int size = 4 * 1024 * 1024 + 17;
        long rate = 512 * 1024;
        List<Sample> samples = new ArrayList<>();
        for (int trial = 1; trial <= 3; trial++) {
            int[] order = trial % 2 == 1 ? new int[]{1, 4} : new int[]{4, 1};
            for (int count : order) {
                try (var server = new FaultyRangeServer(size, FaultyRangeServer.Mode.CAPPED, rate)) {
                    var sample = download(engine, server, directory.resolve("workers-" + count + "-" + trial + ".bin"), count, trial);
                    samples.add(sample);
                    System.out.printf(Locale.ROOT, "Trial %d, %d worker(s): %.3f s, %.3f MiB/s, SHA-256 verified%n",
                            trial, count, sample.seconds, sample.bytes / sample.seconds / 1048576.0);
                }
            }
        }
        var csv = new StringBuilder("trial,connections,bytes,seconds,mib_per_second,received_bytes,peak_responses,sha256\n");
        for (var sample : samples) csv.append(String.format(Locale.ROOT, "%d,%d,%d,%.6f,%.6f,%d,%d,%s%n",
                sample.trial, sample.connections, sample.bytes, sample.seconds, sample.bytes / sample.seconds / 1048576.0,
                sample.received, sample.peak, sample.sha256));
        Files.writeString(directory.resolve("samples.csv"), csv, StandardOpenOption.CREATE_NEW);
        double single = median(samples, 1), parallel = median(samples, 4);
        String report = String.format(Locale.ROOT, """
                # AJDM fixed-parallel local comparison

                Measured at %s, on %s, Java %s. Each transfer contains %,d bytes. The local server caps each response at %,d bytes/s; it does not impose a shared link cap. Three alternating trials follow a small warmup. Times include probing, transfer, checkpoints, full SHA-256 verification, and publication; engine/server construction is excluded.

                | Requested workers | Median seconds | Median MiB/s |
                |---|---:|---:|
                | 1 | %.3f | %.3f |
                | 4 | %.3f | %.3f |

                Measured completion-time speedup: %.2fx. All six outputs matched SHA-256; received bytes equal file bytes in each trial. [Raw samples](samples.csv) include per-run times and observed concurrent response counts. The deterministic file hash is `%s`.

                Transfer options: 16 KiB buffers, at most eight in-flight buffers per file, 1 MiB request windows, 256 KiB minimum segment size, and 1 s checkpoints. Shared engine limits: eight active responses per host, 32 globally, and 64 MiB of in-flight buffer bytes.

                This comparison measures AJDM's one-worker and fixed four-worker paths under a local per-response rate cap. Dynamic rebalancing, IDM comparisons, and real 4G/5G measurements remain future work.
                """, Instant.now(), System.getProperty("os.name"), System.getProperty("java.version"), size, rate,
                single, size / single / 1048576.0, parallel, size / parallel / 1048576.0, single / parallel, samples.getFirst().sha256);
        Files.writeString(directory.resolve("report.md"), report, StandardOpenOption.CREATE_NEW);
        System.out.printf(Locale.ROOT, "Median completion-time speedup: %.2fx. Report: %s%n", single / parallel, directory.resolve("report.md"));
    }
    private static Sample download(AjdmEngine engine, FaultyRangeServer server, Path target, int connections, int trial) throws Exception {
        var options = new TransferOptions(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(3),
                16384, 8, 1024 * 1024, 2, Duration.ofMillis(10), Duration.ofMillis(100), server.sha256(), Duration.ofSeconds(1))
                .withParallelism(connections, 256 * 1024);
        long started = System.nanoTime();
        var handle = engine.start(new TransferRequest(UUID.randomUUID(), server.uri(), target, Map.of(), options));
        var result = handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - started) / 1000000000.0;
        if (handle.progress().receivedBytes() != result.bytes()) throw new IllegalStateException("Unexpected repeated payload in controlled comparison");
        return new Sample(trial, connections, result.bytes(), seconds, handle.progress().receivedBytes(), server.peakTransfers(), result.sha256());
    }
    private static double median(List<Sample> samples, int count) {
        return samples.stream().filter(sample -> sample.connections == count).mapToDouble(Sample::seconds).sorted().toArray()[1];
    }
}
