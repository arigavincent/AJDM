package io.ajdm.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test void bytesBeyondTheBurstWaitInProportionToTheLimit() {
        long[] now = {1_000_000_000L};
        var limiter = new RateLimiter(() -> now[0]);
        assertEquals(0, limiter.reserve(10 * 1024 * 1024), "Unlimited by default");
        limiter.limit(1024 * 1024); // 1 MiB/s
        assertEquals(0, limiter.reserve(256 * 1024), "A quarter second of data fits the burst");
        long wait = limiter.reserve(512 * 1024);
        assertEquals(500_000_000L, wait, 2_000_000L, "Half a MiB more must wait about half a second");
        now[0] += 10_000_000_000L; // Ten idle seconds do not bank more than the burst.
        assertEquals(0, limiter.reserve(256 * 1024));
        assertTrue(limiter.reserve(256 * 1024) > 200_000_000L);
        limiter.limit(0);
        assertEquals(0, limiter.reserve(100L * 1024 * 1024), "Lifting the limit applies at once");
        assertThrows(IllegalArgumentException.class, () -> limiter.limit(-1));
    }
}
