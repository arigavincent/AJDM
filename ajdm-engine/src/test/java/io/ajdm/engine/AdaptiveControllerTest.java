package io.ajdm.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveControllerTest {
    private static final long SECOND = 1_000_000_000L;
    private static AdaptiveController.Decision sample(AdaptiveController c, int second, long bytes) {
        return c.observe(second * SECOND, bytes, Long.MAX_VALUE, false, 0, true);
    }
    @Test void startupBurstCannotDeclareSaturationBeforeTryingGrowth() {
        var c = new AdaptiveController(8, 0);
        assertEquals(4, c.target());
        c.lanes(new double[]{900000, 1000, 1000, 1000}, new boolean[]{true, true, true, true});
        sample(c, 1, 200000);
        assertEquals("trial-start", sample(c, 2, 400000).reason());
        assertEquals(8, c.target());
        assertNull(sample(c, 3, 800000)); // settle before measurement
        assertNull(sample(c, 4, 1200000));
        assertEquals("trial-accepted", sample(c, 5, 1600000).reason());
        assertEquals(8, c.target());
    }
    @Test void sharedCapacityRollsBackAndDoesNotOscillate() {
        var c = new AdaptiveController(8, 0);
        int trials = 0;
        for (int s = 1; s <= 60; s++) {
            var d = sample(c, s, s * 200000L);
            if (d != null && d.reason().equals("trial-start")) trials++;
            assertTrue(c.target() <= 8);
        }
        assertEquals(1, trials); assertEquals(4, c.target());
    }
    @Test void harmfulTrialReducesAdmissionsBelowPreviousBaseline() {
        var c = new AdaptiveController(8, 0);
        sample(c, 1, 200000); sample(c, 2, 400000); sample(c, 3, 500000);
        sample(c, 4, 600000);
        assertEquals("trial-rollback", sample(c, 5, 700000).reason());
        assertEquals(3, c.target());
    }
    @Test void serverPushbackHalvesAdmissionsToAtLeastOne() {
        var c = new AdaptiveController(8, 0);
        for (int s = 1; s <= 4; s++) {
            assertEquals("server-pushback", c.observe(s * SECOND, s * 100000L, Long.MAX_VALUE, false, 1, true).reason());
            assertEquals(Math.max(1, 4 >> s), c.target());
        }
    }
    @Test void sustainedWriterPressureRollsBackTrial() {
        var c = new AdaptiveController(8, 0);
        sample(c, 1, 200000); sample(c, 2, 400000);
        assertEquals(8, c.target());
        assertNull(c.observe(3 * SECOND, 600000, Long.MAX_VALUE, true, 0, true));
        assertEquals("writer-pressure-rollback", c.observe(4 * SECOND, 800000, Long.MAX_VALUE, true, 0, true).reason());
        assertEquals(4, c.target());
    }
    @Test void transientWriterPressureDoesNotAbandonBaseline() {
        var c = new AdaptiveController(8, 0);
        c.observe(SECOND, 200000, Long.MAX_VALUE, true, 0, true);
        assertEquals("trial-start", sample(c, 2, 400000).reason());
    }
    @Test void smallMaximumAndShortTailCannotOverAllocate() {
        var c = new AdaptiveController(3, 0);
        for (int s = 1; s <= 20; s++) sample(c, s, s * 200000L);
        assertEquals(3, c.target());
        c = new AdaptiveController(8, 0);
        for (int s = 1; s <= 20; s++) c.observe(s * SECOND, s * 200000L, 10000, false, 0, true);
        assertEquals(4, c.target());
    }
    @Test void sustainedNetworkChangeReopensMeasurementsAfterCooldown() {
        var c = new AdaptiveController(8, 0);
        int trials = 0; long bytes = 0;
        for (int s = 1; s <= 60; s++) {
            bytes += s < 25 ? 200000 : 400000;
            var d = sample(c, s, bytes);
            if (d != null && d.reason().equals("trial-start")) trials++;
        }
        assertEquals(2, trials);
    }
    @Test void staleHostThroughputRevalidatesFromConservativeStartup() {
        var c = new AdaptiveController(16, 8, 0, 2000000);
        sample(c, 1, 200000);
        assertEquals("host-hint-revalidate", sample(c, 2, 400000).reason());
        assertEquals(4, c.target());
        sample(c, 3, 600000);
        assertEquals("trial-start", sample(c, 4, 800000).reason());
    }
    @Test void legacyInitialCountRemainsExplicitlySelectable() {
        var c = new AdaptiveController(3, 2, 0, 0);
        assertEquals(2, c.target()); sample(c, 1, 100000);
        assertEquals("trial-start", sample(c, 2, 200000).reason());
        assertEquals(3, c.target());
    }
}
