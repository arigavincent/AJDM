package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SourceRefreshTest {
    @TempDir Path directory;
    private TransferRequest request(ExpiringSourceServer server, boolean adaptive) throws Exception {
        var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(500),
                4096, 4, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(),
                Duration.ofMillis(50), 4, 65536, adaptive);
        return new TransferRequest(UUID.randomUUID(), server.uri(false), directory.resolve("download.bin"),
                Map.of("Authorization", "Bearer old-secret", "X-Old-Credential", "must-disappear"), options);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredWorkerGenerationRefreshesOnceAndRetainsOnlyVerifiedRanges(boolean adaptive) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var calls = new AtomicInteger();
            var retained = new AtomicReference<List<ByteRange>>();
            var handle = engine.start(request(server, adaptive), context -> {
                calls.incrementAndGet();
                assertEquals(403, context.httpStatus());
                assertEquals(1, context.attempt());
                retained.set(engine.downloads().getFirst().completed());
                return server.replacement();
            });
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); assertEquals(0, server.unauthorized.get());
            assertArrayEquals(server.data, Files.readAllBytes(result.target())); assertEquals(server.sha256(), result.sha256());
            assertFalse(retained.get().isEmpty(), "Refresh should keep already acknowledged windows");
            for (var range : server.replacementRanges) for (var saved : retained.get()) {
                assertTrue(range.endExclusive() <= saved.start() || range.start() >= saved.endExclusive(), "Durable bytes must not be downloaded again");
            }
            var saved = engine.downloads().getFirst();
            assertEquals(server.uri(true), saved.request().uri());
            assertEquals(Map.of("User-Agent", "AJDM-refresh-test"), saved.request().headers());
        }
    }
    @Test void initialExpiredProbeCanObtainADirectSource() throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.initiallyExpired = true;
            var calls = new AtomicInteger();
            var result = engine.start(request(server, false), context -> {
                calls.incrementAndGet(); assertTrue(context.previousProbe().isEmpty()); return server.replacement();
            }).completion().toCompletableFuture().get();
            assertEquals(1, calls.get()); assertEquals(server.sha256(), result.sha256());
        }
    }
    @Test void withoutARefresherExpirationStillHasTheOriginalFailure() throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.initiallyExpired = true;
            var failure = assertThrows(ExecutionException.class, () -> engine.start(request(server, false)).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.ACCESS_DENIED, ((DownloadException) failure.getCause()).code());
        }
    }
    private static DownloadException failed(TransferHandle handle) throws Exception {
        var failure = assertThrows(ExecutionException.class, () -> handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
        return assertInstanceOf(DownloadException.class, failure.getCause());
    }
    @ParameterizedTest @ValueSource(strings = {"etag", "size", "no-ranges", "weak", "missing"})
    void replacementThatCannotProveIdentityNeverReusesBytesOrChangesSavedSource(String mode) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            switch (mode) {
                case "etag" -> server.replacementEtag = "\"different\"";
                case "size" -> server.replacementSize--;
                case "no-ranges" -> server.replacementRangesSupported = false;
                case "weak" -> { server.oldEtag = "W/\"stable-file\""; server.replacementEtag = server.oldEtag; }
                case "missing" -> server.replacementEtag = null;
                default -> throw new AssertionError(mode);
            }
            var request = request(server, false);
            var retained = new AtomicReference<List<ByteRange>>();
            var failure = failed(engine.start(request, context -> {
                retained.set(engine.downloads().getFirst().completed()); return server.replacement();
            }));
            var expected = switch (mode) {
                case "etag", "size" -> ErrorCode.SOURCE_CHANGED;
                case "no-ranges" -> ErrorCode.REFRESH_UNSUPPORTED;
                default -> ErrorCode.REFRESH_IDENTITY_UNVERIFIED;
            };
            assertEquals(expected, failure.code()); assertFalse(retained.get().isEmpty());
            var saved = engine.downloads().getFirst();
            assertEquals(request.uri(), saved.request().uri()); assertEquals(retained.get(), saved.completed());
            assertEquals(0, server.replacementPayloads.get()); assertFalse(Files.exists(request.target()));
        }
    }
    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 410})
    void refreshesOnlySupportedDirectSourceRejections(int status) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.initiallyExpired = true; server.rejection = status;
            var calls = new AtomicInteger();
            var result = engine.start(request(server, false), context -> {
                assertEquals(status, context.httpStatus()); calls.incrementAndGet(); return server.replacement();
            }).completion().toCompletableFuture().get();
            assertEquals(1, calls.get()); assertEquals(server.sha256(), result.sha256());
        }
    }
    @ParameterizedTest @ValueSource(ints = {407, 416, 400, 429, 503})
    void proxyAuthenticationSourceChangesAndRetryablePushbackDoNotInvokeExtraction(int status) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.initiallyExpired = true; server.rejection = status;
            var calls = new AtomicInteger();
            var failure = failed(engine.start(request(server, false), context -> { calls.incrementAndGet(); return server.replacement(); }));
            assertEquals(0, calls.get());
            assertEquals(status == 407 ? ErrorCode.ACCESS_DENIED : status == 416 ? ErrorCode.SOURCE_CHANGED
                    : status == 400 ? ErrorCode.PROTOCOL_VIOLATION : ErrorCode.RETRIES_EXHAUSTED, failure.code());
        }
    }
    @Test void replacementExpirationSharesABoundedAttemptBudget() throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.initiallyExpired = true; server.replacementExpired = true;
            var calls = new AtomicInteger();
            var request = request(server, false);
            var failure = failed(engine.start(request, context -> {
                int call = calls.incrementAndGet(); assertEquals(call, context.attempt());
                assertEquals(server.uri(call != 1), context.failedSource().uri()); return server.replacement();
            }, new SourceRefreshPolicy(2, Duration.ofSeconds(1))));
            assertEquals(ErrorCode.REFRESH_EXHAUSTED, failure.code()); assertEquals(2, calls.get());
            assertEquals(request.uri(), engine.downloads().getFirst().request().uri());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"null", "exception", "invalid"})
    void callbackFailuresHaveSafeDiagnosticsAndKeepDurableRanges(String mode) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var request = request(server, false);
            var failure = failed(engine.start(request, context -> {
                assertFalse(context.toString().contains("secret"));
                return switch (mode) {
                    case "null" -> null;
                    case "exception" -> throw new java.io.IOException("Bearer sensitive-secret signed-url");
                    default -> new DownloadSource(java.net.URI.create("file:///tmp/sensitive-secret"), Map.of());
                };
            }));
            assertEquals(ErrorCode.REFRESH_FAILED, failure.code()); assertNull(failure.getCause());
            assertFalse(failure.toString().contains("secret")); assertFalse(Files.exists(request.target()));
            assertTrue(engine.downloads().getFirst().durableBytes() > 0);
        }
    }
    private static void awaitIgnoringInterrupts(java.util.concurrent.CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pauseAndCancelStopWaitingAndNeverCommitALateCallback(boolean pause) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var contextSeen = new AtomicReference<SourceRefreshContext>();
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var request = request(server, false);
            var handle = engine.start(request, context -> {
                contextSeen.set(context); entered.countDown();
                try { awaitIgnoringInterrupts(release); return server.replacement(); }
                finally { exited.countDown(); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var retained = engine.downloads().getFirst().completed();
                if (pause) handle.pause().toCompletableFuture().get(2, TimeUnit.SECONDS); else handle.cancel();
                assertEquals(pause ? ErrorCode.PAUSED : ErrorCode.CANCELED, failed(handle).code());
                assertTrue(contextSeen.get().isCanceled());
                assertThrows(DownloadException.class, () -> contextSeen.get().checkCanceled());
                release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS));
                assertEquals(request.uri(), engine.downloads().getFirst().request().uri());
                assertEquals(retained, engine.downloads().getFirst().completed()); assertFalse(Files.exists(request.target()));
            } finally { release.countDown(); }
        }
    }
    @Test void timeoutBoundsWaitingAndBlocksOverlappingCallbacksOnResume() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.initiallyExpired = true;
            var request = request(server, false);
            var policy = new SourceRefreshPolicy(2, Duration.ofMillis(150));
            try {
                var first = engine.start(request, context -> {
                    entered.countDown();
                    try { awaitIgnoringInterrupts(release); return server.replacement(); }
                    finally { exited.countDown(); }
                }, policy);
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(ErrorCode.REFRESH_TIMEOUT, failed(first).code());
                var secondCalls = new AtomicInteger();
                var second = engine.resume(request.id(), Map.of(), context -> { secondCalls.incrementAndGet(); return server.replacement(); }, policy);
                assertEquals(ErrorCode.REFRESH_TIMEOUT, failed(second).code()); assertEquals(0, secondCalls.get());
                assertEquals(request.uri(), engine.downloads().getFirst().request().uri());
                release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS));
                var result = engine.resume(request.id()).completion().toCompletableFuture().get();
                assertEquals(1, secondCalls.get()); assertEquals(server.sha256(), result.sha256());
            } finally { release.countDown(); }
        }
    }

    @Test void sameEnginePauseDuringRefreshRetainsProviderForPlainResume() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var request = request(server, true);
            var handle = engine.start(request, context -> {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown(); new java.util.concurrent.CountDownLatch(1).await();
                }
                return server.replacement();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS)); handle.pause().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var saved = engine.downloads().getFirst(); assertTrue(saved.durableBytes() > 0);
            server.initiallyExpired = true;
            var resumed = engine.resume(request.id());
            var result = resumed.completion().toCompletableFuture().get();
            assertEquals(2, calls.get()); assertEquals(server.sha256(), result.sha256());
            assertEquals(result.bytes() - saved.durableBytes(), resumed.progress().receivedBytes());
            ParallelIntegrationTest.assertNoSavedBytesRequested(saved.completed(), server.replacementRanges);
        }
    }
    @Test void freshEngineCanReattachExtractionAndRefreshAnExpiredSavedSource() throws Exception {
        Path database = directory.resolve("state/ajdm.db");
        try (var server = new ExpiringSourceServer()) {
            var request = request(server, false);
            List<ByteRange> retained;
            try (var engine = new AjdmEngine(database)) {
                assertEquals(ErrorCode.ACCESS_DENIED, failed(engine.start(request)).code());
                retained = engine.downloads().getFirst().completed(); assertFalse(retained.isEmpty());
            }
            server.initiallyExpired = true;
            try (var engine = new AjdmEngine(database)) {
                var calls = new AtomicInteger();
                var handle = engine.resume(request.id(), context -> { calls.incrementAndGet(); return server.replacement(); });
                var result = handle.completion().toCompletableFuture().get();
                assertEquals(1, calls.get()); assertEquals(server.sha256(), result.sha256());
                ParallelIntegrationTest.assertNoSavedBytesRequested(retained, server.replacementRanges);
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"TRUNCATE_ONCE", "STALL_ONCE", "THROTTLE_ONCE"})
    void adaptivePayloadRetriesStillWorkAfterAValidatedRefresh(String fault) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.replacementFault = ExpiringSourceServer.Fault.valueOf(fault);
            var calls = new AtomicInteger(); var retained = new AtomicReference<List<ByteRange>>();
            var handle = engine.start(request(server, true), context -> {
                calls.incrementAndGet(); retained.set(engine.downloads().getFirst().completed()); return server.replacement();
            });
            var result = handle.completion().toCompletableFuture().get();
            assertEquals(1, calls.get()); assertEquals(server.sha256(), result.sha256());
            assertArrayEquals(server.data, Files.readAllBytes(result.target()));
            ParallelIntegrationTest.assertNoSavedBytesRequested(retained.get(), server.replacementRanges);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"TRUNCATE_ALWAYS", "STALL_ALWAYS"})
    void retryExhaustionAfterRefreshKeepsVerifiedCoverageAndNeverPublishes(String fault) throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            server.replacementFault = ExpiringSourceServer.Fault.valueOf(fault);
            var request = request(server, false); var calls = new AtomicInteger();
            var failure = failed(engine.start(request, context -> { calls.incrementAndGet(); return server.replacement(); }));
            assertEquals(ErrorCode.RETRIES_EXHAUSTED, failure.code()); assertEquals(1, calls.get());
            assertEquals(server.uri(true), engine.downloads().getFirst().request().uri());
            assertTrue(engine.downloads().getFirst().durableBytes() > 0); assertFalse(Files.exists(request.target()));
            assertTrue(server.replacementPayloads.get() <= 12, "Four workers must obey the configured retry budget");
        }
    }
    @Test void checkpointDiskFailureDoesNotTriggerExtractionOrClaimUnforcedProgress() throws Exception {
        try (var server = new ExpiringSourceServer(); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"), (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.BEFORE_FILE_FORCE && bytes > 0) throw new java.io.IOException("Injected no space left on device");
        })) {
            var request = request(server, false); var calls = new AtomicInteger();
            var failure = failed(engine.start(request, context -> { calls.incrementAndGet(); return server.replacement(); }));
            assertEquals(ErrorCode.DISK_WRITE_ERROR, failure.code()); assertEquals(0, calls.get());
            assertEquals(0, engine.downloads().getFirst().durableBytes()); assertEquals(request.uri(), engine.downloads().getFirst().request().uri());
            assertFalse(Files.exists(request.target()));
        }
    }
    @Test void audioAndVideoRefreshAsOrdinaryTransfersUnderTheSameResourceCaps() throws Exception {
        try (var expired = new ExpiringSourceServer();
             var payload = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT);
             var engine = new AjdmEngine(directory.resolve("state/ajdm.db"), new EngineLimits(2, 2, 1024 * 1024))) {
            expired.initiallyExpired = true;
            var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5),
                    4096, 2, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), payload.sha256(),
                    Duration.ofMillis(50), 4, 65536, true);
            var calls = new AtomicInteger();
            SourceRefresher provider = context -> { calls.incrementAndGet(); return new DownloadSource(payload.uri(), Map.of()); };
            var audio = engine.start(new TransferRequest(UUID.randomUUID(), expired.uri(false), directory.resolve("audio.bin"), Map.of(), options), provider);
            var video = engine.start(new TransferRequest(UUID.randomUUID(), expired.uri(false), directory.resolve("video.bin"), Map.of(), options), provider);
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (payload.activeTransfers() < 2 && System.nanoTime() < until) Thread.sleep(10);
                assertEquals(2, payload.activeTransfers()); assertTrue(payload.peakTransfers() <= 2);
                payload.releaseFirstSegment();
                assertEquals(payload.sha256(), audio.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).sha256());
                assertEquals(payload.sha256(), video.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).sha256());
                assertEquals(2, calls.get());
            } finally { payload.releaseFirstSegment(); }
        }
    }

}
