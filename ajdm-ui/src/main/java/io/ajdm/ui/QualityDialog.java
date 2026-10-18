package io.ajdm.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Downloading a video, IDM style: the video's title and a short list of qualities with sizes (sound paired in when the
 * site serves it separately), the best one already selected; "Show all formats" lists every stream for experts.
 * A page the browser already analysed opens instantly. Start Download closes the box and the progress window opens.
 */
final class QualityDialog {
    private final MainWindow window;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField url = new TextField(), folder = new TextField(), filename = new TextField();
    private final Button analyse = new Button("Get qualities");
    private final Label title = new Label(), status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ListView<JsonObject> qualities = new ListView<>();
    private final TableView<JsonObject> formats = new TableView<>();
    private final CheckBox showAll = new CheckBox("Show all formats");
    private final ComboBox<JsonObject> audioTrack = new ComboBox<>();
    private final Spinner<Integer> connections;
    private final ButtonType later = new ButtonType("Download Later", ButtonBar.ButtonData.OTHER);
    private final ButtonType start = new ButtonType("Start Download", ButtonBar.ButtonData.OK_DONE);
    private final String wantedFormat, wantedAudio;
    private String analysed = "";
    private final javafx.beans.property.BooleanProperty loading = new javafx.beans.property.SimpleBooleanProperty(false);
    private VBox content;

    QualityDialog(MainWindow window, String link, String formatId, String audioId) {
        this.window = window; this.wantedFormat = formatId; this.wantedAudio = audioId;
        var settings = window.currentSettings();
        connections = new Spinner<>(1, 32, settings.mediaConnections()); connections.setId("quality-connections");
        url.setText(link == null ? "" : link); url.setPromptText("Video page address (YouTube, TikTok, Facebook, Instagram, X, Vimeo…)"); url.setId("quality-url");
        folder.setText(settings.folderFor(Categories.VIDEO).toString()); folder.setId("quality-folder");
        filename.setPromptText("Named after the video"); filename.setId("quality-filename");
        analyse.setId("quality-analyse"); analyse.disableProperty().bind(url.textProperty().isEmpty().or(loading)); analyse.setOnAction(event -> load());
        title.getStyleClass().add("video-title"); title.setWrapText(true); title.setId("quality-title");
        status.getStyleClass().add("dialog-message"); status.setWrapText(true); status.setId("quality-status");
        spinner.setPrefSize(22, 22); spinner.setVisible(false);
        qualities.setId("quality-list"); qualities.setPrefHeight(230);
        qualities.setPlaceholder(new Label("Qualities appear here."));
        qualities.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(JsonObject entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) { setGraphic(null); setText(null); return; }
                boolean audio = entry.get("height").getAsInt() == 0;
                var name = new Label(entry.get("label").getAsString()); name.getStyleClass().add("quality-name");
                long bytes = entry.get("sizeBytes").getAsLong();
                var size = new Label(bytes < 0 ? "size unknown" : (entry.get("approximate").getAsBoolean() ? "about " : "") + Display.size(bytes));
                size.getStyleClass().add("quality-size");
                var row = new HBox(10, Icons.file(audio ? Categories.MUSIC : Categories.VIDEO, 18), name, MainWindow.spacer(), size);
                row.setAlignment(Pos.CENTER_LEFT); setText(null); setGraphic(row);
            }
        });
        qualities.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && qualities.getSelectionModel().getSelectedItem() != null) submit(true); });
        buildFormatsTable();
        showAll.setId("quality-show-all");
        showAll.selectedProperty().addListener((o, a, all) -> { qualities.setVisible(!all); qualities.setManaged(!all); formats.setVisible(all); formats.setManaged(all); refreshButtons(); });
        formats.setVisible(false); formats.setManaged(false);
        var browse = new Button("…"); browse.setOnAction(event -> {
            var chooser = new DirectoryChooser(); chooser.setTitle("Save videos in");
            var current = Path.of(folder.getText()).toFile(); if (current.isDirectory()) chooser.setInitialDirectory(current);
            var chosen = chooser.showDialog(dialog.getOwner()); if (chosen != null) folder.setText(chosen.getAbsolutePath());
        });
        var source = new HBox(8, url, analyse); HBox.setHgrow(url, Priority.ALWAYS);
        var heading = new HBox(10, Icons.file(Categories.VIDEO, 36), new VBox(3, title, new HBox(8, spinner, status)));
        heading.setAlignment(Pos.CENTER_LEFT);
        var save = new GridPane(); save.setHgap(10); save.setVgap(8);
        save.addRow(0, new Label("Save in"), new HBox(6, folder, browse)); save.addRow(1, new Label("File name"), filename);
        save.addRow(2, new Label("Connections"), connections);
        HBox.setHgrow(folder, Priority.ALWAYS); GridPane.setHgrow(filename, Priority.ALWAYS);
        var grow = new ColumnConstraints(); grow.setHgrow(Priority.ALWAYS); var fixed = new ColumnConstraints(); fixed.setMinWidth(80);
        save.getColumnConstraints().setAll(fixed, grow);
        var audioRow = new HBox(8, new Label("Sound"), audioTrack); audioRow.setAlignment(Pos.CENTER_LEFT);
        audioRow.visibleProperty().bind(showAll.selectedProperty()); audioRow.managedProperty().bind(showAll.selectedProperty());
        content = new VBox(10, source, heading, qualities, formats, new HBox(12, showAll, audioRow), new Separator(), save);
        VBox.setVgrow(qualities, Priority.ALWAYS); VBox.setVgrow(formats, Priority.ALWAYS);
        content.setPrefWidth(720);
        dialog.initOwner(window.stage()); dialog.setTitle("Download video"); dialog.setResizable(false);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setId("quality-dialog");
        dialog.getDialogPane().getButtonTypes().setAll(later, start, ButtonType.CANCEL);
        var startButton = (Button) dialog.getDialogPane().lookupButton(start); startButton.setId("quality-start"); startButton.getStyleClass().add("primary-button");
        var laterButton = (Button) dialog.getDialogPane().lookupButton(later); laterButton.setId("quality-later");
        startButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { if (!submit(true)) event.consume(); });
        laterButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { if (!submit(false)) event.consume(); });
        qualities.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshButtons());
        formats.getSelectionModel().selectedItemProperty().addListener((o, a, chosen) -> {
            audioTrack.setValue(chosen != null && AddFormats.kind(chosen).equals("VIDEO_ONLY") ? MediaChoices.bestAudio(formats.getItems(), chosen) : null);
            refreshButtons();
        });
        url.textProperty().addListener((o, a, b) -> { if (!b.equals(analysed)) { qualities.getItems().clear(); formats.getItems().clear(); refreshButtons(); } });
        MainWindow.style(dialog);
        title.setText(link == null ? "Paste a video page address and click Get qualities." : "Video");
        refreshButtons();
    }
    private void buildFormatsTable() {
        formats.setId("formats-table"); formats.setPrefHeight(260);
        formats.getColumns().setAll(List.of(column("Quality", MediaChoices::quality, 240),
                column("Sound", value -> switch (AddFormats.kind(value)) {
                    case "VIDEO_ONLY" -> MediaChoices.bestAudio(formats.getItems(), value) == null ? "No separate sound" : "Sound added";
                    case "AUDIO_ONLY" -> MediaChoices.audioLabel(value);
                    case "VIDEO_AUDIO" -> "Included"; default -> "Unknown";
                }, 180),
                column("Format", value -> AddFormats.text(value, "container").toUpperCase(Locale.ROOT) + " · " + MediaChoices.encoding(value), 130),
                column("Size", MediaChoices::size, 110)));
        formats.setPlaceholder(new Label("Formats appear here."));
        audioTrack.setPromptText("No separate sound"); audioTrack.setPrefWidth(300); audioTrack.setId("audio-track");
        audioTrack.setCellFactory(list -> audioCell()); audioTrack.setButtonCell(audioCell());
    }
    private static ListCell<JsonObject> audioCell() {
        return new ListCell<>() { @Override protected void updateItem(JsonObject row, boolean empty) { super.updateItem(row, empty); setText(empty || row == null ? null : MediaChoices.audioLabel(row)); } };
    }
    private static TableColumn<JsonObject, String> column(String name, Function<JsonObject, String> text, double width) {
        var column = new TableColumn<JsonObject, String>(name); column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue()))); return column;
    }
    private void load() {
        String source = url.getText().strip();
        try { FormRequests.base(source, folder.getText(), 1); }
        catch (IllegalArgumentException invalid) { status.setText("Enter the video page's address (http:// or https://)."); return; }
        var payload = new JsonObject(); payload.addProperty("url", source); payload.addProperty("showAll", true);
        spinner.setVisible(true); loading.set(true);
        status.setText("Getting the list of qualities…"); title.setText(title.getText().isBlank() ? "Video" : title.getText());
        long started = System.nanoTime();
        window.model().request("formats", payload, result -> {
            spinner.setVisible(false); loading.set(false);
            if (!source.equals(url.getText().strip())) return;
            analysed = source;
            title.setText(AddFormats.text(result, "title"));
            var all = new ArrayList<JsonObject>();
            for (var item : result.getAsJsonArray("formats")) all.add(item.getAsJsonObject());
            var menu = io.ajdm.ipc.QualityMenu.of(result.getAsJsonArray("formats"));
            var entries = new ArrayList<JsonObject>(); for (var element : menu) entries.add(element.getAsJsonObject());
            qualities.getItems().setAll(entries);
            var shown = new ArrayList<>(MediaChoices.withoutDuplicateStreams(all.stream().filter(MediaChoices::available).toList()));
            shown.sort(Comparator.comparingDouble((JsonObject item) -> MediaChoices.number(item, "height")).reversed()
                    .thenComparing(Comparator.comparingDouble((JsonObject item) -> MediaChoices.number(item, "audioBitrateKbps")).reversed()));
            formats.setItems(FXCollections.observableArrayList(shown));
            audioTrack.getItems().setAll(shown.stream().filter(item -> AddFormats.kind(item).equals("AUDIO_ONLY")).toList());
            select();
            double seconds = (System.nanoTime() - started) / 1e9;
            status.setText(entries.size() + " qualities" + (seconds < 1 ? "" : String.format(Locale.ROOT, " · found in %.0f s", seconds))
                    + ". Choose one and click Start Download.");
            refreshButtons();
        }, error -> {
            spinner.setVisible(false); loading.set(false);
            status.setText(error);
            if (error.startsWith(io.ajdm.model.ErrorCode.PLAYLIST_REQUIRED.publicCode() + ":") && content.lookup("#open-playlist-picker") == null) {
                var open = new Button("Choose videos from this playlist…"); open.setId("open-playlist-picker");
                open.setOnAction(event -> {
                    var picker = new PlaylistDialog(window.stage(), window.model(), Path.of(folder.getText()), connections.getValue());
                    picker.source(url.getText()); dialog.close(); picker.show();
                });
                content.getChildren().add(2, open);
            }
        });
    }
    /** The quality chosen in the browser, else the best (first) entry. */
    private void select() {
        if (wantedFormat != null) {
            for (var entry : qualities.getItems()) {
                if (AddFormats.text(entry, "formatId").equals(wantedFormat) && AddFormats.text(entry, "audioId").equals(wantedAudio == null ? "" : wantedAudio)) {
                    qualities.getSelectionModel().select(entry); qualities.scrollTo(entry); return;
                }
            }
        }
        if (!qualities.getItems().isEmpty()) qualities.getSelectionModel().selectFirst();
    }
    private void refreshButtons() {
        boolean ready = !analysed.isEmpty() && analysed.equals(url.getText().strip()) && choice() != null;
        dialog.getDialogPane().lookupButton(start).setDisable(!ready);
        dialog.getDialogPane().lookupButton(later).setDisable(!ready);
    }
    /** {formatId, audioId or null} for the current choice. */
    private String[] choice() {
        if (showAll.isSelected()) {
            var chosen = formats.getSelectionModel().getSelectedItem(); if (chosen == null) return null;
            var sound = AddFormats.kind(chosen).equals("VIDEO_ONLY") ? audioTrack.getValue() : null;
            return new String[]{AddFormats.text(chosen, "formatId"), sound == null ? null : AddFormats.text(sound, "formatId")};
        }
        var entry = qualities.getSelectionModel().getSelectedItem(); if (entry == null) return null;
        return new String[]{AddFormats.text(entry, "formatId"), entry.has("audioId") ? AddFormats.text(entry, "audioId") : null};
    }
    private boolean submit(boolean startNow) {
        var chosen = choice(); if (chosen == null) return false;
        JsonObject payload;
        try {
            payload = FormRequests.base(url.getText(), folder.getText(), connections.getValue());
            FormRequests.filename(payload, filename.getText());
            FormRequests.selection(payload, "Custom", chosen[0], chosen[1] == null ? "" : chosen[1]);
        } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); return false; }
        if (!startNow) payload.addProperty("start", false);
        String name = title.getText();
        window.model().request("addMedia", payload, result -> {
            var id = java.util.UUID.fromString(result.get("downloadId").getAsString());
            if (startNow) window.started(id); else { window.model().refresh(); window.tell("\"" + name + "\" added. Start it with Resume or Start Queue."); }
        }, failure -> {
            var alert = new Alert(Alert.AlertType.ERROR, failure, ButtonType.OK); alert.initOwner(window.stage());
            alert.setTitle("Video not added"); alert.setHeaderText("AJDM could not add \"" + name + "\""); MainWindow.style(alert); alert.show();
        });
        return true;
    }
    void show() {
        dialog.show();
        if (!url.getText().isBlank()) load(); else url.requestFocus();
    }
    /** Small JSON readers shared with the format table. */
    static final class AddFormats {
        private AddFormats() { }
        static String text(JsonObject row, String key) { return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : ""; }
        static String kind(JsonObject row) { return text(row, "kind"); }
        static JsonArray array(JsonObject row, String key) { return row.has(key) && row.get(key).isJsonArray() ? row.getAsJsonArray(key) : new JsonArray(); }
    }
}
