package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The main window, laid out like Internet Download Manager: menu bar, a toolbar of large icon buttons, the category
 * tree on the left, the download list with IDM's columns, and a status bar. Several rows can be selected and the
 * toolbar acts on all of them. Double-clicking a finished file opens it; any other row opens its progress window.
 */
public final class MainWindow {
    private final Stage stage;
    private final DownloadsViewModel model;
    private Path socket;
    private UiSettings.Values settings;
    private final Path settingsFile;
    private boolean trayAvailable;
    private final BorderPane root = new BorderPane();
    private final TableView<DownloadRow> table = new TableView<>();
    private final TreeView<String> tree = new TreeView<>();
    private final TextField search = new TextField();
    private final Label connection = new Label(), queueLine = new Label(), totals = new Label();
    private final Label notice = new Label();
    private final Map<String, TreeItem<String>> treeItems = new HashMap<>();
    private final Map<Categories, TreeItem<String>> categoryItems = new EnumMap<>(Categories.class);
    private final Map<UUID, ProgressWindow> progress = new HashMap<>();
    private FilteredList<DownloadRow> filtered;
    private Button resume, stop, stopAll, delete, deleteCompleted, startQueue, stopQueue, limiter;
    private final CheckMenuItem limiterItem = new CheckMenuItem("Use speed limiter");
    private javafx.animation.Timeline queueRefresh;
    private JsonObject queueStatus;
    private boolean applyingLimiter;
    private boolean restoring;
    private java.util.Set<UUID> pendingSelection = java.util.Set.of();

    MainWindow(Stage stage, DownloadsViewModel model, Path socket, Path directory) {
        this(stage, model, socket, UiSettings.defaults(directory), null);
    }
    MainWindow(Stage stage, DownloadsViewModel model, Path socket, UiSettings.Values settings, Path settingsFile) {
        this.stage = stage; this.model = model; this.socket = socket; this.settings = settings; this.settingsFile = settingsFile;
    }
    UiSettings.Values currentSettings() { return settings; }
    DownloadsViewModel model() { return model; }
    Stage stage() { return stage; }
    void trayAvailable(boolean available) { trayAvailable = available; }
    boolean tray() { return trayAvailable; }

    /** Builds the window's content. */
    Parent build() {
        root.getStyleClass().add("idm"); root.setId("main-window");
        root.setTop(new VBox(menus(), toolbar()));
        // The list is reloaded twice a second; the selection is remembered before each reload and restored after it.
        model.rows().addListener((ListChangeListener<DownloadRow>) change -> {
            if (restoring) return;
            restoring = true; pendingSelection = selected().stream().map(DownloadRow::id).collect(java.util.stream.Collectors.toSet());
        });
        configureTable(); configureTree();
        var split = new SplitPane(tree, table); split.setDividerPositions(0.19); split.getStyleClass().add("main-split");
        SplitPane.setResizableWithParent(tree, false);
        root.setCenter(split);
        connection.textProperty().bind(model.connectionProperty()); connection.getStyleClass().add("status-text");
        queueLine.getStyleClass().add("status-text"); queueLine.setId("queue-status");
        totals.getStyleClass().add("status-text"); totals.setId("status-totals");
        notice.getStyleClass().add("status-notice"); notice.setId("status-notice");
        var bar = new HBox(14, connection, separator(), queueLine, spacer(), notice, separator(), totals);
        bar.setAlignment(Pos.CENTER_LEFT); bar.getStyleClass().add("status-bar"); root.setBottom(bar);
        model.rows().addListener((ListChangeListener<DownloadRow>) change -> {
            if (restoring) {
                var selection = table.getSelectionModel();
                if (!pendingSelection.isEmpty() && selection.getSelectedItems().stream().map(DownloadRow::id).collect(java.util.stream.Collectors.toSet()).size() != pendingSelection.size()) {
                    selection.clearSelection();
                    for (int i = 0; i < table.getItems().size(); i++) if (pendingSelection.contains(table.getItems().get(i).id())) selection.select(i);
                }
                restoring = false;
            }
            javafx.application.Platform.runLater(this::refreshAll);
        });
        model.connectedProperty().addListener((o, was, now) -> { refreshButtons(); if (now) refreshQueue(); });
        refreshAll(); refreshQueue();
        return root;
    }

    // ---- Menu bar and toolbar ----------------------------------------------------------------------------------
    private MenuBar menus() {
        var add = item("Add new download…", this::addUrl, new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));
        var video = item("Download video from a page…", this::media, new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        var playlist = item("Download a playlist…", this::playlist, null);
        var exit = item("Exit", javafx.application.Platform::exit, new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        var tasks = new Menu("Tasks", null, add, video, playlist, new SeparatorMenuItem(), exit);
        var open = item("Open", () -> selected().stream().filter(r -> r.state().equals("COMPLETED")).findFirst().ifPresent(r -> openFile(r, false)), null);
        var folder = item("Open folder", () -> selected().stream().findFirst().ifPresent(r -> openFile(r, true)), null);
        var properties = item("Properties", () -> selected().stream().findFirst().ifPresent(r -> showProgress(r.id())), null);
        var file = new Menu("File", null, open, folder, properties, new SeparatorMenuItem(),
                item("Delete", this::deleteSelected, new KeyCodeCombination(KeyCode.DELETE)), item("Delete completed", this::deleteCompleted, null));
        limiterItem.setOnAction(event -> toggleLimiter(limiterItem.isSelected()));
        var limiterMenu = new Menu("Speed Limiter", null, limiterItem, item("Settings…", () -> new OptionsDialog(this).show(OptionsDialog.Tab.CONNECTION), null));
        var downloads = new Menu("Downloads", null, item("Resume", this::resumeSelected, null), item("Stop", this::stopSelected, null),
                item("Stop all", this::pauseAll, null), new SeparatorMenuItem(),
                item("Start queue", this::resumeAll, null), item("Stop queue", this::stopQueue, null), item("Scheduler…", this::scheduler, null),
                limiterMenu, new SeparatorMenuItem(), item("Options…", this::options, null));
        var dark = new CheckMenuItem("Dark theme"); dark.setSelected(settings.dark());
        dark.setOnAction(event -> { theme(dark.isSelected()); update(new UiSettings.Values(settings.directory(), settings.connections(), settings.mediaConnections(), dark.isSelected(),
                settings.notifications(), settings.keepInTray(), settings.watchClipboard(), settings.sortByCategory(), settings.mediaBrowser(), settings.cookieFile(),
                settings.showFileInfo(), settings.showProgress(), settings.showComplete(), settings.folders())); });
        var categories = new CheckMenuItem("Categories"); categories.setSelected(true);
        categories.setOnAction(event -> ((SplitPane) root.getCenter()).getItems().setAll(categories.isSelected() ? List.of(tree, table) : List.of(table)));
        var full = item("Full screen", () -> stage.setFullScreen(!stage.isFullScreen()), new KeyCodeCombination(KeyCode.F11));
        var view = new Menu("View", null, categories, dark, full, item("Refresh", model::refresh, new KeyCodeCombination(KeyCode.F5)));
        var help = new Menu("Help", null, item("About AJDM", this::about, null));
        var bar = new MenuBar(tasks, file, downloads, view, help); bar.setId("menu-bar");
        return bar;
    }
    private static MenuItem item(String text, Runnable action, KeyCombination shortcut) {
        var item = new MenuItem(text); item.setOnAction(event -> action.run()); if (shortcut != null) item.setAccelerator(shortcut); return item;
    }
    private ToolBar toolbar() {
        var add = tool("Add URL", Icons.addUrl(30), this::addUrl, "tool-add", "Add a new download (Ctrl+N)");
        var video = tool("Video", Icons.grabber(30), this::media, "tool-video", "Choose a quality and download a video from YouTube, TikTok, Facebook and more");
        resume = tool("Resume", Icons.resume(30), this::resumeSelected, "tool-resume", "Resume the selected downloads");
        stop = tool("Stop", Icons.stop(30), this::stopSelected, "tool-stop", "Stop the selected downloads; they keep their progress");
        stopAll = tool("Stop All", Icons.stopAll(30), this::pauseAll, "tool-stop-all", "Stop every running download");
        delete = tool("Delete", Icons.delete(30), this::deleteSelected, "tool-delete", "Delete the selected downloads from the list (Del)");
        deleteCompleted = tool("Delete Co…", Icons.deleteCompleted(30), this::deleteCompleted, "tool-delete-completed", "Remove all completed downloads from the list");
        var options = tool("Options", Icons.options(30), this::options, "tool-options", "Settings for downloads, folders, browser and connection");
        var scheduler = tool("Scheduler", Icons.scheduler(30), this::scheduler, "tool-scheduler", "Download only at certain hours, how many at once, daily data limit");
        startQueue = tool("Start Qu…", Icons.startQueue(30), this::resumeAll, "tool-start-queue", "Start every stopped download in the queue");
        stopQueue = tool("Stop Qu…", Icons.stopQueue(30), this::stopQueue, "tool-stop-queue", "Stop the queue: running and waiting downloads stop");
        limiter = tool("Speed", Icons.speed(30), () -> toggleLimiter(!limiterOn()), "tool-speed", "Turn the speed limiter on or off");
        search.setPromptText("Search downloads"); search.setId("search"); search.setPrefWidth(160);
        search.textProperty().addListener((o, a, b) -> applyFilter());
        var bar = new ToolBar(add, video, new Separator(Orientation.VERTICAL), resume, stop, stopAll, new Separator(Orientation.VERTICAL),
                delete, deleteCompleted, new Separator(Orientation.VERTICAL), options, scheduler, startQueue, stopQueue, limiter, spacer(), search);
        bar.setId("toolbar"); bar.getStyleClass().add("idm-toolbar");
        return bar;
    }
    private static Button tool(String label, Node icon, Runnable action, String id, String tip) {
        var button = new Button(label, icon); button.setContentDisplay(ContentDisplay.TOP); button.getStyleClass().add("tool");
        button.setId(id); button.setTooltip(new Tooltip(tip)); button.setFocusTraversable(false); button.setOnAction(event -> action.run());
        button.setAccessibleText(tip);
        return button;
    }

    // ---- Category tree -----------------------------------------------------------------------------------------
    private void configureTree() {
        var all = node("all", "All Downloads", Icons.allDownloads(18));
        for (var category : Categories.values()) {
            var child = node("category:" + category.name(), category == Categories.OTHER ? "General" : category.label, Icons.file(category, 18));
            categoryItems.put(category, child); all.getChildren().add(child);
        }
        all.setExpanded(true);
        var unfinished = node("unfinished", "Unfinished", Icons.unfinished(18));
        var finished = node("finished", "Finished", Icons.finished(18));
        var queues = node("queues", "Queues", Icons.queue(18));
        queues.getChildren().add(node("queue:main", "Main download queue", Icons.startQueue(18))); queues.setExpanded(true);
        var top = new TreeItem<String>("Categories"); top.getChildren().addAll(List.of(all, unfinished, finished, queues)); top.setExpanded(true);
        tree.setRoot(top); tree.setShowRoot(false); tree.setId("category-tree");
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override protected void updateItem(String key, boolean empty) {
                super.updateItem(key, empty);
                if (empty || key == null) { setText(null); setGraphic(null); return; }
                setText(label(key)); setGraphic(getTreeItem().getGraphic());
            }
        });
        tree.getSelectionModel().select(all);
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> applyFilter());
        tree.setMinWidth(150); tree.setPrefWidth(220);
    }
    private final Map<String, String> treeLabels = new HashMap<>();
    private TreeItem<String> node(String key, String label, Node icon) {
        var item = new TreeItem<>(key, icon); treeItems.put(key, item); treeLabels.put(key, label); return item;
    }
    private String label(String key) {
        long count = model.rows().stream().filter(predicate(key)).count();
        return treeLabels.get(key) + (count > 0 && !key.equals("queues") ? "  (" + count + ")" : "");
    }
    private Predicate<DownloadRow> predicate(String key) {
        if (key == null || key.equals("all") || key.equals("queues")) return row -> true;
        if (key.equals("unfinished")) return row -> !row.state().equals("COMPLETED");
        if (key.equals("finished")) return row -> row.state().equals("COMPLETED");
        if (key.equals("queue:main")) return row -> row.state().equals("QUEUED") || row.active();
        if (key.startsWith("category:")) { var category = Categories.valueOf(key.substring(9)); return row -> category(row) == category; }
        return row -> true;
    }
    static Categories category(DownloadRow row) {
        if (row.kind().equals("PLAYLIST")) return Categories.VIDEO;
        var category = Categories.of(row.name());
        return category == Categories.OTHER && row.kind().equals("MEDIA") ? Categories.VIDEO : category;
    }
    private void applyFilter() {
        if (filtered == null) return;
        var item = tree.getSelectionModel().getSelectedItem();
        var byTree = predicate(item == null ? null : item.getValue());
        String text = search.getText().strip().toLowerCase(Locale.ROOT);
        filtered.setPredicate(row -> byTree.test(row) && (text.isEmpty() || row.name().toLowerCase(Locale.ROOT).contains(text)));
    }

    // ---- Download list ------------------------------------------------------------------------------------------
    private void configureTable() {
        table.setId("downloads-table"); table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        var name = new TableColumn<DownloadRow, DownloadRow>("File Name"); name.setPrefWidth(360);
        name.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        name.setComparator(java.util.Comparator.comparing(row -> row.name().toLowerCase(Locale.ROOT)));
        name.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(DownloadRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) { setText(null); setGraphic(null); return; }
                setText(row.name()); setGraphic(Icons.file(category(row), 18)); setGraphicTextGap(7);
            }
        });
        var size = new TableColumn<DownloadRow, Long>("Size"); size.setPrefWidth(95);
        size.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().total()));
        size.setCellFactory(column -> text(Display::size, Pos.CENTER_RIGHT));
        var status = new TableColumn<DownloadRow, DownloadRow>("Status"); status.setPrefWidth(130);
        status.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        status.setComparator(java.util.Comparator.comparingDouble(row -> row.reading().fraction()));
        status.setCellFactory(column -> new StatusCell());
        var left = new TableColumn<DownloadRow, Long>("Time left"); left.setPrefWidth(110);
        left.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().reading().etaSeconds()));
        left.setCellFactory(column -> text(Display::timeLeft, Pos.CENTER_LEFT));
        var rate = new TableColumn<DownloadRow, Double>("Transfer rate"); rate.setPrefWidth(110);
        rate.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().reading().bytesPerSecond()));
        rate.setCellFactory(column -> text(Display::rate, Pos.CENTER_RIGHT));
        var description = new TableColumn<DownloadRow, String>("Description"); description.setPrefWidth(330);
        description.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(describe(cell.getValue())));
        table.getColumns().setAll(List.of(name, size, status, left, rate, description));
        table.setFixedCellSize(26);
        table.setPlaceholder(new Label("No downloads yet. Click Add URL, copy a link, or download from your browser."));
        filtered = new FilteredList<>(model.rows()); var sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty()); table.setItems(sorted);
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<DownloadRow>) change -> refreshButtons());
        table.setRowFactory(view -> {
            var row = new TableRow<DownloadRow>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) activate(row.getItem());
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE) { deleteSelected(); event.consume(); }
            else if (event.getCode() == KeyCode.ENTER && table.getSelectionModel().getSelectedItem() != null) { activate(table.getSelectionModel().getSelectedItem()); event.consume(); }
        });
        table.setContextMenu(contextMenu());
    }
    private static <T> TableCell<DownloadRow, T> text(Function<T, String> format, Pos alignment) {
        var cell = new TableCell<DownloadRow, T>() {
            @Override protected void updateItem(T value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : format.apply(value)); }
        };
        cell.getStyleClass().add(alignment == Pos.CENTER_RIGHT ? "cell-right" : "cell-left"); return cell;
    }
    /** The percentage over a slim bar, like IDM's progress text. */
    private static final class StatusCell extends TableCell<DownloadRow, DownloadRow> {
        private final ProgressBar bar = new ProgressBar(); private final Label text = new Label(); private final StackPane box = new StackPane(bar, text);
        StatusCell() {
            bar.setMaxWidth(Double.MAX_VALUE); bar.getStyleClass().add("row-progress"); text.getStyleClass().add("row-progress-text");
            StackPane.setAlignment(text, Pos.CENTER_LEFT); box.prefWidthProperty().bind(widthProperty().subtract(14)); setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }
        @Override protected void updateItem(DownloadRow row, boolean empty) {
            super.updateItem(row, empty); setText(null);
            if (empty || row == null) { setGraphic(null); return; }
            text.setText(Display.status(row));
            double fraction = row.state().equals("COMPLETED") ? 1 : row.total() > 0 ? Math.min(1, (double) row.written() / row.total()) : 0;
            bar.setProgress(fraction);
            bar.setVisible(!row.state().equals("COMPLETED") && fraction > 0);
            box.getStyleClass().setAll("row-status", "state-" + row.state().toLowerCase(Locale.ROOT));
            setGraphic(box);
        }
    }
    static String describe(DownloadRow row) {
        if (!row.error().isEmpty()) return row.error();
        if (row.state().equals("QUEUED")) return switch (row.waiting()) {
            case "schedule" -> "Waiting for the scheduled time";
            case "budget" -> "Daily data limit reached; continues tomorrow";
            default -> "Waiting for a free download slot";
        };
        if (row.kind().equals("PLAYLIST")) return "Playlist";
        if (row.state().equals("PAUSED") && !row.resumeSupported() && row.written() > 0) return "This server cannot resume; it will start again";
        Path target = Path.of(row.target());
        return target.getParent() == null ? "" : target.getParent().toString();
    }
    private ContextMenu contextMenu() {
        var open = item("Open", () -> selected().stream().findFirst().ifPresent(r -> openFile(r, false)), null);
        var folder = item("Open folder", () -> selected().stream().findFirst().ifPresent(r -> openFile(r, true)), null);
        var resumeItem = item("Resume download", this::resumeSelected, null);
        var stopItem = item("Stop download", this::stopSelected, null);
        var restart = item("Redownload (source changed)", this::restartSelected, null);
        var remove = item("Delete", this::deleteSelected, null);
        var properties = item("Properties", () -> selected().stream().findFirst().ifPresent(r -> showProgress(r.id())), null);
        var menu = new ContextMenu(open, folder, new SeparatorMenuItem(), resumeItem, stopItem, restart, new SeparatorMenuItem(), remove, new SeparatorMenuItem(), properties);
        menu.setOnShowing(event -> {
            var rows = selected(); var first = rows.isEmpty() ? null : rows.getFirst();
            open.setDisable(first == null || !first.state().equals("COMPLETED") || first.kind().equals("PLAYLIST"));
            folder.setDisable(first == null); properties.setDisable(first == null);
            resumeItem.setDisable(rows.stream().noneMatch(DownloadRow::resumable)); stopItem.setDisable(rows.stream().noneMatch(DownloadRow::active));
            restart.setDisable(rows.stream().noneMatch(DownloadRow::restartable)); remove.setDisable(rows.isEmpty());
        });
        return menu;
    }
    private List<DownloadRow> selected() { return List.copyOf(table.getSelectionModel().getSelectedItems()); }
    private void activate(DownloadRow row) {
        if (row.state().equals("COMPLETED") && !row.kind().equals("PLAYLIST")) openFile(row, false);
        else if (row.kind().equals("PLAYLIST")) new PlaylistStatusDialog(this, row).show();
        else showProgress(row.id());
    }
    void openFile(DownloadRow row, boolean folder) {
        model.localAction(() -> DesktopGateway.openLocal(row, folder), this::tell);
    }
    private void refreshAll() {
        for (var key : treeItems.keySet()) { var item = treeItems.get(key); item.setValue(null); item.setValue(key); }
        refreshButtons();
        long active = model.rows().stream().filter(DownloadRow::active).count();
        double speed = model.rows().stream().filter(DownloadRow::active).mapToDouble(row -> Math.max(0, row.reading().bytesPerSecond())).sum();
        int count = model.rows().size();
        totals.setText(count + (count == 1 ? " download · " : " downloads · ") + active + " active" + (speed > 0 ? " · " + Display.rate(speed) : ""));
        for (var window : List.copyOf(progress.values())) window.refresh();
        table.refresh();
    }
    private void refreshButtons() {
        if (resume == null) return;
        var rows = selected(); boolean connected = model.connectedProperty().get();
        resume.setDisable(!connected || rows.stream().noneMatch(DownloadRow::resumable));
        stop.setDisable(!connected || rows.stream().noneMatch(DownloadRow::active));
        delete.setDisable(!connected || rows.isEmpty());
        stopAll.setDisable(!connected || model.rows().stream().noneMatch(DownloadRow::active));
        deleteCompleted.setDisable(!connected || model.rows().stream().noneMatch(row -> row.state().equals("COMPLETED")));
        startQueue.setDisable(!connected || model.rows().stream().noneMatch(row -> row.state().equals("PAUSED") && row.resumable()));
        stopQueue.setDisable(!connected || model.rows().stream().noneMatch(DownloadRow::active));
    }

    // ---- Actions ---------------------------------------------------------------------------------------------
    void addUrl() { new AddUrlDialog(this).show(); }
    void media() { new QualityDialog(this, null, null, null).show(); }
    void playlist() { new PlaylistDialog(stage, model, settings.folderFor(Categories.VIDEO), settings.mediaConnections()).show(); }
    void options() { new OptionsDialog(this).show(OptionsDialog.Tab.GENERAL); }
    void scheduler() { new SchedulerDialog(this).show(); }
    private void resumeSelected() { each("resume", selected().stream().filter(DownloadRow::resumable).toList(), "Resumed"); }
    private void stopSelected() { each("pause", selected().stream().filter(DownloadRow::active).filter(MainWindow::pausable).toList(), "Stopped"); }
    private void restartSelected() { each("restart", selected().stream().filter(DownloadRow::restartable).toList(), "Restarted"); }
    /** Pause every running download, or resume every stopped one (toolbar, tray and queue). */
    void pauseAll() { each("pause", model.rows().stream().filter(DownloadRow::active).filter(MainWindow::pausable).toList(), "Stopped"); }
    void resumeAll() { each("resume", model.rows().stream().filter(row -> row.state().equals("PAUSED") && row.resumable()).toList(), "Started"); }
    private void stopQueue() { pauseAll(); }
    private static boolean pausable(DownloadRow row) { return !row.state().equals("MERGING") && !row.state().equals("VERIFYING"); }
    private void each(String command, List<DownloadRow> rows, String verb) {
        if (rows.isEmpty()) return;
        var ids = rows.stream().map(DownloadRow::id).toList();
        model.requestEach(command, ids, count -> tell(verb + " " + count + " download" + (count == 1 ? "" : "s") + "."));
    }
    private void deleteSelected() {
        var rows = selected(); if (rows.isEmpty() || !model.connectedProperty().get()) return;
        var confirm = new Alert(Alert.AlertType.CONFIRMATION); confirm.initOwner(stage); confirm.setTitle("Delete");
        confirm.setHeaderText(rows.size() == 1 ? "Delete \"" + rows.getFirst().name() + "\" from the list?" : "Delete " + rows.size() + " downloads from the list?");
        var files = new CheckBox("Also delete the files from disk"); files.setId("delete-files");
        var note = new Label(rows.stream().anyMatch(DownloadRow::active) ? "Running downloads are stopped first." : "");
        confirm.getDialogPane().setContent(new VBox(10, files, note)); confirm.getDialogPane().setId("delete-confirmation");
        style(confirm);
        confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> {
            var ids = rows.stream().map(DownloadRow::id).toList();
            ids.forEach(id -> { var window = progress.remove(id); if (window != null) window.close(); });
            model.removeEach(ids, files.isSelected(), count -> tell("Deleted " + count + " of " + ids.size() + (files.isSelected() ? " with their files." : ". Files kept.")));
        });
    }
    private void deleteCompleted() {
        var rows = model.rows().stream().filter(row -> row.state().equals("COMPLETED")).toList(); if (rows.isEmpty()) return;
        var confirm = new Alert(Alert.AlertType.CONFIRMATION, "The files stay on disk.", ButtonType.CANCEL, ButtonType.OK); confirm.initOwner(stage);
        confirm.setTitle("Delete completed"); confirm.setHeaderText("Remove " + rows.size() + " completed download" + (rows.size() == 1 ? "" : "s") + " from the list?");
        style(confirm);
        confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok ->
                model.removeEach(rows.stream().map(DownloadRow::id).toList(), false, count -> tell("Removed " + count + " completed downloads.")));
    }

    // ---- Links from the browser, the clipboard and Add URL -------------------------------------------------------
    void offer(String link, String referrer, String name, boolean mediaPage) { offer(link, referrer, name, mediaPage, -1, null, null); }
    /**
     * A link to download, like IDM: a file opens the Download File Info box at once (with the size the browser
     * already knows); a video page opens the quality list, or straight the File Info box when a quality was chosen
     * in the page's AJDM menu. With the File Info box turned off, the download starts directly.
     */
    void offer(String link, String referrer, String name, boolean mediaPage, long sizeBytes, String formatId, String audioId) {
        stage.show(); stage.setIconified(false); stage.toFront();
        if (mediaPage) { new QualityDialog(this, link, formatId, audioId).show(); return; }
        String guess = name != null ? name : guessName(link);
        var dialog = new FileInfoDialog(this, link, referrer, guess, sizeBytes);
        if (settings.showFileInfo()) dialog.show(); else dialog.startNow();
    }
    static String guessName(String link) {
        try {
            String path = java.net.URI.create(link).getPath();
            if (path == null) return "";
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            return java.net.URLDecoder.decode(leaf.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    /** A download was just added: IDM opens its progress window. */
    void started(UUID id) {
        model.refresh();
        if (settings.showProgress()) showProgress(id);
    }
    void showProgress(UUID id) {
        var window = progress.get(id);
        if (window == null) { window = new ProgressWindow(this, id); progress.put(id, window); }
        window.show();
    }
    void progressClosed(UUID id) { progress.remove(id); }
    DownloadRow row(UUID id) { return model.rows().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null); }
    /** A download finished: its progress window closes and IDM's "Download complete" box appears. */
    void completed(CompletionWatcher.Event event) {
        var window = progress.get(event.id());
        boolean dialog = settings.showComplete() && event.completed();
        if (window != null) window.finished(event);
        else if (dialog) { var row = row(event.id()); if (row != null) new CompleteDialog(this, row).show(); }
    }

    // ---- Queue, scheduler and speed limiter --------------------------------------------------------------------
    void refreshQueue() {
        if (queueRefresh == null) {
            queueRefresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), event -> refreshQueue()));
            queueRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE); queueRefresh.play();
        }
        if (!model.connectedProperty().get()) return;
        model.request("queueStatus", new JsonObject(), status -> {
            queueStatus = status; queueLine.setText(queueText(status));
            applyingLimiter = true; limiterItem.setSelected(limiterOn()); applyingLimiter = false;
            limiter.getStyleClass().remove("tool-on"); if (limiterOn()) limiter.getStyleClass().add("tool-on");
            limiter.setText(limiterOn() ? status.get("limitKiBps").getAsLong() + " KB/s" : "Speed");
        }, error -> { });
    }
    JsonObject queueStatus() { return queueStatus; }
    private boolean limiterOn() { return queueStatus != null && queueStatus.has("limitKiBps") && queueStatus.get("limitKiBps").getAsLong() > 0; }
    /** Turns the global speed limiter on (with the last limit, 512 KB/s at first) or off, keeping the other queue settings. */
    private void toggleLimiter(boolean on) {
        if (applyingLimiter || queueStatus == null) return;
        int limit = on ? (int) Math.max(8, rememberedLimit()) : 0;
        configureQueue(queueStatus, limit, () -> tell(on ? "Speed limiter on: " + limit + " KB/s." : "Speed limiter off."));
    }
    private long lastLimit = 512;
    private long rememberedLimit() { return lastLimit; }
    void rememberLimit(long kib) { if (kib > 0) lastLimit = kib; }
    void configureQueue(JsonObject status, int limitKiBps, Runnable done) {
        var queue = new JsonObject();
        queue.addProperty("maxActive", status.get("maxActive").getAsInt()); queue.addProperty("scheduled", status.get("scheduled").getAsBoolean());
        queue.addProperty("start", status.get("start").getAsString()); queue.addProperty("end", status.get("end").getAsString());
        queue.addProperty("budgeted", status.get("budgeted").getAsBoolean()); queue.addProperty("dailyMiB", status.get("dailyMiB").getAsInt());
        queue.addProperty("limitKiBps", limitKiBps); rememberLimit(limitKiBps);
        model.request("configureQueue", queue, result -> { refreshQueue(); done.run(); }, failure -> tell("Not saved: " + failure));
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

    // ---- Settings, theme and about -----------------------------------------------------------------------------
    void update(UiSettings.Values next) {
        settings = next; persist();
    }
    void reconnect(Path next) { if (!next.equals(socket)) { socket = next; model.reconnect(socket); } }
    Path socket() { return socket; }
    void theme(boolean dark) {
        if (dark) { if (!root.getStyleClass().contains("dark")) root.getStyleClass().add("dark"); } else root.getStyleClass().remove("dark");
    }
    private void persist() {
        if (settingsFile == null) return;
        try { UiSettings.save(settingsFile, settings); }
        catch (java.io.IOException failure) { tell("Settings applied, but could not be saved for next time."); }
    }
    void tell(String text) { notice.setText(text); }
    void about() {
        var heading = new Label("Ariga Justine Download Manager"); heading.getStyleClass().add("about-title");
        var dedication = new Label("AJDM stands for Ariga Justine Download Manager. It carries both our names. I built it in memory of my brother, so that something of us keeps moving forward, one byte at a time."); dedication.setWrapText(true); dedication.setMaxWidth(540);
        var content = new VBox(14, heading, new Label("My brother. My legacy."), dedication, new Label(System.getProperty("ajdm.version", "0.1.0-SNAPSHOT") + " · Desktop preview"),
                new Label("AJDM downloads the bytes. yt-dlp extracts metadata. ffmpeg joins local media."),
                new Label("OpenJFX: GPLv2 with Classpath Exception. Dependency notices: docs/DESKTOP_UI.md."));
        content.setId("about-content");
        var logo = AjdmApp.class.getResource("ajdm-logo.png");
        if (logo != null) { var image = new javafx.scene.image.ImageView(logo.toExternalForm()); image.setFitWidth(540); image.setPreserveRatio(true); content.getChildren().addFirst(image); }
        var dialog = new Dialog<Void>(); dialog.initOwner(stage); dialog.setTitle("About AJDM"); dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); style(dialog); dialog.show();
    }
    void shortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN), model::refresh);
    }
    static void style(Dialog<?> dialog) {
        dialog.getDialogPane().getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("idm");
        if (dialog.getOwner() != null && dialog.getOwner().getScene().getRoot().getStyleClass().contains("dark")) dialog.getDialogPane().getStyleClass().add("dark");
    }
    /** Styles a separate window (progress, complete) like the main one. */
    void style(Stage window, Parent content) {
        content.getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
        content.getStyleClass().add("idm");
        if (root.getStyleClass().contains("dark")) content.getStyleClass().add("dark");
        window.initOwner(null);
        var icon = AjdmApp.class.getResource("ajdm-logo.png"); if (icon != null) window.getIcons().setAll(stage.getIcons());
    }
    static Region spacer() { var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); return spacer; }
    private static Separator separator() { var separator = new Separator(Orientation.VERTICAL); separator.setPadding(new Insets(0, 2, 0, 2)); return separator; }
}
