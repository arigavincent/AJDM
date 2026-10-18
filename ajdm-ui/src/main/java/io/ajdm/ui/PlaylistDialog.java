package io.ajdm.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class PlaylistDialog {
    void source(String value) { url.setText(value); }
    private static final List<String> PRESETS = List.of("BEST", "P1080", "P720", "AUDIO");
    private static final class Entry {
        final JsonObject metadata;
        final BooleanProperty selected = new SimpleBooleanProperty(false);
        String override = "Default";
        Entry(JsonObject metadata) { this.metadata = metadata; }
        int index() { return metadata.get("index").getAsInt(); }
    }
    private final Dialog<Void> dialog = new Dialog<>();
    private final DownloadsViewModel model;
    private final TextField url = new TextField(), folder, range = new TextField();
    private final Spinner<Integer> workers;
    private final ComboBox<String> preset = new ComboBox<>(FXCollections.observableArrayList(PRESETS));
    private final TableView<Entry> table = new TableView<>();
    private final Label status = new Label("Discover a playlist to choose its entries.");
    private final VBox content = new VBox(12);
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private int offset, total;
    private String source = "";
    private boolean busy;
    PlaylistDialog(Stage owner, DownloadsViewModel model, Path directory, int connections) {
        this.model = model; folder = new TextField(directory.toString()); workers = new Spinner<>(1, 32, connections);
        dialog.initOwner(owner); dialog.setTitle("Playlist picker");
        url.setId("playlist-url"); table.setId("playlist-table");
        url.setPromptText("Playlist page URL"); url.setAccessibleText("Playlist URL");
        range.setPromptText("Optional range, for example 1-5,8,12 or all"); preset.setValue("P1080");
        var discover = new Button("Discover playlist"); discover.setOnAction(event -> { offset = 0; entries.clear(); load(); });
        discover.setId("discover-playlist");
        var previous = new Button("Previous 100"); previous.setOnAction(event -> { offset = Math.max(0, offset - 100); load(); });
        var next = new Button("Next 100"); next.setOnAction(event -> { if (offset + 100 < total) { offset += 100; load(); } });
        var select = new Button("Select page"); select.setOnAction(event -> table.getItems().forEach(entry -> entry.selected.set(true)));
        var clear = new Button("Clear selection"); clear.setOnAction(event -> entries.values().forEach(entry -> entry.selected.set(false)));
        var add = new Button("Download selected"); add.getStyleClass().add("primary-button"); add.setOnAction(event -> add());
        configureTable(); table.setPrefHeight(340); table.setAccessibleText("Playlist entries with selection and per-entry quality");
        url.textProperty().addListener((o, a, b) -> { source = ""; entries.clear(); table.getItems().clear(); });
        content.getChildren().addAll(new Label("Playlist URL"), url, new HBox(8, discover, MainWindow.spacer(), previous, next),
                table, new HBox(8, select, clear), new Label("Choose checked entries, or enter a range"), range,
                new HBox(10, new Label("Default quality"), preset, new Label("Connections per transfer"), workers),
                new Label("Save to"), folder, new HBox(8, MainWindow.spacer(), add), status);
        status.setWrapText(true); status.getStyleClass().add("notice"); dialog.getDialogPane().setPrefWidth(880);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); MainWindow.style(dialog);
        dialog.setOnCloseRequest(event -> { if (busy) event.consume(); });
    }
    private void configureTable() {
        table.setEditable(true); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var selected = new TableColumn<Entry, Boolean>("Select"); selected.setPrefWidth(64); selected.setEditable(true);
        selected.setCellValueFactory(cell -> cell.getValue().selected); selected.setCellFactory(CheckBoxTableCell.forTableColumn(selected));
        var index = column("#", entry -> Integer.toString(entry.index()), 50);
        var title = column("Video", entry -> QualityDialog.AddFormats.text(entry.metadata, "title"), 380);
        var availability = column("Availability", entry -> entry.metadata.has("error") ? "Unavailable · skipped" : "Available", 145);
        var override = new TableColumn<Entry, Entry>("Quality override"); override.setPrefWidth(140);
        override.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        override.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty); setGraphic(null);
                if (!empty && entry != null) {
                    var choices = new ComboBox<String>(); choices.getItems().add("Default"); choices.getItems().addAll(PRESETS);
                    choices.setValue(entry.override); choices.setAccessibleText("Quality for playlist entry " + entry.index());
                    choices.setOnAction(event -> entry.override = choices.getValue()); setGraphic(choices);
                }
            }
        });
        table.getColumns().setAll(List.of(selected, index, title, availability, override));
    }
    private static TableColumn<Entry, String> column(String title, Function<Entry, String> text, double width) {
        var column = new TableColumn<Entry, String>(title); column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue()))); return column;
    }
    private void load() {
        if (busy) return;
        try {
            var validated = FormRequests.base(url.getText(), folder.getText(), workers.getValue());
            var request = new JsonObject(); request.add("url", validated.get("url")); request.addProperty("offset", offset); request.addProperty("limit", 100);
            String current = url.getText(); setBusy(true, "Extracting playlist metadata…");
            model.request("playlist", request, result -> {
                source = current; total = result.get("totalEntries").getAsInt(); table.getItems().clear();
                for (var item : result.getAsJsonArray("entries")) {
                    var metadata = item.getAsJsonObject(); int index = metadata.get("index").getAsInt();
                    table.getItems().add(entries.computeIfAbsent(index, ignored -> new Entry(metadata)));
                }
                setBusy(false, QualityDialog.AddFormats.text(result, "title") + " · " + total + " entries · Showing " + (offset + 1) + "–" + Math.min(total, offset + 100));
            }, error -> setBusy(false, error));
        } catch (IllegalArgumentException invalid) { status.setText("Check the playlist URL and download folder."); }
    }
    private void add() {
        if (busy) return;
        if (!source.equals(url.getText()) || source.isBlank()) { status.setText("Discover the playlist first."); return; }
        try {
            var request = FormRequests.base(url.getText(), folder.getText(), workers.getValue());
            String selected = range.getText().strip();
            if (selected.isEmpty()) selected = entries.values().stream().filter(entry -> entry.selected.get()).map(entry -> Integer.toString(entry.index())).collect(java.util.stream.Collectors.joining(","));
            if (selected.isEmpty()) { status.setText("Check at least one entry or enter a range."); return; }
            var included = io.ajdm.media.PlaylistSelection.indices(selected, total);
            var overrides = new JsonArray();
            for (var entry : entries.values()) if (included.contains(entry.index()) && !entry.override.equals("Default")) {
                var choice = new JsonObject(); choice.addProperty("index", entry.index()); choice.addProperty("preset", entry.override); overrides.add(choice);
            }
            request.addProperty("items", selected); request.addProperty("preset", preset.getValue()); request.add("choices", overrides);
            setBusy(true, "Creating playlist download…");
            model.request("addPlaylist", request, result -> { setBusy(false, "Playlist added."); dialog.close(); }, error -> setBusy(false, error));
        } catch (IllegalArgumentException invalid) { status.setText("Use a valid range, such as 1-5,8,12 or all, within this playlist."); }
    }
    private void setBusy(boolean value, String text) { busy = value; content.setDisable(value); status.setText(text); }
    void show() { dialog.show(); }
}
