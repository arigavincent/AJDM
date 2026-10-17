package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

public final class MainWindow {
    private final Stage stage;
    private final DownloadsViewModel model;
    private Path socket;
    private Path directory;
    // Direct files gain most from many connections on lossy or per-connection-throttled links (docs/NETLAB.md);
    // media CDNs get IDM's long-standing default until media-specific measurements exist.
    private int defaultConnections = 16, defaultMediaConnections = 8;
    private UiSettings.Values settings;
    private final Path settingsFile;
    private boolean trayAvailable;
    @FXML private BorderPane root;
    @FXML private VBox workspace;
    @FXML private Label connection;
    private final TableView<DownloadRow> table = new TableView<>();
    private final Label summary = new Label();
    private final Label detailTitle = new Label("Select a download");
    private final Label details = new Label("Inspect progress and adjust connections after pausing.");
    private final Label streamState = new Label("Select a download to see its controls.");
    private final Label notice = new Label();
    private final Label queueLine = new Label();
    private javafx.animation.Timeline queueRefresh;
    private final Canvas timeline = new Canvas(430, 76);
    /** IDM-style map of the file: saved parts in teal, missing parts in grey. */
    private final Canvas segmentMap = new Canvas(430, 14);
    private UUID mappedId;
    private long mapRequestedAt;
    private final Button pause = new Button("Pause"), resume = new Button("Resume"), cancel = new Button("Cancel");
    private final Button delete = new Button("Delete"), restart = new Button("Restart");
    private final Button inspect = new Button("Details");
    private final Spinner<Integer> connections = new Spinner<>(1, 32, 8);
    private final CheckBox changeConnections = new CheckBox("Set connections on resume");
    private boolean busy;
    private UUID selected;

    MainWindow(Stage stage, DownloadsViewModel model, Path socket, Path directory) {
        this(stage, model, socket, UiSettings.defaults(directory), null);
    }
    MainWindow(Stage stage, DownloadsViewModel model, Path socket, UiSettings.Values settings, Path settingsFile) {
        this.stage = stage; this.model = model; this.socket = socket; this.settings = settings; this.settingsFile = settingsFile;
        directory = settings.directory(); defaultConnections = settings.connections(); defaultMediaConnections = settings.mediaConnections();
    }
    UiSettings.Values currentSettings() { return settings; }
    void trayAvailable(boolean available) { trayAvailable = available; }
    /** Pause every running download, or resume every paused one (tray and toolbar). */
    void pauseAll() { bulk("pause", DownloadRow::active, "Paused"); }
    void resumeAll() { bulk("resume", row -> row.state().equals("PAUSED"), "Resumed"); }
    private void bulk(String command, java.util.function.Predicate<DownloadRow> which, String verb) {
        var ids = model.rows().stream().filter(which).filter(row -> !row.state().equals("MERGING") && !row.state().equals("VERIFYING"))
                .map(DownloadRow::id).toList();
        if (ids.isEmpty()) { notice.setText(command.equals("pause") ? "Nothing is downloading." : "Nothing is paused."); return; }
        notice.setText(verb + " " + ids.size() + " download" + (ids.size() == 1 ? "" : "s") + "…");
        model.requestEach(command, ids, count -> notice.setText(verb + " " + count + " of " + ids.size() + "."));
    }
    @FXML private void initialize() {
        connection.textProperty().bind(model.connectionProperty());
        configureTable();
        model.rows().addListener((javafx.collections.ListChangeListener<DownloadRow>) change -> {
            // Transformed/sorted lists and TableView settle after the source-list notification.
            javafx.application.Platform.runLater(() -> {
                if (selected != null) table.getItems().stream().filter(row -> row.id().equals(selected)).findFirst()
                        .ifPresent(row -> table.getSelectionModel().select(row));
                updateDetails(); updateSummary();
            });
        });
        model.connectedProperty().addListener((observable, old, value) -> updateDetails());
        pause.setOnAction(event -> control("pause")); resume.setOnAction(event -> control("resume"));
        pause.setId("pause-download"); resume.setId("resume-download"); cancel.setId("cancel-download");
        delete.setId("delete-download"); restart.setId("restart-download"); streamState.setId("selected-stream-state");
        inspect.setId("details-download"); inspect.setOnAction(event -> downloadDetails());
        connections.setId("resume-connections"); changeConnections.setId("change-connections");
        cancel.setOnAction(event -> confirmControl("cancel")); delete.setOnAction(event -> confirmControl("remove")); restart.setOnAction(event -> confirmControl("restart"));
        delete.setTooltip(new Tooltip("Pause an active download before deleting it. You can choose whether to delete its files."));
        restart.setTooltip(new Tooltip("Start a new download after the source changed; previous files are kept."));
        connections.setEditable(false); connections.setPrefWidth(84);
        connections.setAccessibleText("Number of connections for resumed download");
        changeConnections.selectedProperty().addListener((o, a, b) -> updateDetails());
        downloads();
    }
    private void configureTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setId("downloads-table");
        var name = column("File", DownloadRow::name, 330);
        var progress = new TableColumn<DownloadRow, DownloadRow>("Progress"); progress.setPrefWidth(170);
        progress.setCellValueFactory(value -> new javafx.beans.property.ReadOnlyObjectWrapper<>(value.getValue()));
        progress.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(DownloadRow row, boolean empty) {
                super.updateItem(row, empty); setText(null); setGraphic(null);
                if (!empty && row != null) {
                    var bar = new ProgressBar(row.reading().fraction()); bar.setMaxWidth(Double.MAX_VALUE);
                    var percent = new Label(row.reading().fraction() < 0 ? "Unknown size" : String.format(Locale.ROOT, "%.1f%%", row.reading().fraction() * 100));
                    percent.getStyleClass().add("progress-percent");
                    var box = new VBox(5, percent, bar); setGraphic(box);
                }
            }
        });
        var size = column("Size", row -> Display.mib(row.total()), 110);
        var speed = column("Speed", row -> Display.speed(row.reading().bytesPerSecond()), 110);
        var eta = column("Remaining", row -> Display.eta(row.reading().etaSeconds()), 94);
        var state = column("State", DownloadRow::stateLabel, 140);
        table.getColumns().setAll(List.of(name, progress, size, speed, eta, state));
        table.setFixedCellSize(64); table.setAccessibleText("Live downloads with progress, size, speed and remaining time");
        table.setPlaceholder(new Label("No downloads here. Add a URL, or check the core connection below."));
        var menu = new ContextMenu();
        for (var button : List.of(pause, resume, cancel, restart, delete, inspect)) {
            var action = new MenuItem(); action.textProperty().bind(button.textProperty()); action.disableProperty().bind(button.disableProperty());
            action.setOnAction(event -> button.fire()); menu.getItems().add(action);
        }
        table.setContextMenu(menu);
        table.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.DELETE && !delete.isDisabled()) { confirmControl("remove"); event.consume(); } });
        table.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> {
            if (row != null) selected = row.id(); updateDetails();
        });
    }
    private static TableColumn<DownloadRow, String> column(String title, Function<DownloadRow, String> text, double width) {
        var column = new TableColumn<DownloadRow, String>(title); column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue()))); return column;
    }
    @FXML void downloads() {
        var title = new Label("Downloads"); title.getStyleClass().add("page-title");
        summary.getStyleClass().setAll("muted");
        var refresh = new Button("Refresh"); refresh.setOnAction(event -> model.refresh());
        var expand = new Button("Expand window"); expand.setOnAction(event -> stage.setFullScreen(!stage.isFullScreen()));
        var pauseAll = new Button("Pause all"); pauseAll.setId("pause-all"); pauseAll.setOnAction(event -> pauseAll());
        var resumeAll = new Button("Resume all"); resumeAll.setId("resume-all"); resumeAll.setOnAction(event -> resumeAll());
        queueLine.getStyleClass().setAll("muted"); queueLine.setId("queue-status");
        var heading = new HBox(14, new VBox(5, title, summary, queueLine), spacer(), pauseAll, resumeAll, expand, refresh);
        refreshQueueLine();
        var search = new TextField(); search.setPromptText("Search files"); search.setAccessibleText("Search downloads");
        var filter = new ComboBox<String>(); filter.getItems().setAll("All", "Active", "Paused", "Completed", "Failed"); filter.setValue("All");
        filter.setAccessibleText("Filter downloads by state");
        var type = new ComboBox<String>(); type.setId("filter-type");
        type.getItems().add("All types"); for (var category : Categories.values()) type.getItems().add(category.label);
        type.setValue("All types"); type.setAccessibleText("Filter downloads by file type");
        var filtered = new FilteredList<>(model.rows()); var sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty()); table.setItems(sorted);
        Runnable apply = () -> filtered.setPredicate(row -> row.name().toLowerCase(Locale.ROOT).contains(search.getText().toLowerCase(Locale.ROOT))
                && (type.getValue().equals("All types") || Categories.of(row.name()).label.equals(type.getValue())) && switch (filter.getValue()) {
            case "Active" -> row.active(); case "Paused" -> row.state().equals("PAUSED");
            case "Completed" -> row.state().equals("COMPLETED"); case "Failed" -> row.state().equals("FAILED"); default -> true;
        });
        search.textProperty().addListener((o, a, b) -> apply.run()); filter.valueProperty().addListener((o, a, b) -> apply.run());
        type.valueProperty().addListener((o, a, b) -> apply.run());
        HBox.setHgrow(search, Priority.ALWAYS); VBox.setVgrow(table, Priority.ALWAYS);
        detailTitle.getStyleClass().setAll("detail-title"); details.setWrapText(true); details.getStyleClass().setAll("muted");
        notice.setWrapText(true); notice.getStyleClass().setAll("notice");
        segmentMap.setId("segment-map");
        var chart = new VBox(6, new Label("Recent download speed"), timeline, new Label("Saved parts of the file"), segmentMap); chart.getStyleClass().add("speed-chart");
        var controls = new HBox(9, pause, resume, cancel, restart, delete, inspect);
        var tuning = new HBox(9, changeConnections, connections);
        var bottom = new VBox(10, detailTitle, streamState, details, chart, controls, tuning, notice); bottom.getStyleClass().add("details");
        workspace.getChildren().setAll(heading, new HBox(10, search, filter, type), table, bottom);
        updateDetails(); updateSummary();
    }
    private void updateSummary() {
        long active = model.rows().stream().filter(DownloadRow::active).count();
        summary.setText(model.rows().size() + " downloads · " + active + " active · " + Display.speed(model.rows().stream().mapToDouble(row -> row.reading().bytesPerSecond()).sum()));
    }
    private void updateDetails() {
        var row = table.getSelectionModel().getSelectedItem();
        boolean available = row != null && !busy && model.connectedProperty().get();
        pause.setDisable(!available || !row.active()); resume.setDisable(!available || !row.resumable());
        cancel.setDisable(!available || row.terminal());
        delete.setDisable(!available || !row.removable()); restart.setDisable(!available || !row.restartable());
        inspect.setDisable(!available);
        resume.setText(row != null && row.state().equals("FAILED") ? "Retry" : "Resume");
        changeConnections.setDisable(!available || !row.resumable() || row.kind().equals("PLAYLIST")); connections.setDisable(changeConnections.isDisabled() || !changeConnections.isSelected());
        if (row == null) {
            detailTitle.setText("Select a download"); streamState.setText("Select a download to see its controls."); details.setText("Inspect progress and adjust connections after pausing."); drawHistory(List.of());
            drawSegments(-1, List.of()); mappedId = null; return;
        }
        refreshSegments(row);
        detailTitle.setText(row.name());
        streamState.setText("State: " + Display.state(row.state()) + " · " + (row.active() ? "Pause to save progress, or cancel to stop."
                : row.restartable() ? "Source changed. Restart or delete this entry."
                : row.resumable() ? "Resume saved progress, cancel, or delete."
                : "You can delete this entry and optionally its files."));
        details.setText(Display.mib(row.written()) + " written / " + Display.mib(row.total()) + " · " + Display.mib(row.durable()) + " saved for recovery\n" + row.target() + "\n" + row.id());
        if (!busy) notice.setText(row.error());
        drawHistory(row.reading().history());
    }
    private void refreshSegments(DownloadRow row) {
        boolean changed = !row.id().equals(mappedId);
        if (changed) { mappedId = row.id(); drawSegments(-1, List.of()); }
        if (row.kind().equals("PLAYLIST") || !model.connectedProperty().get()) return;
        long now = System.nanoTime();
        if (!changed && now - mapRequestedAt < 900_000_000L) return;
        mapRequestedAt = now;
        var request = new JsonObject(); request.addProperty("downloadId", row.id().toString()); UUID id = row.id();
        model.request("segments", request, result -> {
            if (!id.equals(mappedId)) return;
            var saved = new java.util.ArrayList<long[]>();
            for (var pair : result.getAsJsonArray("saved")) saved.add(new long[]{pair.getAsJsonArray().get(0).getAsLong(), pair.getAsJsonArray().get(1).getAsLong()});
            drawSegments(result.get("total").getAsLong(), saved);
        }, error -> { });
    }
    private void drawSegments(long total, List<long[]> saved) {
        var g = segmentMap.getGraphicsContext2D(); double width = segmentMap.getWidth(), height = segmentMap.getHeight();
        g.clearRect(0, 0, width, height);
        g.setFill(Color.web("#D4DEE9")); g.fillRoundRect(0, 0, width, height, 6, 6);
        if (total <= 0) return;
        g.setFill(Color.web("#1FB5A8"));
        for (long[] range : saved) {
            double x = range[0] * width / total, w = Math.max(1, (range[1] - range[0]) * width / total);
            g.fillRect(x, 0, Math.min(w, width - x), height);
        }
    }
    private void drawHistory(List<Double> history) {
        var g = timeline.getGraphicsContext2D(); g.clearRect(0, 0, timeline.getWidth(), timeline.getHeight());
        g.setStroke(Color.web("#D4DEE9")); g.strokeLine(0, 64, 430, 64);
        if (history.size() < 2) return;
        double max = Math.max(1, history.stream().mapToDouble(Double::doubleValue).max().orElse(1));
        g.setStroke(Color.web("#1FB5A8")); g.setLineWidth(2); g.beginPath();
        for (int i = 0; i < history.size(); i++) {
            double x = i * 430.0 / (history.size() - 1), y = 64 - history.get(i) / max * 55;
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
    }
    private void control(String command) {
        var row = table.getSelectionModel().getSelectedItem(); if (row == null || busy) return;
        control(command, row, false);
    }
    private void confirmControl(String command) {
        var row = table.getSelectionModel().getSelectedItem(); if (row == null || busy || !model.connectedProperty().get()) return;
        if (command.equals("remove") && !row.removable() || command.equals("restart") && !row.restartable() || command.equals("cancel") && row.terminal()) return;
        String action = command.equals("remove") ? "Delete" : command.equals("restart") ? "Restart" : "Cancel";
        var confirmation = new Alert(Alert.AlertType.CONFIRMATION); confirmation.initOwner(stage); confirmation.setHeaderText(action + " " + row.name() + "?");
        var explanation = new Label(command.equals("remove") ? "Remove this entry from AJDM? Playlist removal also removes its child entries. Files are kept unless you select the option below."
                : command.equals("restart") ? "Start a new download of the changed source? Previous downloaded files are kept."
                : "Stop this download permanently? Saved partial files are kept; this job cannot be resumed.");
        explanation.setWrapText(true); explanation.setMaxWidth(520);
        var deleteFiles = new CheckBox("Also delete downloaded and partial files from disk"); deleteFiles.setId("delete-files");
        var location = new Label(row.target()); location.setWrapText(true); location.setMaxWidth(520);
        var content = new VBox(10, explanation, location); if (command.equals("remove")) content.getChildren().add(deleteFiles);
        confirmation.getDialogPane().setContent(content); confirmation.getDialogPane().setId("control-confirmation");
        confirmation.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        ((Button) confirmation.getDialogPane().lookupButton(ButtonType.OK)).setText(action);
        style(confirmation);
        confirmation.setOnHidden(event -> { if (confirmation.getResult() == ButtonType.OK) control(command, row, deleteFiles.isSelected()); });
        confirmation.show();
    }
    private void control(String command, DownloadRow row, boolean deleteFiles) {
        if (busy) return;
        var payload = new JsonObject(); payload.addProperty("downloadId", row.id().toString());
        if (command.equals("remove")) payload.addProperty("deleteFiles", deleteFiles);
        if (command.equals("resume") && !changeConnections.isDisabled() && changeConnections.isSelected()) payload.addProperty("connections", connections.getValue());
        busy = true; notice.setText(Display.state(command.toUpperCase(Locale.ROOT)) + " requested…"); updateDetails();
        model.request(command, payload, result -> {
            busy = false;
            if (command.equals("remove")) { if (row.id().equals(selected)) selected = null; table.getSelectionModel().clearSelection(); }
            else if (command.equals("restart")) selected = UUID.fromString(result.get("downloadId").getAsString());
            updateDetails(); notice.setText(command.equals("remove") ? deleteFiles ? "Entry and its files deleted." : "Entry deleted. Files kept." : "Request accepted.");
        },
                failure -> {
                    busy = false; updateDetails();
                    notice.setText(command.equals("remove") && failure.startsWith(io.ajdm.model.ErrorCode.INVALID_STATE.publicCode() + ":")
                            ? "Pause the download or its playlist before deleting. If another job uses the same file, delete the entry while keeping files." : failure);
                });
    }
    @FXML void addDownload() { new AddDownloadDialog(stage, model, directory, defaultConnections, false).show(); }
    @FXML void media() { new AddDownloadDialog(stage, model, mediaFolder(), defaultMediaConnections, true).show(); }
    private Path mediaFolder() { return settings.sortByCategory() ? directory.resolve(Categories.VIDEO.label) : directory; }
    /**
     * A link from the clipboard or the browser: a file opens the Add download dialog filled in (saved in its category
     * folder when sorting is on); a video page opens the quality picker and starts analysing. The window comes forward.
     */
    void offer(String link, String referrer, String name, boolean mediaPage) {
        stage.show(); stage.setIconified(false); stage.toFront();
        if (mediaPage) {
            var picker = new AddDownloadDialog(stage, model, mediaFolder(), defaultMediaConnections, true);
            picker.prefill(link, null, null); picker.show(); picker.analyse();
            return;
        }
        String guess = name != null ? name : guessName(link);
        var dialog = new AddDownloadDialog(stage, model, Categories.folder(directory, guess, settings.sortByCategory()), defaultConnections, false);
        dialog.prefill(link, name, referrer); dialog.show();
    }
    static String guessName(String link) {
        try {
            String path = java.net.URI.create(link).getPath();
            if (path == null) return "";
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            return java.net.URLDecoder.decode(leaf.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    @FXML void playlist() { new PlaylistDialog(stage, model, directory, defaultMediaConnections).show(); }
    private void downloadDetails() {
        var row = table.getSelectionModel().getSelectedItem(); if (row == null) return;
        var content = new VBox(10); var state = new Label(row.kind().equals("PLAYLIST") ? "Loading playlist status…" : describe(row));
        state.setId("download-details-state"); state.setWrapText(true); state.setMaxWidth(650);
        var rows = new ListView<String>(); rows.setPrefSize(650, 360);
        var previous = new Button("Previous 100"); var next = new Button("Next 100");
        var dialog = new Dialog<Void>(); dialog.initOwner(stage); dialog.setTitle(row.kind().equals("PLAYLIST") ? "Playlist details" : "Download details");
        var openFolder = new Button("Open folder"); openFolder.setId("open-download-folder");
        var openFile = new Button("Open file"); openFile.setId("open-download-file");
        openFile.setDisable(!row.state().equals("COMPLETED") || row.kind().equals("PLAYLIST"));
        var fileStatus = new Label(); fileStatus.setWrapText(true);
        openFolder.setOnAction(event -> model.localAction(() -> DesktopGateway.openLocal(row, true), fileStatus::setText));
        openFile.setOnAction(event -> model.localAction(() -> DesktopGateway.openLocal(row, false), fileStatus::setText));
        if (!row.kind().equals("PLAYLIST")) {
            content.getChildren().addAll(state, new HBox(8, openFolder, openFile), fileStatus);
            javafx.collections.ListChangeListener<DownloadRow> observer = change -> {
                var current = model.rows().stream().filter(r -> r.id().equals(row.id())).findFirst().orElse(null);
                if (current == null) { state.setText("This entry is no longer available."); openFile.setDisable(true); openFolder.setDisable(true); return; }
                state.setText(describe(current)); openFile.setDisable(!current.state().equals("COMPLETED"));
                openFolder.setOnAction(event -> model.localAction(() -> DesktopGateway.openLocal(current, true), fileStatus::setText));
                openFile.setOnAction(event -> model.localAction(() -> DesktopGateway.openLocal(current, false), fileStatus::setText));
            };
            model.rows().addListener(observer); dialog.setOnHidden(event -> model.rows().removeListener(observer));
            if (row.kind().equals("FILE") && row.resumable()) {
                var referer = new TextField(); var agent = new TextField();
                var cookies = new PasswordField(); var auth = new PasswordField();
                var retry = new Button("Resume with request details");
                var requestStatus = new Label(); requestStatus.setWrapText(true);
                var fields = new TitledPane("Update browser request details", new VBox(7, new Label("Referrer"), referer,
                        new Label("User-Agent"), agent, new Label("Cookie"), cookies, new Label("Authorization"), auth, retry, requestStatus));
                fields.setExpanded(false); content.getChildren().add(fields);
                retry.setOnAction(event -> {
                    try {
                        var request = new JsonObject(); request.addProperty("downloadId", row.id().toString());
                        FormRequests.requestDetails(request, referer.getText(), agent.getText(), cookies.getText(), auth.getText());
                        retry.setDisable(true);
                        model.request("resume", request, result -> dialog.close(), failure -> { retry.setDisable(false); requestStatus.setText(failure); });
                    } catch (IllegalArgumentException invalid) { requestStatus.setText("Check the referrer and request headers."); }
                });
            }
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); style(dialog); dialog.show(); return;
        }
        content.getChildren().addAll(state, new HBox(8, openFolder), fileStatus, rows, new HBox(8, previous, next));
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); style(dialog);
        int[] offset = {0}; boolean[] loading = {false};
        Runnable load = () -> {
            if (loading[0] || !dialog.isShowing()) return; loading[0] = true; previous.setDisable(true); next.setDisable(true);
            var request = new JsonObject(); request.addProperty("downloadId", row.id().toString()); request.addProperty("offset", offset[0]); request.addProperty("limit", 100);
            model.request("playlistStatus", request, result -> {
                rows.getItems().clear();
                for (var value : result.getAsJsonArray("entries")) {
                    var entry = value.getAsJsonObject(); rows.getItems().add(entry.get("index").getAsInt() + "  " + AddDownloadDialog.string(entry, "state") + "  " + AddDownloadDialog.string(entry, "title"));
                }
                state.setText(AddDownloadDialog.string(result, "title") + "\n" + result.get("completed").getAsInt() + " completed · " + result.get("failed").getAsInt() + " failed · " + result.get("skipped").getAsInt() + " skipped");
                loading[0] = false; previous.setDisable(offset[0] == 0); next.setDisable(offset[0] + 100 >= result.get("selected").getAsInt());
            }, error -> { loading[0] = false; state.setText(error); });
        };
        previous.setOnAction(event -> { offset[0] = Math.max(0, offset[0] - 100); load.run(); });
        next.setOnAction(event -> { offset[0] += 100; load.run(); });
        var refresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> load.run()));
        refresh.setCycleCount(javafx.animation.Animation.INDEFINITE); dialog.setOnHidden(event -> refresh.stop());
        refresh.play();
        dialog.show(); load.run();
    }
    private static String describe(DownloadRow row) {
        return row.name() + "\n" + Display.state(row.state()) + " · " + Display.mib(row.written()) + " / " + Display.mib(row.total())
                + "\nSpeed: " + Display.speed(row.reading().bytesPerSecond()) + " · Remaining: " + Display.eta(row.reading().etaSeconds())
                + "\nSaved for recovery: " + Display.mib(row.durable()) + "\n" + (row.resumeSupported() ? "Resume supported" : "Resume is not supported by this source")
                + "\n" + row.target() + "\n" + row.id() + (row.error().isEmpty() ? "" : "\n" + row.error());
    }
    @FXML void settings() {
        var socketField = new TextField(socket.toString()); var directoryField = new TextField(directory.toString());
        var workers = new Spinner<Integer>(1, 32, defaultConnections);
        var mediaWorkers = new Spinner<Integer>(1, 32, defaultMediaConnections); mediaWorkers.setId("media-connections");
        var dark = new CheckBox("Dark theme"); dark.setSelected(root.getStyleClass().contains("dark"));
        var notify = new CheckBox("Notify when downloads finish or fail"); notify.setId("notifications"); notify.setSelected(settings.notifications());
        var watchClipboard = new CheckBox("Offer to download links I copy (files and video pages)"); watchClipboard.setId("watch-clipboard");
        watchClipboard.setSelected(settings.watchClipboard());
        var sortByCategory = new CheckBox("Save into category folders (Video, Music, Documents, Compressed, Programs)"); sortByCategory.setId("sort-by-category");
        sortByCategory.setSelected(settings.sortByCategory());
        var keepInTray = new CheckBox("Keep AJDM running in the tray when its window closes"); keepInTray.setId("keep-in-tray");
        keepInTray.setSelected(settings.keepInTray() && trayAvailable); keepInTray.setDisable(!trayAvailable);
        if (!trayAvailable) keepInTray.setTooltip(new Tooltip("This desktop has no system tray."));
        var cookies = new TextField(); cookies.setPromptText("Optional Netscape cookies.txt file"); cookies.setId("media-cookie-file");
        if (settings.cookieFile() != null) cookies.setText(settings.cookieFile().toString());
        var chooseCookies = new Button("Choose file"); chooseCookies.setOnAction(event -> {
            var chooser = new javafx.stage.FileChooser(); chooser.setTitle("Choose exported media cookies");
            var file = chooser.showOpenDialog(stage); if (file != null) cookies.setText(file.toPath().toString());
        });
        var useCookies = new Button("Use for media"); useCookies.setId("configure-media-cookies");
        var browser = new ComboBox<String>(); browser.setId("media-browser"); browser.setPromptText("Choose your signed-in browser");
        browser.getItems().setAll("firefox", "chrome", "chromium", "brave", "edge", "opera", "vivaldi", "whale", "safari");
        var useBrowser = new Button("Use browser session"); useBrowser.setId("configure-media-browser");
        var configuringSession = new javafx.beans.property.SimpleBooleanProperty(false);
        useBrowser.disableProperty().bind(browser.valueProperty().isNull().or(configuringSession));
        useCookies.disableProperty().bind(configuringSession);
        if (settings.mediaBrowser() != null) browser.setValue(settings.mediaBrowser());
        var cookieStatus = new Label("Remembered and applied again when AJDM starts. Only the browser name or file path is saved, never cookies.");
        cookieStatus.setId("media-session-status");
        cookieStatus.setWrapText(true); cookieStatus.setMaxWidth(520);
        var clearCookies = new Button("Clear media cookies");
        clearCookies.disableProperty().bind(configuringSession);
        useCookies.setOnAction(event -> {
            if (cookies.getText().isBlank()) { cookieStatus.setText("Choose a Netscape cookies.txt file first."); return; }
            var request = new JsonObject(); request.addProperty("file", cookies.getText().strip());
            configuringSession.set(true);
            model.request("mediaCookies", request, result -> { configuringSession.set(false); browser.setValue(null); remember(null, Path.of(cookies.getText().strip())); cookieStatus.setText("Cookie file selected for future media extraction and link renewal."); }, error -> { configuringSession.set(false); cookieStatus.setText(error); });
        });
        useBrowser.setOnAction(event -> {
            var request = new JsonObject(); request.addProperty("browser", browser.getValue());
            configuringSession.set(true);
            String chosen = browser.getValue();
            model.request("mediaCookies", request, result -> { configuringSession.set(false); cookies.clear(); remember(chosen, null); cookieStatus.setText("Browser session selected. AJDM reads its cookies only for media analysis and link renewal. Sign in to the site in that browser, then analyse again."); }, error -> { configuringSession.set(false); cookieStatus.setText(error); });
        });
        clearCookies.setOnAction(event -> {
            configuringSession.set(true);
            model.request("mediaCookies", new JsonObject(), result -> { configuringSession.set(false); cookies.clear(); browser.setValue(null); remember(null, null); cookieStatus.setText("Media cookies cleared."); }, error -> { configuringSession.set(false); cookieStatus.setText(error); });
        });
        var maxActive = new Spinner<Integer>(1, 10, 3); maxActive.setId("queue-max-active"); maxActive.setPrefWidth(86);
        var scheduled = new CheckBox("Only download inside a daily window (for example night bundles)"); scheduled.setId("queue-scheduled");
        TextField windowStart = new TextField("23:00"), windowEnd = new TextField("06:00");
        windowStart.setPrefColumnCount(5); windowEnd.setPrefColumnCount(5); windowStart.setId("queue-start"); windowEnd.setId("queue-end");
        windowStart.disableProperty().bind(scheduled.selectedProperty().not()); windowEnd.disableProperty().bind(scheduled.selectedProperty().not());
        var budgeted = new CheckBox("Limit data per day"); budgeted.setId("queue-budgeted");
        var dailyMiB = new Spinner<Integer>(1, 1048576, 1024); dailyMiB.setEditable(true); dailyMiB.setId("queue-daily-mib"); dailyMiB.setPrefWidth(120);
        dailyMiB.disableProperty().bind(budgeted.selectedProperty().not());
        var limited = new CheckBox("Limit download speed to"); limited.setId("speed-limited");
        var limitKiBps = new Spinner<Integer>(8, 10_485_760, 512); limitKiBps.setEditable(true); limitKiBps.setId("speed-limit"); limitKiBps.setPrefWidth(120);
        limitKiBps.disableProperty().bind(limited.selectedProperty().not());
        var queueState = new Label("Loading the queue settings…"); queueState.setWrapText(true); queueState.setMaxWidth(520);
        var queueLoaded = new javafx.beans.property.SimpleBooleanProperty(false);
        model.request("queueStatus", new JsonObject(), status -> {
            maxActive.getValueFactory().setValue(status.get("maxActive").getAsInt());
            scheduled.setSelected(status.get("scheduled").getAsBoolean());
            windowStart.setText(status.get("start").getAsString()); windowEnd.setText(status.get("end").getAsString());
            budgeted.setSelected(status.get("budgeted").getAsBoolean());
            dailyMiB.getValueFactory().setValue((int) Math.min(1048576, status.get("dailyMiB").getAsLong()));
            long limit = status.has("limitKiBps") ? status.get("limitKiBps").getAsLong() : 0;
            limited.setSelected(limit > 0); if (limit > 0) limitKiBps.getValueFactory().setValue((int) Math.min(10_485_760, limit));
            queueState.setText(queueText(status)); queueLoaded.set(true);
        }, error -> queueState.setText("Queue settings are unavailable: " + error));
        var queueBox = new VBox(8, new Label("Queue and data saver"), new HBox(8, new Label("Downloads at the same time"), maxActive),
                scheduled, new HBox(8, new Label("From"), windowStart, new Label("to"), windowEnd, new Label("(24-hour HH:MM; the window may cross midnight)")),
                new HBox(8, budgeted, dailyMiB, new Label("MiB")), new HBox(8, limited, limitKiBps, new Label("KiB/s for all downloads")), queueState);
        var content = new VBox(10, new Label("Core socket"), socketField, new Label("Default download folder"), directoryField,
                new Label("Connections for new downloads"), workers, new Label("Connections for video and playlist downloads"), mediaWorkers,
                dark, notify, keepInTray, watchClipboard, sortByCategory,
                new Label("Media site access (optional)"), new HBox(8, browser, useBrowser),
                new Label("Or use an exported cookie file"), new HBox(8, cookies, chooseCookies), new HBox(8, useCookies, clearCookies), cookieStatus,
                queueBox, new Label("Settings are saved and kept after AJDM restarts."));
        var dialog = new Dialog<ButtonType>(); dialog.initOwner(stage); dialog.setTitle("Settings"); dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.APPLY); style(dialog);
        dialog.showAndWait().filter(ButtonType.APPLY::equals).ifPresent(result -> {
            try {
                Path nextSocket = Path.of(socketField.getText()).toAbsolutePath().normalize();
                directory = Path.of(directoryField.getText()).toAbsolutePath().normalize(); defaultConnections = workers.getValue();
                defaultMediaConnections = mediaWorkers.getValue();
                theme(dark.isSelected());
                settings = new UiSettings.Values(directory, defaultConnections, defaultMediaConnections, dark.isSelected(), notify.isSelected(),
                        keepInTray.isSelected(), watchClipboard.isSelected(), sortByCategory.isSelected(), settings.mediaBrowser(), settings.cookieFile());
                persist();
                if (queueLoaded.get()) {
                    var queue = new JsonObject(); queue.addProperty("maxActive", maxActive.getValue()); queue.addProperty("scheduled", scheduled.isSelected());
                    queue.addProperty("start", windowStart.getText().strip()); queue.addProperty("end", windowEnd.getText().strip());
                    queue.addProperty("budgeted", budgeted.isSelected()); queue.addProperty("dailyMiB", dailyMiB.getValue());
                    queue.addProperty("limitKiBps", limited.isSelected() ? limitKiBps.getValue() : 0);
                    model.request("configureQueue", queue, configured -> refreshQueueLine(), failure -> message("Queue settings were not saved: " + failure));
                }
                if (!nextSocket.equals(socket)) { socket = nextSocket; model.reconnect(socket); }
            } catch (java.nio.file.InvalidPathException invalid) { message("Enter a valid socket path and download folder."); }
        });
    }
    /** Data used today, the budget and what the queue waits for; refreshed every few seconds while the page is shown. */
    private void refreshQueueLine() {
        if (queueRefresh == null) {
            queueRefresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), event -> refreshQueueLine()));
            queueRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE); queueRefresh.play();
        }
        if (!model.connectedProperty().get()) return;
        model.request("queueStatus", new JsonObject(), status -> queueLine.setText(queueText(status)), error -> { });
    }
    static String queueText(JsonObject status) {
        long used = status.get("usedTodayBytes").getAsLong();
        String data = "Data today: " + Display.mib(used) + (status.get("budgeted").getAsBoolean() ? " of " + status.get("dailyMiB").getAsLong() + " MiB" : "");
        String window = status.get("scheduled").getAsBoolean() ? " · Window " + status.get("start").getAsString() + "–" + status.get("end").getAsString() : "";
        String hold = !status.has("holding") ? "" : status.get("holding").getAsString().equals("budget")
                ? " · Daily data budget reached: queued downloads continue tomorrow" : " · Waiting for the download window";
        long limit = status.has("limitKiBps") ? status.get("limitKiBps").getAsLong() : 0;
        return data + window + (limit > 0 ? " · Speed limit " + limit + " KiB/s" : "") + hold;
    }
    void theme(boolean dark) {
        if (dark) { if (!root.getStyleClass().contains("dark")) root.getStyleClass().add("dark"); } else root.getStyleClass().remove("dark");
    }
    private void remember(String browser, Path cookieFile) { settings = settings.withMedia(browser, cookieFile); persist(); }
    private void persist() {
        if (settingsFile == null) return;
        try { UiSettings.save(settingsFile, settings); }
        catch (java.io.IOException failure) { notice.setText("Settings applied, but could not be saved for next time."); }
    }
    @FXML void about() {
        var heading = new Label("Ariga Justine Download Manager"); heading.getStyleClass().add("detail-title");
        var dedication = new Label("AJDM stands for Ariga Justine Download Manager. It carries both our names. I built it in memory of my brother, so that something of us keeps moving forward, one byte at a time."); dedication.setWrapText(true); dedication.setMaxWidth(540);
        var content = new VBox(16, heading, new Label("My brother. My legacy."), dedication, new Label(System.getProperty("ajdm.version", "0.1.0-SNAPSHOT") + " · JavaFX desktop preview"),
                new Label("AJDM downloads the bytes. yt-dlp extracts metadata. ffmpeg joins local media."),
                new Label("OpenJFX: GPLv2 with Classpath Exception. Dependency notices: docs/DESKTOP_UI.md."));
        var logo = AjdmApp.class.getResource("ajdm-logo.png");
        if (logo != null) { var image = new javafx.scene.image.ImageView(logo.toExternalForm()); image.setFitWidth(540); image.setPreserveRatio(true); content.getChildren().addFirst(image); }
        var dialog = new Dialog<Void>(); dialog.initOwner(stage); dialog.setTitle("About AJDM"); dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); style(dialog); dialog.show();
    }
    void shortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), this::addDownload);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), model::refresh);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F11), () -> stage.setFullScreen(!stage.isFullScreen()));
    }
    private void message(String text) { var alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK); alert.initOwner(stage); alert.setHeaderText("Check settings"); alert.show(); }
    static void style(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
        if (dialog.getOwner() != null && dialog.getOwner().getScene().getRoot().getStyleClass().contains("dark")) dialog.getDialogPane().getStyleClass().add("dark");
    }
    static Region spacer() { var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); return spacer; }
}
