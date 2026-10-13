package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns client workers. All observable state is updated on the FX thread. */
final class DownloadsViewModel implements AutoCloseable {
    private final ObservableList<DownloadRow> rows = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper connection = new ReadOnlyStringWrapper("Connecting to core…");
    private final ReadOnlyBooleanWrapper connected = new ReadOnlyBooleanWrapper(false);
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("ajdm-ui-poll").factory());
    private final ThreadPoolExecutor actions = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon(true).name("ajdm-ui-action-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile DesktopGateway gateway;
    private volatile long generation;
    private volatile boolean closed;
    DownloadsViewModel(Path socket) {
        gateway = new DesktopGateway(socket);
        poller.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }
    ObservableList<DownloadRow> rows() { return rows; }
    ReadOnlyStringProperty connectionProperty() { return connection.getReadOnlyProperty(); }
    ReadOnlyBooleanProperty connectedProperty() { return connected.getReadOnlyProperty(); }
    void reconnect(Path socket) {
        generation++; gateway = new DesktopGateway(socket); rows.clear(); connected.set(false);
        connection.set("Connecting to core…"); refresh();
    }
    void refresh() {
        try { if (!closed) poller.execute(this::poll); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { /* Window closed while a command completed. */ }
    }
    private void poll() {
        if (closed || !polling.compareAndSet(false, true)) return;
        var current = gateway; long epoch = generation;
        try {
            var snapshot = current.downloads(System.nanoTime());
            Platform.runLater(() -> {
                if (closed || epoch != generation) return;
                rows.setAll(snapshot); connected.set(true); connection.set("Core connected · Live every second");
            });
        } catch (Exception failure) {
            current.resetRates();
            String message = DesktopGateway.failure(failure);
            Platform.runLater(() -> {
                if (closed || epoch != generation) return;
                connected.set(false); rows.clear(); connection.set(message);
            });
        } finally { polling.set(false); }
    }
    void request(String type, JsonObject payload, Consumer<JsonObject> success, Consumer<String> failure) {
        var current = gateway; long epoch = generation;
        try {
            actions.execute(() -> {
                try {
                    var result = current.call(type, payload);
                    Platform.runLater(() -> {
                        if (closed) return;
                        if (epoch == generation) success.accept(result);
                        else failure.accept("Core connection changed. Check the previous core before retrying this request.");
                    });
                    if (java.util.Set.of("add", "addMedia", "addPlaylist", "pause", "resume", "cancel", "restart", "remove").contains(type)) refresh();
                } catch (Exception exception) {
                    String message = DesktopGateway.failure(exception);
                    Platform.runLater(() -> { if (!closed) failure.accept(message); });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { failure.accept("AJDM is busy. Wait for the current request to finish."); }
    }
    void localAction(Runnable action, Consumer<String> failure) {
        try {
            actions.execute(() -> {
                try { action.run(); }
                catch (RuntimeException exception) { Platform.runLater(() -> { if (!closed) failure.accept("Could not open this path. Check that it exists and a file manager is available."); }); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { failure.accept("AJDM is busy. Try again shortly."); }
    }
    @Override public void close() { closed = true; generation++; poller.shutdownNow(); actions.shutdownNow(); }
}
