package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.EngineLimits;
import io.ajdm.engine.TransferHandle;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class AdaptiveIntegrationTest {
    @TempDir Path directory;
    @Test void slowDelayedSourceAvoidsExcessiveRangeRequestChurn() throws Exception {
        var profile = new NetworkProfile("slow-delayed", 2 * 1024 * 1024, 0,
                java.util.List.of(new NetworkProfile.RateStep(Duration.ZERO, 256 * 1024)),
                Duration.ofMillis(150), java.util.Set.of());
        try (var server = new BenchmarkServer(profile); var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(3),
                    4096, 8, 262144, 3, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50))
                    .withAdaptiveParallelism(2, 65536);
            long started = System.nanoTime();
            var handle = engine.start(new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("result.bin"), Map.of(), options));
            var result = handle.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256());
            assertEquals(profile.bytes(), result.bytes()); assertEquals(result.bytes(), handle.progress().receivedBytes());
            assertTrue(server.payloadRequests() <= 10,
                    () -> "A 2 MiB delayed source should not need " + server.payloadRequests() + " payload requests when 256 KiB windows are available");
            // Server handlers can outlive a fully received body while closing the exchange.
            // Measure the scheduler's actual worker target rather than handler cleanup overlap.
            assertEquals(2, handle.adaptation().workerBytesPerSecond().size());
            assertTrue(handle.adaptation().decisions().stream().allMatch(event -> event.connections() <= 2));
            System.out.printf(java.util.Locale.ROOT, "WINDOW_CHURN_RESULT: %d payload requests, %d verified bytes, %.3f seconds%n",
                    server.payloadRequests(), result.bytes(), (System.nanoTime() - started) / 1e9);
        }
    }
    /** A hosted-runner hang was not reproducible locally; leave the evidence needed to diagnose a recurrence. */
    private static TransferResult completeOrDump(TransferHandle handle, int seconds) throws Exception {
        try { return handle.completion().toCompletableFuture().get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            var dump = new StringBuilder("TRANSFER_TIMEOUT progress=").append(handle.progress())
                    .append(" queuedWriteBytes=").append(handle.queuedWriteBytes())
                    .append(" adaptation=").append(handle.adaptation()).append('\n');
            // The engine's workers are virtual threads, which only a HotSpot thread dump includes.
            Path threads = Files.createTempFile("ajdm-threads", ".txt"); Files.delete(threads);
            java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                    .dumpThreads(threads.toString(), com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            dump.append(Files.readString(threads)); Files.deleteIfExists(threads);
            System.err.print(dump);
            throw e;
        }
    }
    private TransferRequest request(FaultyRangeServer server, String name, int maximum) {
        var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(3),
                4096, 8, 262144, 3, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50))
                .withAdaptiveParallelism(maximum, 65536).withInitialConnections(2);
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve(name), Map.of(), options);
    }
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"NORMAL", "TRUNCATE_ONCE", "STALL_ONCE", "THROTTLE_ONCE",
            "IGNORE_RANGES_MIDWAY", "NO_RANGES", "NO_VALIDATOR", "UNKNOWN_LENGTH"})
    void adaptiveFaultsAndFallbackRemainBitExact(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024 + 17, mode);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var handle = engine.start(request(server, "result.bin", 4));
            var result = completeOrDump(handle, 20);
            assertEquals(server.sha256(), result.sha256()); assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertEquals(result.bytes(), handle.progress().durableBytes());
            assertTrue(handle.adaptation().decisions().size() <= 256);
        }
    }
    @Test void completedFastLaneStealsFromAProtectedSlowLaneWithoutDuplicateRequests() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var handle = engine.start(request(server, "result.bin", 2));
            // Let the fast lane finish its original share while the first requested window stays protected.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (handle.adaptation().decisions().stream().noneMatch(d -> d.reason().equals("tail-stolen"))
                    && System.nanoTime() < until) Thread.sleep(5);
            server.releaseFirstSegment();
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256());
            assertEquals(result.bytes(), handle.progress().receivedBytes());
            assertTrue(handle.adaptation().decisions().stream().anyMatch(d -> d.reason().equals("tail-stolen")));
            assertExclusiveCoverage(server, handle, result.bytes());
        }
    }
    @Test void sharedCapRejectsAnExtraLaneAndSettlesBackAtTwo() throws Exception {
        try (var server = new FaultyRangeServer(4 * 1024 * 1024, FaultyRangeServer.Mode.SHARED_CAPPED, 256 * 1024);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var handle = engine.start(request(server, "result.bin", 8));
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256()); assertExclusiveCoverage(server, handle, result.bytes());
            var decisions = handle.adaptation().decisions();
            assertTrue(decisions.stream().anyMatch(d -> d.reason().equals("trial-start")));
            assertTrue(decisions.stream().anyMatch(d -> d.reason().equals("trial-rollback")));
            assertEquals(1, decisions.stream().filter(d -> d.reason().equals("trial-start")).count());
            assertEquals(2, handle.adaptation().targetConnections()); assertTrue(server.peakTransfers() <= 4);
        }
    }
    @Test void capPerResponseAcceptsABeneficialConnectionTrial() throws Exception {
        try (var server = new FaultyRangeServer(8 * 1024 * 1024, FaultyRangeServer.Mode.CAPPED, 256 * 1024);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var handle = engine.start(request(server, "result.bin", 3));
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256()); assertExclusiveCoverage(server, handle, result.bytes());
            assertTrue(handle.adaptation().decisions().stream().anyMatch(d -> d.reason().equals("trial-accepted")),
                    () -> handle.adaptation().toString());
            assertEquals(3, handle.adaptation().targetConnections());
            // A server handler can still be closing after its complete body has reached the client.
            // Count the engine's worker slots and every published admission decision, not closing handlers.
            assertEquals(3, handle.adaptation().workerBytesPerSecond().size());
            assertTrue(handle.adaptation().decisions().stream().allMatch(event -> event.connections() <= 3));
        }
    }
    @Test void serverPushbackReducesAdmissionsWithoutLosingAcknowledgedBytes() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.PUSHBACK_CAPPED, 512 * 1024);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var handle = engine.start(request(server, "result.bin", 8));
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256()); assertExclusiveCoverage(server, handle, result.bytes());
            assertTrue(handle.adaptation().decisions().stream().anyMatch(d -> d.reason().equals("server-pushback")));
            assertEquals(1, handle.adaptation().targetConnections());
            assertEquals(result.bytes(), handle.progress().receivedBytes());
        }
    }
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"WRONG_RANGE", "WRONG_TOTAL", "ENCODED", "CHANGED_SOURCE", "CORRUPT_BODY", "TRUNCATE_ALWAYS"})
    void adaptiveProtocolAndIntegrityFailuresDoNotPublish(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(1024 * 1024 + 17, mode);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            var handle = engine.start(request(server, "result.bin", 4));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS));
            assertFalse(Files.exists(directory.resolve("result.bin")));
            assertEquals(0, handle.adaptation().busyWorkers());
        }
    }
    @Test void adaptiveMaximumRespectsTheSharedHostLimit() throws Exception {
        try (var server = new FaultyRangeServer(1024 * 1024, FaultyRangeServer.Mode.CAPPED, 512 * 1024);
             var engine = new AjdmEngine(directory.resolve("state.db"), new EngineLimits(1, 2, 1024 * 1024))) {
            var handle = engine.start(request(server, "result.bin", 8));
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256()); assertEquals(1, server.peakTransfers());
            assertEquals(1, handle.adaptation().targetConnections());
        }
    }
    @Test void adaptivePauseReopensFromOnlyDurableGaps() throws Exception {
        try (var server = new FaultyRangeServer(4 * 1024 * 1024, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT)) {
            var request = request(server, "result.bin", 4);
            DownloadRecord saved;
            try (var engine = new AjdmEngine(directory.resolve("state.db"))) {
                var handle = engine.start(request);
                // Leaves as soon as 64 KiB are durable; the cap only absorbs a cold or busy JVM (250 ms failed on Windows).
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (handle.progress().durableBytes() < 65536 && System.nanoTime() < until) Thread.sleep(5);
                assertTrue(handle.progress().durableBytes() >= 65536);
                handle.pause().toCompletableFuture().get(5, TimeUnit.SECONDS);
                saved = engine.downloads().getFirst(); assertTrue(saved.request().options().adaptive());
                assertEquals(0, saved.prefixLength()); assertTrue(saved.durableEnd() > saved.durableBytes());
            }
            server.releaseFirstSegment();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (server.activeTransfers() > 0 && System.nanoTime() < until) Thread.sleep(5);
            int before = server.requestedRanges().size();
            try (var engine = new AjdmEngine(directory.resolve("state.db"))) {
                var handle = engine.resume(request.id());
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(server.sha256(), result.sha256());
                assertEquals(result.bytes() - saved.durableBytes(), handle.progress().receivedBytes());
                ParallelIntegrationTest.assertNoSavedBytesRequested(saved.completed(), server.requestedRanges().subList(before, server.requestedRanges().size()));
            }
        }
    }
    private static void assertExclusiveCoverage(FaultyRangeServer server, TransferHandle handle, long total) {
        // Cutting an active window (architecture section 8) re-requests the victim's unread tail from the thief, so
        // requests may overlap. The victim stops reading at the cut; only a read racing the cut is discarded.
        assertEquals(total, new RangeSet(server.requestedRanges()).coveredBytes());
        long discarded = handle.progress().receivedBytes() - total;
        assertTrue(discarded >= 0 && discarded <= total / 32, () -> "Discarded " + discarded + " received bytes");
    }
}
