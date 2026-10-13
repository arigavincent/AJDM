package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class PreallocationTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(ints = {0, 320017})
    void knownSizeIsExtendedBeforePayloadWithoutClaimingAnyCoverage(int size) throws Exception {
        AtomicBoolean allocated = new AtomicBoolean();
        try (var server = new FaultyRangeServer(size, FaultyRangeServer.Mode.NORMAL)) {
            CheckpointObserver observer = (phase, id, bytes) -> {
                if (phase.name().equals("AFTER_PREALLOCATION")) {
                    allocated.set(true);
                    try (var files = Files.list(directory)) {
                        Path part = files.filter(p -> p.toString().endsWith(".ajdmpart")).findFirst().orElseThrow();
                        assertEquals(server.data().length, Files.size(part));
                    }
                    assertEquals(0, bytes); assertEquals(0, server.transferRequests());
                    throw new java.io.IOException("Injected stop after allocation");
                }
            };
            var request = TransferRequest.of(server.uri(), directory.resolve("result.bin"));
            try (var engine = new AjdmEngine(directory.resolve("state/ajdm.db"), observer)) {
                assertThrows(ExecutionException.class, () -> engine.start(request).completion().toCompletableFuture().get());
                assertTrue(allocated.get());
                assertEquals(0, engine.downloads().getFirst().durableBytes());
                assertTrue(engine.downloads().getFirst().completed().isEmpty());
            }
            try (var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
                var handle = engine.resume(request.id());
                var result = handle.completion().toCompletableFuture().get();
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(server.data().length, handle.progress().receivedBytes());
            }
        }
    }

    @Test void unknownSizeGrowsNormallyWithoutPreallocation() throws Exception {
        AtomicBoolean allocated = new AtomicBoolean();
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.UNKNOWN_LENGTH);
             var engine = new AjdmEngine(directory.resolve("state/ajdm.db"),
                     (phase, id, bytes) -> { if (phase.name().equals("AFTER_PREALLOCATION")) allocated.set(true); })) {
            var result = engine.start(TransferRequest.of(server.uri(), directory.resolve("result.bin")))
                    .completion().toCompletableFuture().get();
            assertFalse(allocated.get()); assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
        }
    }
}
