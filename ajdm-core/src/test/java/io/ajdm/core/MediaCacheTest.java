package io.ajdm.core;

import static org.junit.jupiter.api.Assertions.*;

import io.ajdm.media.MediaInfo;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MediaCacheTest {
    private static MediaInfo info(String title) { return new MediaInfo("id", title, "AJDM", 1.0, URI.create("https://site.example/v"), List.of(), 0); }

    @Test void analysesAreReusedUntilTheyExpireAndFailuresAreNotKept() throws Exception {
        long[] now = {0};
        var cache = new MediaCache(() -> now[0]);
        var calls = new AtomicInteger();
        assertNull(cache.peek("p"));
        assertEquals("one", cache.get("p", () -> { calls.incrementAndGet(); return info("one"); }).title());
        assertEquals("one", cache.get("p", () -> { calls.incrementAndGet(); return info("two"); }).title());
        assertEquals(1, calls.get());
        assertEquals("one", cache.peek("p").title());
        now[0] += 11L * 60 * 1_000_000_000L;
        assertNull(cache.peek("p"), "Ten minutes is the limit");
        assertEquals("three", cache.get("p", () -> info("three")).title());
        assertThrows(IOException.class, () -> cache.get("q", () -> { throw new IOException("offline"); }));
        assertEquals("ok", cache.get("q", () -> info("ok")).title(), "A failed analysis is retried, not remembered");
        cache.clear();
        assertNull(cache.peek("p"), "Signing in elsewhere clears old analyses");
    }
}
