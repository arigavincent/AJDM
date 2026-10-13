package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class RetryTransitionsTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("state/ajdm.db"); }
    private TransferRequest request(RetryTableServer server) throws Exception {
        return request(server, 0);
    }
    private TransferRequest request(RetryTableServer server, int retries) throws Exception {
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("result.bin"), Map.of(),
                new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                        4096, 2, 65536, retries, Duration.ZERO, Duration.ZERO, server.hash(), Duration.ofMillis(50)));
    }
    private DownloadException failed(TransferHandle handle) {
        return (DownloadException) assertThrows(ExecutionException.class, () -> handle.completion().toCompletableFuture().get()).getCause();
    }
    @ParameterizedTest @ValueSource(strings = {"WRONG_THEN_500", "PROBE_WRONG_THEN_500"})
    void malformedRangeRetryDoesNotConsumeTheNetworkRetryBudget(String mode) throws Exception {
        try (var server = new RetryTableServer(mode); var engine = new AjdmEngine(database())) {
            var result = engine.start(request(server, 1)).completion().toCompletableFuture().get();
            assertArrayEquals(server.data, Files.readAllBytes(result.target()));
        }
    }
    @ParameterizedTest @ValueSource(ints = {429, 500, 503})
    void temporaryStatusesUseTheConfiguredNetworkRetryBudget(int status) throws Exception {
        try (var server = new RetryTableServer("HTTP_" + status + "_ONCE"); var engine = new AjdmEngine(database())) {
            var result = engine.start(request(server, 1)).completion().toCompletableFuture().get();
            assertArrayEquals(server.data, Files.readAllBytes(result.target())); assertEquals(1, server.probes.get());
        }
    }
    @Test void retryAfterIsHonoredEvenWhenJitterIsZero() throws Exception {
        try (var server = new RetryTableServer("HTTP_429_SECONDS"); var engine = new AjdmEngine(database())) {
            long started = System.nanoTime();
            var result = engine.start(request(server, 1)).completion().toCompletableFuture().get();
            assertTrue(System.nanoTime() - started >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(900));
            assertArrayEquals(server.data, Files.readAllBytes(result.target()));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void parallelAndAdaptiveReprobesJoinOldWorkersAndKeepByteIdentity(boolean adaptive) throws Exception {
        for (String mode : new String[]{"416_ONCE", "HTTP_403_ONCE", "HTTP_410_ONCE"}) {
            Path run = directory.resolve(mode);
            try (var server = new RetryTableServer(mode); var engine = new AjdmEngine(run.resolve("state/ajdm.db"))) {
                var base = request(server);
                var options = adaptive ? base.options().withAdaptiveParallelism(4, 65536) : base.options().withParallelism(4, 65536);
                var request = new TransferRequest(base.id(), base.uri(), run.resolve("result.bin"), base.headers(), options);
                var result = engine.start(request).completion().toCompletableFuture().get();
                assertArrayEquals(server.data, Files.readAllBytes(result.target())); assertEquals(2, server.probes.get());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"WRONG_ONCE", "PROBE_WRONG_ONCE", "416_ONCE", "HTTP_403_ONCE", "HTTP_410_ONCE", "INITIAL_403_ONCE", "INITIAL_410_ONCE"})
    void boundedSpecialRecoveryCompletesEvenWithoutOrdinaryRetries(String mode) throws Exception {
        try (var server = new RetryTableServer(mode); var engine = new AjdmEngine(database())) {
            var result = engine.start(request(server)).completion().toCompletableFuture().get();
            assertArrayEquals(server.data, Files.readAllBytes(result.target()));
            assertEquals(mode.equals("WRONG_ONCE") ? 1 : 2, server.probes.get());
            assertTrue(server.transfers.get() <= 6);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"WRONG_ALWAYS", "PROBE_WRONG_ALWAYS", "416_ALWAYS", "HTTP_403_ALWAYS", "HTTP_410_ALWAYS", "INITIAL_403_ALWAYS", "INITIAL_410_ALWAYS", "HTTP_404_ALWAYS"})
    void persistentRejectionsTerminateWithoutPublishing(String mode) throws Exception {
        try (var server = new RetryTableServer(mode); var engine = new AjdmEngine(database())) {
            var handle = engine.start(request(server)); var error = failed(handle);
            ErrorCode expected = mode.contains("WRONG") || mode.startsWith("416") ? ErrorCode.PROTOCOL_VIOLATION
                    : mode.contains("404") ? ErrorCode.NOT_FOUND : ErrorCode.ACCESS_DENIED;
            assertEquals(expected, error.code()); assertEquals(DownloadState.FAILED, handle.progress().state());
            assertEquals(mode.startsWith("WRONG") || mode.contains("404") ? 1 : 2, server.probes.get());
            assertTrue(server.transfers.get() <= 2); assertFalse(Files.exists(directory.resolve("result.bin")));
        }
    }
    @Test void badLaterWindowRetainsPreviouslyVerifiedWindows() throws Exception {
        try (var server = new RetryTableServer("WRONG_LATE_ALWAYS"); var engine = new AjdmEngine(database())) {
            var handle = engine.start(request(server)); assertEquals(ErrorCode.PROTOCOL_VIOLATION, failed(handle).code());
            var saved = engine.downloads().getFirst();
            assertEquals(java.util.List.of(new ByteRange(0, 65536)), saved.completed()); assertEquals(3, server.transfers.get());
            assertArrayEquals(java.util.Arrays.copyOf(server.data, 65536), java.util.Arrays.copyOf(Files.readAllBytes(saved.partialPath()), 65536));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CHANGE", "416_SIZE", "416_ETAG"})
    void changedRepresentationPausesAndPersistsReasonWithoutPublishing(String mode) throws Exception {
        try (var server = new RetryTableServer(mode); var engine = new AjdmEngine(database())) {
            var handle = engine.start(request(server)); var error = failed(handle);
            assertEquals(ErrorCode.SOURCE_CHANGED, error.code()); assertEquals(DownloadState.PAUSED, handle.progress().state());
            var saved = engine.downloads().getFirst();
            assertEquals(DownloadState.PAUSED, saved.state()); assertEquals(ErrorCode.SOURCE_CHANGED, saved.error());
            assertEquals("\"v1\"", saved.probe().etag()); assertEquals(server.data.length, saved.probe().contentLength());
            assertFalse(Files.exists(directory.resolve("result.bin")));
            byte[] bytes = Files.readAllBytes(saved.partialPath());
            for (var range : saved.completed()) assertArrayEquals(java.util.Arrays.copyOfRange(server.data, (int) range.start(), (int) range.endExclusive()),
                    java.util.Arrays.copyOfRange(bytes, (int) range.start(), (int) range.endExclusive()));
            handle.pause().toCompletableFuture().get();
        }
        try (var engine = new AjdmEngine(database())) {
            assertEquals(DownloadState.PAUSED, engine.downloads().getFirst().state());
            assertEquals(ErrorCode.SOURCE_CHANGED, engine.downloads().getFirst().error());
        }
    }
    @ParameterizedTest @ValueSource(ints = {401, 407})
    void credentialsPauseOnceAndResumeWithCallerSuppliedHeaders(int status) throws Exception {
        try (var server = new RetryTableServer("HTTP_" + status + "_ALWAYS"); var engine = new AjdmEngine(database())) {
            var request = request(server); var handle = engine.start(request); var error = failed(handle);
            assertEquals(ErrorCode.ACCESS_DENIED, error.code()); assertTrue(error.userMessage().startsWith("AJDM-2010"));
            assertEquals(DownloadState.PAUSED, handle.progress().state()); assertEquals(1, server.transfers.get());
            assertEquals(ErrorCode.AUTH_REQUIRED, engine.downloads().getFirst().error());
            var result = engine.resume(request.id(), Map.of("Authorization", "Bearer supplied")).completion().toCompletableFuture().get();
            assertArrayEquals(server.data, Files.readAllBytes(result.target()));
            assertFalse(engine.downloads().getFirst().request().headers().containsKey("Authorization"));
        }
    }
    @Test void diskFailurePausesWithReasonAndKeepsVerifiedPublicationForResume() throws Exception {
        try (var server = new RetryTableServer("NORMAL")) {
            var request = request(server);
            try (var engine = new AjdmEngine(database(), (phase, id, bytes) -> {
                if (phase == CheckpointObserver.Phase.AFTER_FINAL_LINK) throw new java.io.IOException("Injected disk failure");
            })) {
                var handle = engine.start(request); assertEquals(ErrorCode.DISK_WRITE_ERROR, failed(handle).code());
                assertEquals(DownloadState.PAUSED, handle.progress().state());
                assertEquals(ErrorCode.DISK_WRITE_ERROR, engine.downloads().getFirst().error()); assertTrue(engine.downloads().getFirst().readyToPublish());
            }
            int requests = server.transfers.get();
            try (var engine = new AjdmEngine(database())) {
                var result = engine.resume(request.id()).completion().toCompletableFuture().get();
                assertArrayEquals(server.data, Files.readAllBytes(result.target())); assertEquals(requests, server.transfers.get());
            }
        }
    }
    @Test void concurrentPauseCannotHideTheSourceChangedDecision() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<TransferHandle>();
        try (var server = new RetryTableServer("CHANGE"); var engine = new AjdmEngine(database(), (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_DB_COMMIT) reference.get().pause();
        })) {
            var base = request(server);
            var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                    4096, 2, 65536, 0, Duration.ZERO, Duration.ZERO, server.hash(), Duration.ofSeconds(30));
            var handle = engine.start(new TransferRequest(base.id(), base.uri(), base.target(), base.headers(), options)); reference.set(handle);
            assertEquals(ErrorCode.SOURCE_CHANGED, failed(handle).code());
            assertEquals(ErrorCode.SOURCE_CHANGED, engine.downloads().getFirst().error());
            assertEquals(DownloadState.PAUSED, handle.progress().state());
        }
    }
}
