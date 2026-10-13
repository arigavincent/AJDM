package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceMatrixTest {
    @TempDir Path directory;
    @Test void sixRoundsBalanceEveryPositionAndPairwiseOrder() {
        var permutations = new HashSet<List<PerformanceMatrix.Mode>>();
        for (int round = 1; round <= 6; round++) permutations.add(PerformanceMatrix.order(round));
        assertEquals(6, permutations.size());
        for (var mode : PerformanceMatrix.Mode.values()) for (int position = 0; position < 3; position++) {
            int index = position;
            assertEquals(2, permutations.stream().filter(order -> order.get(index) == mode).count());
        }
        assertThrows(IllegalArgumentException.class, () -> PerformanceMatrix.order(0));
    }
    @Test @Timeout(30) void matrixKeepsCorrectOutputsTelemetryAndRetryOverhead() throws Exception {
        Path output = directory.resolve("report");
        var profile = new NetworkProfile("test-drops", 1024 * 1024 + 17, 0, List.of(), Duration.ofMillis(2), Set.of(2));
        try (var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            PerformanceMatrix.run(engine, output, 1, List.of(profile));
        }
        assertTrue(Files.readString(output.resolve("report.md")).contains("Status: complete"));
        List<String> rows = Files.readAllLines(output.resolve("samples.csv")); assertEquals(4, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            String[] fields = rows.get(i).split(",", -1);
            assertEquals(26, fields.length); assertEquals(1024 * 1024 + 17, Long.parseLong(fields[4]));
            assertTrue(Long.parseLong(fields[7]) >= Long.parseLong(fields[4]));
            assertEquals(1, Integer.parseInt(fields[13]));
            String name = "test-drops-1-" + fields[3];
            assertEquals(Long.parseLong(fields[4]), Files.size(output.resolve(name + ".bin")));
            assertTrue(Files.size(output.resolve(name + "-telemetry.csv")) > 0);
            assertTrue(Files.size(output.resolve(name + "-requests.csv")) > 0);
        }
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> {
            try (var engine = new AjdmEngine(directory.resolve("different.db"))) {
                PerformanceMatrix.run(engine, output, 1, List.of(profile));
            }
        });
    }
}
