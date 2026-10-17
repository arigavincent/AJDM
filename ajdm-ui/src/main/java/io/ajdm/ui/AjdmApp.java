package io.ajdm.ui;

import io.ajdm.ipc.IpcClient;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.nio.file.Path;

public final class AjdmApp extends Application {
    private DownloadsViewModel model;
    private DesktopNotifier notifier;
    @Override public void start(Stage stage) throws Exception {
        var options = getParameters().getRaw();
        Path socket = IpcClient.defaultSocket(), directory = Path.of(System.getProperty("user.home"), "Downloads");
        Path snapshot = null; int closeAfter = 0;
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (i + 1 >= options.size()) throw new IllegalArgumentException("Missing UI option value");
            String value = options.get(++i);
            switch (option) {
                case "--socket" -> socket = Path.of(value);
                case "--directory" -> directory = Path.of(value);
                case "--snapshot" -> snapshot = Path.of(value);
                case "--close-after" -> closeAfter = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("Unknown UI option");
            }
        }
        Path settingsFile = UiSettings.file(socket);
        var settings = UiSettings.load(settingsFile, directory);
        model = new DownloadsViewModel(socket);
        var window = new MainWindow(stage, model, socket, settings, settingsFile);
        var loader = new FXMLLoader(AjdmApp.class.getResource("main.fxml"));
        loader.setControllerFactory(type -> window);
        var scene = new Scene(loader.load(), 1240, 780);
        scene.getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
        stage.setTitle("AJDM · Ariga Justine Download Manager");
        stage.setMinWidth(1000); stage.setMinHeight(640); stage.setScene(scene); stage.show();
        stage.setMaximized(true);
        stage.setFullScreenExitHint("Press Esc to return to the desktop.");
        window.shortcuts(scene);
        window.theme(settings.dark());
        restoreMediaSession(settings);
        boolean interactive = snapshot == null && closeAfter == 0;
        if (interactive) desktop(stage, window);
        if (snapshot != null) {
            stage.setFullScreen(true);
            Path output = snapshot;
            var delay = new PauseTransition(Duration.seconds(3));
            delay.setOnFinished(event -> {
                try { Snapshot.write(scene, output); }
                catch (java.io.IOException failure) { System.err.println("Could not save UI snapshot."); }
            });
            delay.play();
        }
        if (closeAfter > 0) {
            var delay = new PauseTransition(Duration.seconds(closeAfter));
            delay.setOnFinished(event -> { stage.close(); Platform.exit(); }); delay.play();
        }
    }
    /** Tray, completion pop-ups and close-to-tray, according to the saved settings. */
    private void desktop(Stage stage, MainWindow window) {
        Runnable open = () -> { stage.show(); stage.setIconified(false); stage.toFront(); };
        notifier = new DesktopNotifier(open, window::pauseAll, window::resumeAll, Platform::exit);
        window.trayAvailable(notifier.trayAvailable());
        if (notifier.trayAvailable()) {
            Platform.setImplicitExit(false);
            stage.setOnCloseRequest(event -> {
                if (window.currentSettings().keepInTray()) { event.consume(); stage.hide(); }
                else Platform.exit();
            });
        }
        // Clipboard links: what is copied before AJDM starts is never offered.
        var clipboard = new ClipboardWatcher();
        clipboard.offer(javafx.scene.input.Clipboard.getSystemClipboard().getString());
        var clipboardCheck = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(1), tick -> {
            String text;
            try { text = javafx.scene.input.Clipboard.getSystemClipboard().getString(); } catch (RuntimeException unavailable) { return; }
            var offer = clipboard.offer(text);
            if (offer != null && window.currentSettings().watchClipboard()) window.offer(offer.url(), null, null, offer.kind() == ClipboardWatcher.Kind.MEDIA);
        }));
        clipboardCheck.setCycleCount(javafx.animation.Animation.INDEFINITE); clipboardCheck.play();
        var watcher = new CompletionWatcher();
        model.rows().addListener((javafx.collections.ListChangeListener<DownloadRow>) change -> {
            var rows = java.util.List.copyOf(model.rows());
            for (var event : watcher.update(rows)) {
                if (!window.currentSettings().notifications()) continue;
                if (event.completed()) notifier.notify("Download complete", event.name(), false);
                else notifier.notify("Download failed", event.name() + (event.error().isEmpty() ? "" : "\n" + event.error()), true);
            }
            long active = rows.stream().filter(DownloadRow::active).count();
            double speed = rows.stream().filter(DownloadRow::active).mapToDouble(row -> Math.max(0, row.reading().bytesPerSecond())).sum();
            notifier.tooltip(active == 0 ? "AJDM · idle" : "AJDM · " + active + " active · " + Display.speed(speed));
        });
    }
    /** The media browser or cookie file chosen earlier is applied again once the core answers. */
    private void restoreMediaSession(UiSettings.Values settings) {
        if (settings.mediaBrowser() == null && settings.cookieFile() == null) return;
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        model.connectedProperty().addListener((observable, was, now) -> {
            if (!now || !once.compareAndSet(false, true)) return;
            var request = new com.google.gson.JsonObject();
            if (settings.mediaBrowser() != null) request.addProperty("browser", settings.mediaBrowser());
            else request.addProperty("file", settings.cookieFile().toString());
            model.request("mediaCookies", request, result -> { }, error -> { });
        });
    }
    @Override public void stop() {
        if (notifier != null) notifier.close();
        if (model != null) model.close();
    }
}
