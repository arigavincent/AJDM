package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.TransferHandle;
import io.ajdm.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Development runner; the production CLI, IPC, and media picker are later milestones. */
public final class EngineRunner {
    private EngineRunner() {}
    public static void main(String[] supplied) throws Exception {
        String[] args = supplied;
        Path database = AjdmEngine.defaultDatabase();
        if (args.length >= 2 && "--state".equals(args[0])) {
            database = Path.of(args[1]); args = Arrays.copyOfRange(args, 2, args.length);
        }
        if (args.length == 0 || "help".equals(args[0])) {
            System.out.println("AJDM: [--state <database>] demo <output> | download <http(s)-url> <output> [sha256] | resume <id> | list | adaptive <http(s)-url> <output> [sha256] | compare <new-report-directory> | compare-adaptive <new-report-directory> | benchmark-matrix <new-report-directory> [rounds] | serve-benchmark <profile> [port] | benchmark-download <single|fixed|adaptive> <url> <output> <sha256> [product]");
            return;
        }
        if ("serve-benchmark".equals(args[0]) && (args.length == 2 || args.length == 3)) {
            try (var server = new BenchmarkServer(NetworkProfile.named(args[1]), args.length == 3 ? Integer.parseInt(args[2]) : 0)) {
                System.out.println("Source: " + server.uri()); System.out.println("Bytes: " + NetworkProfile.named(args[1]).bytes());
                System.out.println("SHA-256: " + server.sha256()); System.out.println("Fresh fixture; restart this process before each external trial. Ctrl+C stops it.");
                Thread shutdown = Thread.ofPlatform().unstarted(server::close);
                Runtime.getRuntime().addShutdownHook(shutdown);
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { /* Shutdown owns it. */ } }
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid benchmark profile or port"); System.exit(2);
            } catch (IOException e) {
                System.err.println("Could not start benchmark fixture"); System.exit(1);
            }
            return;
        }
        try (var engine = new AjdmEngine(database)) {
            if ("benchmark-download".equals(args[0]) && (args.length == 5 || args.length == 6 && "product".equals(args[5]))) {
                ExternalClientBenchmark.run(engine, PerformanceMatrix.Mode.valueOf(args[1].toUpperCase(java.util.Locale.ROOT)),
                        args.length == 6, URI.create(args[2]), Path.of(args[3]), args[4]);
            } else if ("benchmark-matrix".equals(args[0]) && (args.length == 2 || args.length == 3)) {
                PerformanceMatrix.run(engine, Path.of(args[1]), args.length == 3 ? Integer.parseInt(args[2]) : 6);
            } else if ("compare-adaptive".equals(args[0]) && args.length == 2) {
                AdaptiveBenchmark.run(engine, Path.of(args[1]));
            } else if ("adaptive".equals(args[0]) && (args.length == 3 || args.length == 4)) {
                var fixed = request(URI.create(args[1]), Path.of(args[2]), args.length == 4 ? args[3] : null);
                await(engine.start(new TransferRequest(fixed.id(), fixed.uri(), fixed.target(), fixed.headers(),
                        fixed.options().withAdaptiveParallelism(4, 256 * 1024))));
            } else if ("compare".equals(args[0]) && args.length == 2) {
                ParallelBenchmark.run(engine, Path.of(args[1]));
            } else if ("demo".equals(args[0]) && args.length == 2) {
                try (var server = new FaultyRangeServer(8 * 1024 * 1024 + 17, FaultyRangeServer.Mode.NORMAL)) {
                    await(engine.start(request(server.uri(), Path.of(args[1]), server.sha256())));
                }
            } else if ("download".equals(args[0]) && (args.length == 3 || args.length == 4)) {
                await(engine.start(request(URI.create(args[1]), Path.of(args[2]), args.length == 4 ? args[3] : null)));
            } else if ("resume".equals(args[0]) && args.length == 2) {
                await(engine.resume(UUID.fromString(args[1])));
            } else if ("list".equals(args[0]) && args.length == 1) {
                for (var record : engine.downloads()) {
                    long total = record.probe() == null ? -1 : record.probe().contentLength();
                    System.out.println(record.request().id() + " " + record.state() + " " + record.durableBytes()
                            + "/" + (total < 0 ? "?" : total) + " " + record.request().target());
                }
            } else {
                System.err.println("Invalid command. Run help for usage."); System.exit(2);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Parsing and transport exceptions can contain signed URLs; do not print their payloads.
            System.err.println("Invalid arguments or download state"); System.exit(2);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DownloadException failure) System.err.println(failure.userMessage());
            else System.err.println("Download failed");
            System.exit(1);
        } catch (IOException e) {
            if (e instanceof DownloadException failure) System.err.println(failure.userMessage());
            else System.err.println("Could not access download files or state");
            System.exit(1);
        }
    }
    private static TransferRequest request(URI uri, Path target, String hash) {
        var defaults = TransferOptions.defaults();
        var options = new TransferOptions(defaults.connectTimeout(), defaults.requestTimeout(), defaults.stallTimeout(),
                defaults.bufferBytes(), defaults.maxInflightBuffers(), defaults.windowBytes(), defaults.maxRetries(),
                defaults.retryBaseDelay(), defaults.retryMaxDelay(), hash, defaults.checkpointInterval(),
                defaults.connections(), defaults.minSegmentBytes());
        return new TransferRequest(UUID.randomUUID(), uri, target, Map.of(), options);
    }
    private static void await(TransferHandle handle) throws Exception {
        System.out.println("Download ID: " + handle.id());
        Thread shutdown = Thread.ofPlatform().name("ajdm-shutdown").unstarted(() -> {
            if (!handle.completion().toCompletableFuture().isDone()) {
                try { handle.pause().toCompletableFuture().join(); }
                catch (CompletionException ignored) { /* Main reports any checkpoint failure. */ }
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            var result = handle.completion().toCompletableFuture().get();
            System.out.println("Saved " + result.bytes() + " bytes to " + result.target());
            for (var decision : handle.adaptation().decisions()) System.out.printf(java.util.Locale.ROOT,
                    "Adaptive %d ms: %s, target %d, %.0f useful bytes/s%n", decision.elapsedMillis(), decision.reason(),
                    decision.connections(), decision.usefulBytesPerSecond());
            System.out.println(result.sha256() == null ? "Size verified" : "SHA-256 verified: " + result.sha256());
        } catch (ExecutionException e) {
            handle.partialPath().ifPresent(part -> System.err.println("Partial file retained: " + part)); throw e;
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM shutdown already owns the hook. */ }
        }
    }
}
