package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import java.nio.file.Path;

/**
 * IDM's "Download File Info" box, shown the moment a link arrives: address, category, Save As and size, with Start
 * Download, Download Later and Cancel. It closes as soon as a button is pressed; the download is added in the
 * background and its progress window opens. Rarely needed request details stay folded away.
 */
final class FileInfoDialog {
    private final MainWindow window;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField url = new TextField(), saveAs = new TextField();
    private final ComboBox<Categories> category = new ComboBox<>();
    private final CheckBox remember = new CheckBox();
    private final Label size = new Label();
    private final Spinner<Integer> connections;
    private final TextField checksum = new TextField(), referer = new TextField(), userAgent = new TextField();
    private final PasswordField cookies = new PasswordField(), authorization = new PasswordField();
    private final Label message = new Label();
    private final ButtonType later = new ButtonType("Download Later", ButtonBar.ButtonData.OTHER);
    private final ButtonType start = new ButtonType("Start Download", ButtonBar.ButtonData.OK_DONE);

    FileInfoDialog(MainWindow window, String link, String referrer, String name, long sizeBytes) {
        this.window = window;
        var settings = window.currentSettings();
        connections = new Spinner<>(1, 32, settings.connections());
        String leaf = name == null || name.isBlank() ? "download" : name;
        var kind = Categories.of(leaf);
        url.setText(link == null ? "" : link); url.setId("file-info-url");
        category.getItems().setAll(Categories.OTHER, Categories.COMPRESSED, Categories.DOCUMENTS, Categories.MUSIC, Categories.PROGRAMS, Categories.VIDEO);
        category.setConverter(new StringConverter<>() {
            @Override public String toString(Categories value) { return value == null ? "" : value == Categories.OTHER ? "General" : value.label; }
            @Override public Categories fromString(String text) { return null; }
        });
        category.setValue(kind); category.setId("file-info-category");
        saveAs.setText(settings.folderFor(kind).resolve(leaf).toString()); saveAs.setId("file-info-save-as");
        remember.setId("file-info-remember");
        updateRememberLabel();
        category.valueProperty().addListener((o, previous, next) -> {
            if (next == null) return;
            saveAs.setText(window.currentSettings().folderFor(next).resolve(filename()).toString()); updateRememberLabel();
        });
        var browse = new Button("…"); browse.setId("file-info-browse"); browse.setTooltip(new Tooltip("Choose where to save"));
        browse.setOnAction(event -> {
            var chooser = new FileChooser(); chooser.setTitle("Save As"); chooser.setInitialFileName(filename());
            var folder = folder().toFile(); if (folder.isDirectory()) chooser.setInitialDirectory(folder);
            var chosen = chooser.showSaveDialog(dialog.getOwner()); if (chosen != null) saveAs.setText(chosen.getAbsolutePath());
        });
        size.setId("file-info-size"); size.getStyleClass().add("file-size");
        size.setText(sizeBytes > 0 ? Display.size(sizeBytes) : "Size shown when the download starts");
        referer.setText(referrer == null ? "" : referrer);
        var form = new GridPane(); form.setHgap(10); form.setVgap(9);
        form.addRow(0, new Label("URL"), url);
        form.addRow(1, new Label("Category"), new HBox(8, category));
        form.addRow(2, new Label("Save As"), new HBox(6, saveAs, browse));
        form.add(remember, 1, 3);
        GridPane.setHgrow(url, Priority.ALWAYS); HBox.setHgrow(saveAs, Priority.ALWAYS);
        var columns = new ColumnConstraints(); columns.setMinWidth(70); var grow = new ColumnConstraints(); grow.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().setAll(columns, grow);
        var badge = new VBox(6, Icons.file(kind, 52), size); badge.setAlignment(Pos.TOP_CENTER); badge.setMinWidth(150); badge.setPadding(new Insets(0, 0, 0, 10));
        checksum.setPromptText("Optional SHA-256 to verify the file"); referer.setPromptText("Page the link came from");
        userAgent.setPromptText("Browser User-Agent"); cookies.setPromptText("Cookie header"); authorization.setPromptText("Authorization header");
        var more = new GridPane(); more.setHgap(10); more.setVgap(7);
        more.addRow(0, new Label("Connections"), connections); more.addRow(1, new Label("Checksum"), checksum);
        more.addRow(2, new Label("Referrer"), referer); more.addRow(3, new Label("User-Agent"), userAgent);
        more.addRow(4, new Label("Cookie"), cookies); more.addRow(5, new Label("Authorization"), authorization);
        more.getColumnConstraints().setAll(columns, grow); connections.setId("file-info-connections");
        var advanced = new TitledPane("More options", more); advanced.setExpanded(false); advanced.setId("file-info-more");
        message.getStyleClass().add("dialog-message"); message.setWrapText(true);
        var top = new HBox(form, badge); HBox.setHgrow(form, Priority.ALWAYS);
        var content = new VBox(12, top, advanced, message); content.setPrefWidth(640);
        dialog.initOwner(window.stage()); dialog.setTitle("Download File Info");
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setId("file-info-dialog");
        dialog.getDialogPane().getButtonTypes().setAll(later, start, ButtonType.CANCEL);
        var startButton = (Button) dialog.getDialogPane().lookupButton(start); startButton.setId("file-info-start"); startButton.setDefaultButton(true);
        startButton.getStyleClass().add("primary-button");
        var laterButton = (Button) dialog.getDialogPane().lookupButton(later); laterButton.setId("file-info-later");
        startButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { if (!submit(true)) event.consume(); });
        laterButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { if (!submit(false)) event.consume(); });
        dialog.setResizable(false); MainWindow.style(dialog);
        dialog.setOnShown(event -> startButton.requestFocus());
    }
    void authorization(String header) { if (header != null) authorization.setText(header); }
    private void updateRememberLabel() {
        var value = category.getValue();
        remember.setText("Remember this path for \"" + (value == null || value == Categories.OTHER ? "General" : value.label) + "\" category");
    }
    private String filename() {
        try { var leaf = Path.of(saveAs.getText().strip()).getFileName(); return leaf == null ? "download" : leaf.toString(); }
        catch (java.nio.file.InvalidPathException e) { return "download"; }
    }
    private Path folder() {
        try { var parent = Path.of(saveAs.getText().strip()).toAbsolutePath().getParent(); return parent == null ? window.currentSettings().directory() : parent; }
        catch (java.nio.file.InvalidPathException e) { return window.currentSettings().directory(); }
    }
    /** Validates, closes at once and adds the download in the background. False keeps the box open with a message. */
    private boolean submit(boolean startNow) {
        JsonObject payload;
        try {
            payload = FormRequests.base(url.getText(), folder().toString(), connections.getValue());
            FormRequests.filename(payload, filename());
            FormRequests.checksum(payload, checksum.getText());
            FormRequests.requestDetails(payload, referer.getText(), userAgent.getText(), cookies.getText(), authorization.getText());
        } catch (IllegalArgumentException invalid) {
            message.setText(invalid.getMessage() == null ? "Check the address and the Save As path." : invalid.getMessage()); return false;
        }
        if (!startNow) payload.addProperty("start", false);
        if (remember.isSelected() && category.getValue() != null) window.update(window.currentSettings().withFolder(category.getValue(), folder()));
        String name = filename();
        window.model().request("add", payload, result -> {
            var id = java.util.UUID.fromString(result.get("downloadId").getAsString());
            if (startNow) window.started(id); else { window.model().refresh(); window.tell("\"" + name + "\" added. Start it with Resume or Start Queue."); }
        }, failure -> {
            var alert = new Alert(Alert.AlertType.ERROR, failure, ButtonType.OK); alert.initOwner(window.stage());
            alert.setTitle("Download not added"); alert.setHeaderText("AJDM could not add \"" + name + "\""); MainWindow.style(alert); alert.show();
        });
        return true;
    }
    /** IDM with "show this box" turned off: start right away with the defaults. */
    void startNow() { submit(true); }
    void show() { dialog.show(); }
}
