package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * One AJDM transfer for the emulated-network lab, using the same option path as the product core:
 * {@link TransferOptions#defaults()} plus the user's connection count in fixed or adaptive mode.
 * Prints one machine-readable result line; never prints the source URL.
 */
public final class LabDownload {
    private LabDownload() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: LabDownload <fixed|adaptive> <connections> <url> <output> <sha256>");
            System.exit(2);
        }
        boolean adaptive = switch (args[0]) {
            case "fixed" -> false;
            case "adaptive" -> true;
            default -> throw new IllegalArgumentException("mode");
        };
        int connections = Integer.parseInt(args[1]);
        Path target = Path.of(args[3]).toAbsolutePath().normalize();
        Path state = Files.createTempDirectory(target.getParent(), ".lab-state");
        var defaults = TransferOptions.defaults();
        var options = new TransferOptions(defaults.connectTimeout(), defaults.requestTimeout(), defaults.stallTimeout(),
                defaults.bufferBytes(), defaults.maxInflightBuffers(), defaults.windowBytes(), defaults.maxRetries(),
                defaults.retryBaseDelay(), defaults.retryMaxDelay(), args[4]).withParallelism(4, defaults.minSegmentBytes());
        options = adaptive ? options.withAdaptiveParallelism(connections, options.minSegmentBytes()) : options.withConnections(connections);
        long started = System.nanoTime();
        try (var engine = new AjdmEngine(state.resolve("state.db"))) {
            var handle = engine.start(new TransferRequest(UUID.randomUUID(), URI.create(args[2]), target, Map.of(), options));
            var result = handle.completion().toCompletableFuture().get(60, TimeUnit.MINUTES);
            double seconds = (System.nanoTime() - started) / 1e9;
            System.out.printf(Locale.ROOT, "LAB_RESULT transfer_seconds=%.3f bytes=%d received=%d sha256=%s target_connections=%d%n",
                    seconds, result.bytes(), handle.progress().receivedBytes(), result.sha256(),
                    adaptive ? handle.adaptation().targetConnections() : connections);
        } catch (java.util.concurrent.ExecutionException e) {
            System.err.println(e.getCause() instanceof DownloadException failure ? failure.userMessage() : "Download failed");
            System.exit(1);
        }
    }
}
