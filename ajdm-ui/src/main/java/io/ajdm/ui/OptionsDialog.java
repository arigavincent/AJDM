package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** IDM-style Options: General, Save To, Downloads, Connection and Video sites. OK applies and saves everything. */
final class OptionsDialog {
    enum Tab { GENERAL, SAVE_TO, DOWNLOADS, CONNECTION, VIDEO }
    private final MainWindow main;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TabPane tabs = new TabPane();
    private final Map<Tab, javafx.scene.control.Tab> byKind = new EnumMap<>(Tab.class);
    // General
    private final CheckBox clipboard = new CheckBox("Watch the clipboard and offer to download copied links");
    private final CheckBox tray = new CheckBox("Keep AJDM running in the tray when its window is closed");
    private final CheckBox notify = new CheckBox("Show a notification when a download finishes or fails");
    private final CheckBox dark = new CheckBox("Dark theme");
    // Save to
    private final TextField directory = new TextField();
    private final CheckBox sortByCategory = new CheckBox("Save each file type in its own folder (Video, Music, Documents, Compressed, Programs)");
    private final Map<Categories, TextField> folders = new EnumMap<>(Categories.class);
    // Downloads
    private final CheckBox fileInfo = new CheckBox("Show the Download File Info box before a download starts");
    private final CheckBox progress = new CheckBox("Show the progress window when a download starts");
    private final CheckBox complete = new CheckBox("Show the Download complete box");
    private final Spinner<Integer> connections, mediaConnections;
    // Connection
    private final Spinner<Integer> maxActive = new Spinner<>(1, 10, 3);
    private final CheckBox limited = new CheckBox("Use speed limiter");
    private final Spinner<Integer> limit = new Spinner<>(8, 10_485_760, 512);
    private final TextField socket = new TextField();
    private final Label queueState = new Label("Loading…");
    private final SimpleBooleanProperty queueLoaded = new SimpleBooleanProperty(false);
    private JsonObject queue;

    OptionsDialog(MainWindow main) {
        this.main = main;
        var settings = main.currentSettings();
        connections = new Spinner<>(1, 32, settings.connections()); mediaConnections = new Spinner<>(1, 32, settings.mediaConnections());
        clipboard.setSelected(settings.watchClipboard()); clipboard.setId("watch-clipboard");
        tray.setSelected(settings.keepInTray() && main.tray()); tray.setDisable(!main.tray()); tray.setId("keep-in-tray");
        if (!main.tray()) tray.setTooltip(new Tooltip("This desktop has no system tray."));
        notify.setSelected(settings.notifications()); notify.setId("notifications");
        dark.setSelected(settings.dark()); dark.setId("dark-theme");
        directory.setText(settings.directory().toString()); directory.setId("default-folder");
        sortByCategory.setSelected(settings.sortByCategory()); sortByCategory.setId("sort-by-category");
        fileInfo.setSelected(settings.showFileInfo()); fileInfo.setId("show-file-info");
        progress.setSelected(settings.showProgress()); progress.setId("show-progress");
        complete.setSelected(settings.showComplete()); complete.setId("show-complete");
        connections.setId("default-connections"); mediaConnections.setId("media-connections");
        maxActive.setId("queue-max-active"); limited.setId("speed-limited"); limit.setId("speed-limit"); limit.setEditable(true); limit.setPrefWidth(120);
        limit.disableProperty().bind(limited.selectedProperty().not());
        socket.setText(main.socket().toString()); socket.setId("core-socket");
        add(Tab.GENERAL, "General", general());
        add(Tab.SAVE_TO, "Save To", saveTo());
        add(Tab.DOWNLOADS, "Downloads", downloads());
        add(Tab.CONNECTION, "Connection", connection());
        add(Tab.VIDEO, "Video sites", new MediaAccess(main).content());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefSize(680, 470); tabs.setId("options-tabs");
        dialog.initOwner(main.stage()); dialog.setTitle("Options"); dialog.getDialogPane().setContent(tabs); dialog.getDialogPane().setId("options-dialog");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setId("options-ok");
        MainWindow.style(dialog);
        main.model().request("queueStatus", new JsonObject(), status -> {
            queue = status;
            maxActive.getValueFactory().setValue(status.get("maxActive").getAsInt());
            long kib = status.has("limitKiBps") ? status.get("limitKiBps").getAsLong() : 0;
            limited.setSelected(kib > 0); if (kib > 0) limit.getValueFactory().setValue((int) Math.min(10_485_760, kib));
            queueState.setText(MainWindow.queueText(status)); queueLoaded.set(true);
        }, error -> queueState.setText("Unavailable: " + error));
    }
    private void add(Tab kind, String title, Node content) {
        var scroller = new ScrollPane(content); scroller.setFitToWidth(true); scroller.getStyleClass().add("edge-to-edge");
        var tab = new javafx.scene.control.Tab(title, scroller); byKind.put(kind, tab); tabs.getTabs().add(tab);
    }
    private static VBox page(Node... children) { var box = new VBox(12, children); box.setPadding(new Insets(16)); return box; }
    private static Label heading(String text) { var label = new Label(text); label.getStyleClass().add("options-heading"); return label; }
    private Node general() {
        var browser = new Label("""
                Install the AJDM extension once: open your browser's extensions page (chrome://extensions or edge://extensions), \
                turn on Developer mode, choose Load unpacked and pick the "chrome" folder below. In Firefox use about:debugging. \
                Downloads you start in the browser then open here, and videos get a "Download with AJDM" button.""");
        browser.setWrapText(true);
        var openExtension = new Button("Open the extension folder"); openExtension.setId("open-extension-folder");
        var where = new Label(); where.setWrapText(true); where.getStyleClass().add("muted");
        Path extension = extensionFolder();
        where.setText(extension == null ? "The extension folder is part of the installed AJDM." : extension.toString());
        openExtension.setDisable(extension == null);
        openExtension.setOnAction(event -> main.model().localAction(() -> {
            try { java.awt.Desktop.getDesktop().open(extension.toFile()); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }, main::tell));
        return page(heading("While AJDM runs"), clipboard, tray, notify, dark, new Separator(), heading("Browser integration"), browser, openExtension, where);
    }
    /** The bundled extension: app/browser beside the Windows launcher, lib/app/browser on Linux, build/extension in development. */
    static Path extensionFolder() {
        var candidates = new java.util.ArrayList<Path>();
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null) {
            Path bin = Path.of(launcher).toAbsolutePath().getParent();
            candidates.add(bin.resolve("app/browser")); candidates.add(bin.resolveSibling("lib").resolve("app/browser"));
        }
        candidates.add(Path.of("build/extension").toAbsolutePath());
        for (var candidate : candidates) if (Files.isDirectory(candidate.resolve("chrome"))) return candidate;
        return null;
    }
    private Node saveTo() {
        var browse = new Button("…"); browse.setOnAction(event -> choose(directory));
        var main = new HBox(6, directory, browse); HBox.setHgrow(directory, Priority.ALWAYS);
        var grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        int row = 0;
        for (var category : new Categories[]{Categories.COMPRESSED, Categories.DOCUMENTS, Categories.MUSIC, Categories.PROGRAMS, Categories.VIDEO, Categories.OTHER}) {
            var field = new TextField(); field.setPromptText("Default: " + defaultFolder(category)); field.setId("folder-" + category.name().toLowerCase(java.util.Locale.ROOT));
            var remembered = this.main.currentSettings().folders().get(category); if (remembered != null) field.setText(remembered.toString());
            folders.put(category, field);
            var change = new Button("…"); change.setOnAction(event -> choose(field));
            var label = new Label(category == Categories.OTHER ? "General" : category.label); label.setGraphic(Icons.file(category, 16));
            grid.addRow(row++, label, field, change); GridPane.setHgrow(field, Priority.ALWAYS);
        }
        var note = new Label("Leave a category empty to use the default. \"Remember this path\" in the Download File Info box fills these in.");
        note.setWrapText(true); note.getStyleClass().add("muted");
        return page(heading("Default download folder"), main, sortByCategory, new Separator(), heading("Folder for each category"), grid, note);
    }
    private String defaultFolder(Categories category) {
        var base = main.currentSettings().directory();
        return (main.currentSettings().sortByCategory() && category != Categories.OTHER ? base.resolve(category.label) : base).toString();
    }
    private void choose(TextField field) {
        var chooser = new DirectoryChooser(); chooser.setTitle("Choose a folder");
        try { var current = Path.of(field.getText().isBlank() ? directory.getText() : field.getText()).toFile(); if (current.isDirectory()) chooser.setInitialDirectory(current); }
        catch (java.nio.file.InvalidPathException ignored) { }
        var chosen = chooser.showDialog(dialog.getOwner()); if (chosen != null) field.setText(chosen.getAbsolutePath());
    }
    private Node downloads() {
        var grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        grid.addRow(0, new Label("Connections per file"), connections); grid.addRow(1, new Label("Connections per video"), mediaConnections);
        var note = new Label("More connections help on slow or unstable links. AJDM adds and drops connections as the link allows.");
        note.setWrapText(true); note.getStyleClass().add("muted");
        return page(heading("When a download starts"), fileInfo, progress, complete, new Separator(), heading("Connections"), grid, note);
    }
    private Node connection() {
        var simultaneous = new HBox(8, new Label("Downloads at the same time"), maxActive); simultaneous.setAlignment(Pos.CENTER_LEFT);
        var speed = new HBox(8, limited, limit, new Label("KB/sec for all downloads together")); speed.setAlignment(Pos.CENTER_LEFT);
        queueState.setWrapText(true); queueState.getStyleClass().add("muted");
        var advanced = new TitledPane("Advanced", new VBox(6, new Label("Core connection (socket file)"), socket)); advanced.setExpanded(false);
        return page(heading("Queue"), simultaneous, new Separator(), heading("Speed limiter"), speed, queueState, advanced);
    }
    void show(Tab tab) {
        tabs.getSelectionModel().select(byKind.get(tab));
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> apply());
    }
    private void apply() {
        try {
            var settings = main.currentSettings();
            Path base = Path.of(directory.getText().strip()).toAbsolutePath().normalize();
            var chosen = new EnumMap<Categories, Path>(Categories.class);
            for (var entry : folders.entrySet()) if (!entry.getValue().getText().isBlank()) chosen.put(entry.getKey(), Path.of(entry.getValue().getText().strip()));
            main.theme(dark.isSelected());
            main.update(new UiSettings.Values(base, connections.getValue(), mediaConnections.getValue(), dark.isSelected(), notify.isSelected(),
                    tray.isSelected(), clipboard.isSelected(), sortByCategory.isSelected(), settings.mediaBrowser(), settings.cookieFile(),
                    fileInfo.isSelected(), progress.isSelected(), complete.isSelected(), chosen));
            if (queueLoaded.get()) {
                var copy = queue.deepCopy(); copy.addProperty("maxActive", maxActive.getValue());
                main.configureQueue(copy, limited.isSelected() ? limit.getValue() : 0, () -> main.tell("Options saved."));
            } else main.tell("Options saved.");
            main.reconnect(Path.of(socket.getText().strip()).toAbsolutePath().normalize());
        } catch (java.nio.file.InvalidPathException invalid) {
            var alert = new Alert(Alert.AlertType.ERROR, "Enter valid folder paths.", ButtonType.OK); alert.initOwner(main.stage()); MainWindow.style(alert); alert.show();
        }
    }
}
