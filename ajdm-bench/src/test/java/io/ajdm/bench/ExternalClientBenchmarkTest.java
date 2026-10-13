package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ExternalClientBenchmarkTest {
    @TempDir Path directory;
    @ParameterizedTest @EnumSource(PerformanceMatrix.Mode.class) @Timeout(15)
    void clientOnlyCaptureUsesVerifiedCompletionAndProtectsArtifacts(PerformanceMatrix.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            Path target = directory.resolve("result.bin");
            var measurement = ExternalClientBenchmark.run(engine, mode, server.uri(), target, server.sha256());
            assertEquals(server.sha256(), measurement.sha256()); assertEquals(320017, measurement.bytes());
            assertEquals(measurement.bytes(), measurement.received()); assertTrue(measurement.seconds() > 0);
            assertArrayEquals(server.data(), Files.readAllBytes(target));
            assertEquals(2, Files.readAllLines(directory.resolve("result.bin.benchmark.csv")).size());
            assertTrue(Files.readString(directory.resolve("result.bin.telemetry.csv")).contains(",-1,"));
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> ExternalClientBenchmark.run(engine, mode, server.uri(), target, server.sha256()));
        }
    }
}
