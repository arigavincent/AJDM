package io.ajdm.core;

import io.ajdm.media.MediaExtractor;
import io.ajdm.media.MediaInfo;
import io.ajdm.media.PlaylistInfo;
import io.ajdm.media.PlaylistSelection;
import io.ajdm.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class PlaylistDispatchTest {
    @TempDir Path temp;
    @Test void delayedExecutorDispatchCannotBunchActualMetadataInvocations() throws Exception {
        var times = new CopyOnWriteArrayList<Long>(); var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1); var actualCall = new CountDownLatch(1);
        var delegate = Executors.newFixedThreadPool(3);
        var submissions = new AtomicInteger();
        var executor = new AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                boolean delay = submissions.getAndIncrement() == 0;
                delegate.execute(() -> {
                    if (delay) {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                    command.run();
                });
            }
            @Override public void shutdown() { delegate.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
            @Override public boolean isShutdown() { return delegate.isShutdown(); }
            @Override public boolean isTerminated() { return delegate.isTerminated(); }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout, unit); }
        };
        var extractor = new MediaExtractor() {
            @Override public PlaylistInfo playlist(URI page, BooleanSupplier canceled) {
                return new PlaylistInfo("dispatch", "Dispatch test", page, List.of(
                        new PlaylistInfo.Entry(1, "first", "First", 1.0, URI.create("https://example.test/first"), null),
                        new PlaylistInfo.Entry(2, "second", "Second", 1.0, URI.create("https://example.test/second"), null)));
            }
            @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws java.io.IOException {
                times.add(System.nanoTime()); actualCall.countDown();
                throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Deliberate metadata fixture failure");
            }
        };
        try (var core = new CoreService(temp.resolve("state/db"), extractor,
                (v, ve, a, ae, out, container, canceled) -> { throw new AssertionError("No mux expected"); },
                MediaJobObserver.none(), PlaylistObserver.none(), executor)) {
            var id = core.addPlaylist("https://example.test/list", "all", new PlaylistSelection.Choice("P1080", null, null), Map.of(), temp.resolve("files"), ConflictPolicy.RENAME, 4, true);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            // The second task overtakes the held first one. Release it only after an actual call.
            assertTrue(actualCall.await(5, TimeUnit.SECONDS)); release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (times.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2, times.size());
            assertTrue(times.get(1) - times.get(0) >= TimeUnit.MILLISECONDS.toNanos(1950),
                    "Actual extraction starts bunched after delayed dispatch: " + TimeUnit.NANOSECONDS.toMillis(times.get(1) - times.get(0)) + " ms");
            assertTrue(core.playlistItems(id).stream().noneMatch(item -> item.childId() != null));
        } finally { release.countDown(); delegate.shutdownNow(); }
    }
}
