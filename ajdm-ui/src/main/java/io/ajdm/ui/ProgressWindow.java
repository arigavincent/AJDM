package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * IDM's download progress window: status, size, downloaded, transfer rate, time left and resume capability over a
 * progress bar, with Pause/Resume and Cancel; the details show which parts of the file are saved and each
 * connection's speed. Tabs hold the speed limiter and what to do when the download finishes. Cancel stops the
 * download, keeping its progress, and closes the window, as IDM's does.
 */
final class ProgressWindow {
    private final MainWindow main;
    private final UUID id;
    private final Stage stage = new Stage();
    private final Label name = new Label(), folder = new Label();
    private final Label status = value("status"), size = value("size"), downloaded = value("downloaded"), rate = value("rate"),
            left = value("left"), resumable = value("resumable");
    private final ProgressBar bar = new ProgressBar(0);
    private final Button pause = new Button("Pause"), cancel = new Button("Cancel"), details = new Button("<< Hide details");
    private final Canvas map = new Canvas(560, 18);
    private final ListView<String> connections = new ListView<>();
    private final VBox detailBox;
    private final CheckBox showComplete = new CheckBox("Show download complete dialog"),
            openFile = new CheckBox("Open the file when it finishes"), openFolder = new CheckBox("Open its folder when it finishes");
    private final CheckBox useLimiter = new CheckBox("Use speed limiter");
    private final Spinner<Integer> limit = new Spinner<>(8, 10_485_760, 512);
    private final javafx.animation.Timeline segments;
    private long[][] saved = new long[0][]; private long total = -1;
    private boolean requesting;
    private Categories shownCategory = Categories.OTHER;

    ProgressWindow(MainWindow main, UUID id) {
        this.main = main; this.id = id;
        name.getStyleClass().add("progress-name"); folder.getStyleClass().add("muted"); name.setId("progress-name");
        var grid = new GridPane(); grid.setHgap(18); grid.setVgap(6); grid.setId("progress-grid");
        String[] labels = {"Status", "File size", "Downloaded", "Transfer rate", "Time left", "Resume capability"};
        Label[] values = {status, size, downloaded, rate, left, resumable};
        for (int i = 0; i < labels.length; i++) { var key = new Label(labels[i]); key.getStyleClass().add("progress-key"); grid.addRow(i, key, values[i]); }
        bar.setMaxWidth(Double.MAX_VALUE); bar.getStyleClass().add("big-progress"); bar.setId("progress-bar");
        pause.setId("progress-pause"); cancel.setId("progress-cancel"); details.setId("progress-details");
        pause.setOnAction(event -> togglePause()); cancel.setOnAction(event -> cancelDownload());
        var buttons = new HBox(8, details, MainWindow.spacer(), pause, cancel); buttons.setAlignment(Pos.CENTER_LEFT);
        connections.setPrefHeight(118); connections.setId("progress-connections"); connections.setPlaceholder(new Label("Connections appear while the file downloads."));
        var mapLabel = new Label("Start positions and download progress by connections"); mapLabel.getStyleClass().add("muted");
        map.setId("segment-map");
        detailBox = new VBox(6, mapLabel, map, connections);
        details.setOnAction(event -> {
            boolean shown = !detailBox.isVisible(); detailBox.setVisible(shown); detailBox.setManaged(shown);
            details.setText(shown ? "<< Hide details" : "Show details >>"); stage.sizeToScene();
        });
        var header = new HBox(12, Icons.file(Categories.OTHER, 40), new VBox(2, name, folder)); header.setAlignment(Pos.CENTER_LEFT);
        var statusTab = new VBox(12, header, grid, bar, buttons, detailBox); statusTab.setPadding(new Insets(14));
        useLimiter.setId("progress-use-limiter"); limit.setEditable(true); limit.setPrefWidth(110); limit.disableProperty().bind(useLimiter.selectedProperty().not());
        var applyLimit = new Button("Apply"); applyLimit.setOnAction(event -> applyLimiter());
        var limiterTab = new VBox(12, useLimiter, new HBox(8, new Label("Maximum download speed"), limit, new Label("KB/sec"), applyLimit),
                new Label("The limit applies to all downloads together.")); limiterTab.setPadding(new Insets(14));
        showComplete.setSelected(main.currentSettings().showComplete()); showComplete.setId("progress-show-complete");
        showComplete.selectedProperty().addListener((o, a, on) -> main.update(main.currentSettings().withDialogs(main.currentSettings().showFileInfo(), main.currentSettings().showProgress(), on)));
        var completionTab = new VBox(10, showComplete, openFile, openFolder); completionTab.setPadding(new Insets(14));
        var tabs = new TabPane(new Tab("Download status", statusTab), new Tab("Speed Limiter", limiterTab), new Tab("Options on completion", completionTab));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var content = new StackPane(tabs); content.setPrefWidth(600); content.setId("progress-window");
        main.style(stage, content);
        stage.setScene(new Scene(content)); stage.setResizable(false);
        stage.setOnHidden(event -> { segments().stop(); main.progressClosed(id); });
        segments = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> requestSegments()));
        segments.setCycleCount(javafx.animation.Animation.INDEFINITE);
        var queue = main.queueStatus();
        if (queue != null && queue.has("limitKiBps") && queue.get("limitKiBps").getAsLong() > 0) {
            useLimiter.setSelected(true); limit.getValueFactory().setValue((int) Math.min(10_485_760, queue.get("limitKiBps").getAsLong()));
        }
        refresh();
    }
    private javafx.animation.Timeline segments() { return segments; }
    private static Label value(String id) { var label = new Label(); label.setId("progress-" + id); label.getStyleClass().add("progress-value"); return label; }
    void show() {
        stage.show(); stage.toFront(); segments.play(); requestSegments();
    }
    void close() { stage.close(); }
    void refresh() {
        var row = main.row(id);
        if (row == null) { if (stage.isShowing()) stage.close(); return; }
        name.setText(row.name());
        var target = java.nio.file.Path.of(row.target()); folder.setText(target.getParent() == null ? "" : target.getParent().toString());
        var kind = MainWindow.category(row);
        if (kind != shownCategory) { shownCategory = kind; ((HBox) name.getParent().getParent()).getChildren().set(0, Icons.file(kind, 40)); }
        double fraction = row.state().equals("COMPLETED") ? 1 : row.total() > 0 ? Math.min(1, (double) row.written() / row.total()) : -1;
        stage.setTitle((fraction >= 0 ? Math.round(fraction * 100) + "% " : "") + row.name());
        status.setText(statusText(row));
        size.setText(row.total() < 0 ? "Unknown" : Display.size(row.total()));
        downloaded.setText(Display.size(Math.max(0, row.written())) + (fraction >= 0 ? "  ( " + String.format(Locale.ROOT, "%.2f", fraction * 100) + " % )" : ""));
        rate.setText(row.active() && row.reading().bytesPerSecond() > 0 ? Display.rate(row.reading().bytesPerSecond()) : "");
        left.setText(Display.timeLeft(row.reading().etaSeconds()));
        resumable.setText(row.resumeSupported() ? "Yes" : row.state().equals("PROBING") ? "" : "No");
        bar.setProgress(fraction < 0 ? row.active() ? ProgressBar.INDETERMINATE_PROGRESS : 0 : fraction);
        pause.setText(row.resumable() ? "Resume" : "Pause");
        pause.setDisable(!(row.resumable() || row.active() && !row.state().equals("MERGING") && !row.state().equals("VERIFYING")));
        cancel.setDisable(!row.active());
        drawMap();
    }
    private static String statusText(DownloadRow row) {
        if (!row.error().isEmpty()) return row.error();
        return switch (row.state()) {
            case "PROBING" -> "Connecting…";
            case "DOWNLOADING" -> "Receiving data…";
            case "EXTRACTING" -> "Getting the video's address…";
            case "QUEUED" -> MainWindow.describe(row);
            case "MERGING" -> "Joining video and sound…";
            case "VERIFYING" -> "Checking the file…";
            case "PAUSED" -> "Stopped";
            case "COMPLETED" -> "Complete";
            default -> Display.state(row.state());
        };
    }
    private void togglePause() {
        var row = main.row(id); if (row == null) return;
        var payload = new JsonObject(); payload.addProperty("downloadId", id.toString());
        String command = row.resumable() ? "resume" : "pause"; pause.setDisable(true);
        main.model().request(command, payload, result -> refresh(), failure -> { refresh(); main.tell(failure); });
    }
    private void cancelDownload() {
        var row = main.row(id);
        if (row != null && row.active()) {
            var payload = new JsonObject(); payload.addProperty("downloadId", id.toString());
            main.model().request("pause", payload, result -> { }, failure -> main.tell(failure));
        }
        stage.close();
    }
    private void applyLimiter() {
        var queue = main.queueStatus(); if (queue == null) return;
        main.configureQueue(queue, useLimiter.isSelected() ? limit.getValue() : 0,
                () -> main.tell(useLimiter.isSelected() ? "Speed limiter on: " + limit.getValue() + " KB/s." : "Speed limiter off."));
    }
    /** Finished: the window closes; IDM's complete box and the chosen completion actions follow. */
    void finished(CompletionWatcher.Event event) {
        var row = main.row(id);
        stage.close();
        if (row == null) return;
        if (event.completed()) {
            if (openFile.isSelected()) main.openFile(row, false);
            if (openFolder.isSelected()) main.openFile(row, true);
            if (main.currentSettings().showComplete()) new CompleteDialog(main, row).show();
        } else {
            main.showProgress(id);
        }
    }
    private void requestSegments() {
        var row = main.row(id);
        if (requesting || row == null || row.kind().equals("PLAYLIST") || !stage.isShowing()) return;
        requesting = true;
        var payload = new JsonObject(); payload.addProperty("downloadId", id.toString());
        main.model().request("segments", payload, result -> {
            requesting = false; total = result.get("total").getAsLong();
            var list = new ArrayList<long[]>();
            for (var pair : result.getAsJsonArray("saved")) list.add(new long[]{pair.getAsJsonArray().get(0).getAsLong(), pair.getAsJsonArray().get(1).getAsLong()});
            saved = list.toArray(long[][]::new);
            var lines = new ArrayList<String>(); int n = 1;
            if (result.has("connections")) for (var speed : result.getAsJsonArray("connections")) {
                double value = speed.getAsDouble();
                lines.add(String.format(Locale.ROOT, "%2d.   %s", n++, value > 0 ? Display.rate(value) : "Waiting for data"));
            }
            connections.getItems().setAll(lines);
            drawMap();
        }, failure -> requesting = false);
    }
    private void drawMap() {
        var g = map.getGraphicsContext2D(); double width = map.getWidth(), height = map.getHeight();
        boolean dark = main.currentSettings().dark();
        g.setFill(Color.web(dark ? "#22344D" : "#E3E9F1")); g.fillRect(0, 0, width, height);
        var row = main.row(id);
        if (row != null && row.state().equals("COMPLETED")) { g.setFill(Color.web("#2F7DE1")); g.fillRect(0, 0, width, height); }
        else if (total > 0) {
            g.setFill(Color.web("#2F7DE1"));
            for (long[] range : saved) {
                double x = range[0] * width / total, w = Math.max(1, (range[1] - range[0]) * width / total);
                g.fillRect(x, 0, Math.min(w, width - x), height);
            }
        }
        g.setStroke(Color.web(dark ? "#36516E" : "#B9C4D3")); g.strokeRect(0.5, 0.5, width - 1, height - 1);
    }
    List<String> connectionLines() { return List.copyOf(connections.getItems()); }
}
