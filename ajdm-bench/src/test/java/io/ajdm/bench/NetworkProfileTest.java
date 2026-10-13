package io.ajdm.bench;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkProfileTest {
    @Test void changingRateUsesElapsedPayloadTimeAndExactStepBoundaries() {
        var profile = NetworkProfile.named("changing-shared-rate");
        assertEquals(2 * 1024 * 1024, profile.sharedRateAt(0));
        assertEquals(2 * 1024 * 1024, profile.sharedRateAt(Duration.ofSeconds(2).toNanos() - 1));
        assertEquals(256 * 1024, profile.sharedRateAt(Duration.ofSeconds(2).toNanos()));
        assertEquals(4 * 1024 * 1024, profile.sharedRateAt(Duration.ofSeconds(5).toNanos()));
        assertEquals(0, NetworkProfile.named("response-delay").sharedRateAt(100));
    }
    @Test void invalidSchedulesCannotSilentlyChangeBenchmarkConditions() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkProfile("valid", 10, 0,
                List.of(new NetworkProfile.RateStep(Duration.ofSeconds(1), 100)), Duration.ZERO, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new NetworkProfile("valid", 10, 0,
                List.of(new NetworkProfile.RateStep(Duration.ZERO, 100), new NetworkProfile.RateStep(Duration.ZERO, 200)), Duration.ZERO, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> NetworkProfile.named("unknown"));
    }
}
