package io.ajdm.bench;

import io.ajdm.model.TransferOptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonSettingsTest {
    private static final String HASH = "a".repeat(64);
    @Test void productSettingsAreTheCoreDefaultsWithSixteenConnections() {
        var defaults = TransferOptions.defaults();
        for (var mode : new PerformanceMatrix.Mode[]{PerformanceMatrix.Mode.FIXED, PerformanceMatrix.Mode.ADAPTIVE}) {
            var product = ExternalClientBenchmark.productOptions(HASH, mode);
            assertEquals(16, product.connections()); assertEquals(mode == PerformanceMatrix.Mode.ADAPTIVE, product.adaptive());
            assertEquals(HASH, product.expectedSha256());
            assertEquals(defaults.windowBytes(), product.windowBytes()); assertEquals(defaults.bufferBytes(), product.bufferBytes());
            assertEquals(defaults.minSegmentBytes(), product.minSegmentBytes()); assertEquals(defaults.checkpointInterval(), product.checkpointInterval());
        }
        assertThrows(IllegalArgumentException.class, () -> ExternalClientBenchmark.productOptions(HASH, PerformanceMatrix.Mode.SINGLE));
    }
    @Test void comparisonSourcesResolveByNameButStayOutOfTheMatrix() {
        for (var profile : NetworkProfile.comparisons()) {
            assertEquals(profile, NetworkProfile.named(profile.name()));
            assertTrue(NetworkProfile.catalog().stream().noneMatch(p -> p.name().equals(profile.name())));
        }
    }
}
