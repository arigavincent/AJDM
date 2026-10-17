package io.ajdm.store;

import static org.junit.jupiter.api.Assertions.*;

import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueStoreTest {
    @TempDir Path temp;
    private TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), URI.create("https://cdn.test/file.bin"), temp.resolve("file-" + UUID.randomUUID() + ".bin"), Map.of(), TransferOptions.defaults());
    }

    @Test void queueKeepsStartOrderAcrossReopenAndInterruptedWorkGoesFirst() throws Exception {
        Path database = temp.resolve("state/ajdm.db");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        try (var store = new SqliteDownloadStore(database)) {
            store.enqueue(new QueueEntry(a, QueueEntry.Kind.DOWNLOAD), false);
            store.enqueue(new QueueEntry(b, QueueEntry.Kind.MEDIA_JOB), false);
            store.enqueue(new QueueEntry(c, QueueEntry.Kind.DOWNLOAD), true);
        }
        try (var store = new SqliteDownloadStore(database)) {
            assertEquals(List.of(c, a, b), store.queue().stream().map(QueueEntry::id).toList());
            assertEquals(QueueEntry.Kind.MEDIA_JOB, store.queue().get(2).kind());
            store.enqueue(new QueueEntry(c, QueueEntry.Kind.DOWNLOAD), false);
            assertEquals(List.of(a, b, c), store.queue().stream().map(QueueEntry::id).toList(), "Re-queueing moves, never duplicates");
            assertTrue(store.dequeue(a)); assertFalse(store.dequeue(a));
            assertEquals(List.of(b, c), store.queue().stream().map(QueueEntry::id).toList());
        }
    }

    @Test void settingsAndDailyUsagePersistAndRemovingADownloadLeavesTheQueue() throws Exception {
        Path database = temp.resolve("state/ajdm.db"); var request = request();
        try (var store = new SqliteDownloadStore(database)) {
            store.saveCoreSettings(Map.of("budget.dailyMiB", "500", "schedule.start", "23:00"));
            store.saveCoreSettings(Map.of("budget.dailyMiB", "750"));
            store.addUsage("2026-10-17", 1000); store.addUsage("2026-10-17", 24); store.addUsage("2026-10-18", 5);
            assertThrows(IllegalArgumentException.class, () -> store.addUsage("2026-10-17", -1));
            store.create(request, temp.resolve("partial"));
            store.enqueue(new QueueEntry(request.id(), QueueEntry.Kind.DOWNLOAD), false);
        }
        try (var store = new SqliteDownloadStore(database)) {
            assertEquals(Map.of("budget.dailyMiB", "750", "schedule.start", "23:00"), store.coreSettings());
            assertEquals(1024, store.usage("2026-10-17")); assertEquals(5, store.usage("2026-10-18")); assertEquals(0, store.usage("2026-10-19"));
            store.removeStopped(request.id());
            assertTrue(store.queue().isEmpty());
        }
    }
}
