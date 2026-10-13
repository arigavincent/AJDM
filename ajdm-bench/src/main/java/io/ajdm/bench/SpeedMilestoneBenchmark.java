package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One independently hashed local transfer, suitable for alternating different AJDM builds. */
public final class SpeedMilestoneBenchmark {
    private SpeedMilestoneBenchmark() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("scenario mode fresh-output-directory");
        System.setProperty("ajdm.hostLearning.disabled", "true");
        Path root = Path.of(args[2]); Files.createDirectory(root);
        String name = args[0], mode = args[1];
        boolean growth = name.equals("growth"), laggard = name.equals("laggard");
        int connections = growth ? 8 : 4;
        NetworkProfile profile = growth ? new NetworkProfile("growth", 32 * 1024 * 1024 + 17,
                512 * 1024, List.of(), Duration.ofMillis(20), Set.of()) : laggard ? null : NetworkProfile.named(name);
        try (var engine = new AjdmEngine(root.resolve("state.db"));
             var server = profile == null ? null : new BenchmarkServer(profile);
             var slow = laggard ? new FaultyRangeServer(64 * 1024 * 1024 + 17, FaultyRangeServer.Mode.CHANGING_LANE, 2 * 1024 * 1024) : null) {
            String expected = laggard ? slow.sha256() : server.sha256();
            var uri = laggard ? slow.uri() : server.uri();
            var base = new TransferOptions(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15),
                    16384, 8, 1024 * 1024, 10, Duration.ofMillis(10), Duration.ofMillis(100), expected, Duration.ofSeconds(1));
            var options = mode.equals("adaptive") ? base.withAdaptiveParallelism(connections, 256 * 1024) : base.withParallelism(connections, 256 * 1024);
            Path target = root.resolve("payload.bin");
            long started = System.nanoTime();
            var handle = engine.start(new TransferRequest(UUID.randomUUID(), uri, target, Map.of(), options));
            var result = handle.completion().toCompletableFuture().get(180, TimeUnit.SECONDS);
            double elapsed = (System.nanoTime() - started) / 1e9;
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(target)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equals(actual) || Files.size(target) != result.bytes()) throw new IllegalStateException("Independent verification failed");
            var trace = new StringBuilder("elapsed_ms,connections,reason,bytes_per_second\n");
            for (var decision : handle.adaptation().decisions()) trace.append(String.format(Locale.ROOT, "%d,%d,%s,%.3f%n",
                    decision.elapsedMillis(), decision.connections(), decision.reason(), decision.usefulBytesPerSecond()));
            Files.writeString(root.resolve("decisions.csv"), trace);
            int requests = laggard ? slow.requestedRanges().size() : server.payloadRequests();
            int peak = laggard ? slow.peakTransfers() : server.peakResponses();
            String row = String.format(Locale.ROOT, "%s,%s,%d,%.6f,%d,%d,%d,%s%n", name, mode, result.bytes(), elapsed,
                    handle.progress().receivedBytes(), requests, peak, actual);
            Files.writeString(root.resolve("sample.csv"), "scenario,mode,bytes,seconds,received_bytes,payload_requests,peak_responses,sha256\n" + row);
            System.out.print(row);
        }
    }
}
