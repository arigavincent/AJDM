package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

final class AddDownloadDialog {
    private final Dialog<Void> dialog = new Dialog<>();
    private final DownloadsViewModel model;
    private final TextField url = field("Paste a direct file URL or a video page URL");
    private final TextField folder;
    private final TextField filename = field("Automatic filename");
    private final TextField checksum = field("Optional SHA-256");
    private final TextField referer = field("Page that started the download (optional)"), userAgent = field("Browser User-Agent (optional)");
    private final PasswordField cookies = new PasswordField(), authorization = new PasswordField();
    private final TextField format = field("Format ID"), audio = field("Audio ID (optional)");
    private final Spinner<Integer> connections;
    private final ComboBox<String> preset = new ComboBox<>();
    private final ComboBox<JsonObject> audioTrack = new ComboBox<>();
    private final CheckBox includeSound = new CheckBox("Include sound");
    private final Label selectedQuality = new Label("Choose a quality from the list.");
    private final CheckBox modern = new CheckBox("Prefer modern codecs"), adaptive = new CheckBox("Adaptive connections (experimental)");
    private final Label message = new Label();
    private final Label inventory = new Label("Analyse the URL to inspect every available quality before downloading.");
    private String inventorySummary = inventory.getText();
    private final TableView<JsonObject> formats = new TableView<>();
    private final VBox content = new VBox(12);
    private final Button download = new Button("Start download"), discover = new Button("Analyse qualities"), preview = new Button("Preview selection");
    private final boolean media;
    private boolean busy;
    private String discoveredUrl = "";

    AddDownloadDialog(Stage owner, DownloadsViewModel model, Path directory, int workers, boolean media) {
        this.model = model; this.media = media; folder = new TextField(directory.toString()); connections = new Spinner<>(1, 32, workers);
        url.setId("source-url"); folder.setId("destination-folder"); filename.setId("download-filename");
        download.setId("start-download"); discover.setId("discover-formats"); preset.setId("quality-preset"); formats.setId("formats-table");
        preview.setId("preview-selection");
        inventory.setId("format-inventory");
        format.setId("custom-format"); audio.setId("custom-audio");
        connections.setId("download-connections");
        adaptive.setId("adaptive-connections");
        adaptive.setTooltip(new Tooltip("Try more connections and keep them only when useful throughput improves. Uncheck for fixed workers."));
        connections.setPrefWidth(86); dialog.initOwner(owner); dialog.setTitle(media ? "Media picker" : "Add download");
        var paste = new Button("Paste"); paste.setId("paste-url"); paste.setOnAction(event -> { if (Clipboard.getSystemClipboard().hasString()) url.setText(Clipboard.getSystemClipboard().getString()); });
        var sourceRow = new HBox(8, url, paste);
        if (!media) {
            var analyse = new Button("Analyse qualities"); analyse.setId("analyse-qualities");
            analyse.disableProperty().bind(url.textProperty().isEmpty());
            analyse.setOnAction(event -> openMediaPicker(owner)); sourceRow.getChildren().add(analyse);
        }
        HBox.setHgrow(url, Priority.ALWAYS); content.getChildren().addAll(new Label(media ? "Video page URL" : "File or video page URL"), sourceRow);
        if (!media) {
            var help = new Label("For YouTube, TikTok, Facebook, Instagram or X pages, analyse qualities first. Use Start download for a direct ISO, movie or other file URL.");
            help.setWrapText(true); help.setMaxWidth(600); content.getChildren().add(help);
            var playlist = new Button("Open playlist picker"); playlist.setId("open-playlist-picker");
            playlist.setOnAction(event -> {
                var picker = new PlaylistDialog(owner, model, Path.of(folder.getText()), connections.getValue());
                picker.source(url.getText()); dialog.close(); picker.show();
            }); content.getChildren().add(playlist);
        }
        var browse = new Button("Choose folder"); browse.setOnAction(event -> {
            var chooser = new DirectoryChooser(); chooser.setTitle("Download folder");
            try { var path = Path.of(folder.getText()); if (Files.isDirectory(path)) chooser.setInitialDirectory(path.toFile()); } catch (java.nio.file.InvalidPathException ignored) { }
            var selected = chooser.showDialog(owner); if (selected != null) folder.setText(selected.toString());
        });
        HBox.setHgrow(folder, Priority.ALWAYS);
        if (media) buildMedia();
        content.getChildren().addAll(new Label("Save to"), new HBox(8, folder, browse), new Label("Filename"), filename);
        if (!media) {
            content.getChildren().addAll(new Label("Expected checksum"), checksum);
            referer.setId("request-referer"); userAgent.setId("request-user-agent"); cookies.setId("request-cookies"); authorization.setId("request-authorization");
            cookies.setPromptText("Cookie header, for example session=value"); authorization.setPromptText("Authorization header, if required");
            var requestFields = new TitledPane("Browser request details (optional)", new VBox(7,
                    new Label("Referrer"), referer, new Label("User-Agent"), userAgent,
                    new Label("Cookie"), cookies, new Label("Authorization"), authorization,
                    new Label("Use the direct URL from Chrome's download. Credentials are used only for this transfer; enter them again after reopening AJDM.")));
            requestFields.setExpanded(false); content.getChildren().add(requestFields);
        } else {
            var access=new Label("For sign-in-only media, choose your signed-in browser or an exported cookie file in Settings > Media site access. Public TikTok links also use TikWM/Douyin metadata resolvers; session cookies are never sent to them.");
            access.setWrapText(true); access.setMaxWidth(Double.MAX_VALUE); access.setMinHeight(Region.USE_PREF_SIZE); content.getChildren().add(access);
        }
        content.getChildren().add(adaptive);
        var workersRow = new HBox(10, new Label("Connections"), connections, MainWindow.spacer(), download);
        content.getChildren().addAll(workersRow, new Label("Existing filenames are renamed automatically."), message);
        message.setWrapText(true); message.getStyleClass().add("notice");
        download.getStyleClass().add("primary-button"); download.setOnAction(event -> add());
        // Scrolls instead of growing past the screen; the window is resizable and the formats table takes the extra room.
        var scroller = new ScrollPane(content); scroller.setFitToWidth(true); scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroller.getStyleClass().add("edge-to-edge");
        dialog.getDialogPane().setContent(scroller); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        MainWindow.style(dialog); dialog.getDialogPane().setPrefWidth(media ? 950 : 660); dialog.setResizable(true);
        dialog.setOnCloseRequest(event -> { if (busy) event.consume(); });
        dialog.setOnShown(event -> { fitToScreen(); url.requestFocus(); });
    }
    /** Size the window to its content, but never taller or wider than the visible screen, and keep it on screen. */
    private void fitToScreen() {
        var stage = (Stage) dialog.getDialogPane().getScene().getWindow();
        stage.sizeToScene();
        var screens = javafx.stage.Screen.getScreensForRectangle(stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()));
        var bounds = (screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        stage.setHeight(Math.min(stage.getHeight(), bounds.getHeight() - 16)); stage.setWidth(Math.min(stage.getWidth(), bounds.getWidth() - 16));
        stage.setY(Math.max(bounds.getMinY() + 8, Math.min(stage.getY(), bounds.getMaxY() - stage.getHeight() - 8)));
        stage.setX(Math.max(bounds.getMinX() + 8, Math.min(stage.getX(), bounds.getMaxX() - stage.getWidth() - 8)));
    }
    private void openMediaPicker(Stage owner) {
        if (busy) return;
        try {
            var request = FormRequests.base(url.getText(), folder.getText(), connections.getValue());
            var picker = new AddDownloadDialog(owner, model, Path.of(request.get("directory").getAsString()), connections.getValue(), true);
            picker.url.setText(url.getText()); picker.filename.setText(filename.getText());
            picker.adaptive.setSelected(adaptive.isSelected());
            dialog.close(); picker.show(); picker.inspect(false);
        } catch (IllegalArgumentException invalid) { message.setText("Enter a valid video page URL and download folder before analysing qualities."); }
    }
    private static TextField field(String prompt) { var field = new TextField(); field.setPromptText(prompt); field.setAccessibleText(prompt); return field; }
    private void buildMedia() {
        preset.getItems().setAll("Custom", "BEST", "P1080", "P720", "AUDIO"); preset.setValue("Custom");
        preset.setConverter(new StringConverter<>() {
            @Override public String toString(String value) { return presetLabel(value); }
            @Override public String fromString(String value) { return value; }
        });
        preset.setAccessibleText("Optional automatic quality choice"); modern.setSelected(true);
        format.setEditable(false); audio.setEditable(false);
        download.setDisable(true); preview.setDisable(true);
        preset.valueProperty().addListener((o, a, b) -> refreshMediaSelection());
        discover.setOnAction(event -> inspect(false)); preview.setOnAction(event -> inspect(true));
        var row = new HBox(10, discover, MainWindow.spacer(), preview);
        formats.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        formats.getColumns().setAll(List.of(column("Quality", MediaChoices::quality, 250),
                column("Sound", value -> switch (string(value, "kind")) {
                    case "VIDEO_ONLY" -> MediaChoices.bestAudio(formats.getItems(), value) == null ? "No separate audio available" : "Sound added automatically";
                    case "AUDIO_ONLY" -> MediaChoices.audioLabel(value);
                    case "VIDEO_AUDIO" -> "Sound included"; default -> "Unknown";
                }, 220),
                column("Format", value -> string(value, "container").toUpperCase(Locale.ROOT) + " · " + MediaChoices.encoding(value), 145),
                column("Stream size", MediaChoices::size, 130),
                column("Availability", value -> MediaChoices.available(value) ? "Available" : "Not supported yet", 145)));
        formats.setPrefHeight(330); formats.setMinHeight(180); VBox.setVgrow(formats, Priority.ALWAYS);
        formats.setPlaceholder(new Label("Paste a video URL, then click Analyse qualities."));
        formats.setAccessibleText("All extracted media formats and qualities");
        formats.getSelectionModel().selectedItemProperty().addListener((o, a, chosen) -> {
            if (chosen == null) return;
            preset.setValue("Custom"); format.setText(string(chosen, "formatId"));
            includeSound.setSelected(true);
            audioTrack.setValue(string(chosen, "kind").equals("VIDEO_ONLY") ? MediaChoices.bestAudio(formats.getItems(), chosen) : null);
            refreshMediaSelection();
        });
        audioTrack.setId("audio-track"); includeSound.setId("include-sound"); selectedQuality.setId("selected-quality");
        includeSound.setSelected(true); audioTrack.setPromptText("No separate audio available");
        audioTrack.setPrefWidth(330); audioTrack.setAccessibleText("Sound track for the chosen video");
        audioTrack.setCellFactory(list -> audioCell()); audioTrack.setButtonCell(audioCell());
        audioTrack.valueProperty().addListener((o, a, b) -> refreshMediaSelection());
        includeSound.selectedProperty().addListener((o, a, b) -> refreshMediaSelection());
        url.textProperty().addListener((o, a, b) -> {
            discoveredUrl = ""; formats.getItems().clear(); audioTrack.getItems().clear(); audioTrack.setValue(null);
            inventorySummary = "Analyse qualities for this URL before downloading.";
            format.clear(); audio.clear(); preset.setValue("Custom"); refreshMediaSelection();
        });
        inventory.setWrapText(true); inventory.setMaxWidth(Double.MAX_VALUE); inventory.setMinHeight(Region.USE_PREF_SIZE); inventory.getStyleClass().add("muted");
        selectedQuality.setWrapText(true); selectedQuality.setMaxWidth(Double.MAX_VALUE); selectedQuality.setMinHeight(Region.USE_PREF_SIZE);
        var advanced = new TitledPane("Advanced format details", new VBox(8,
                new HBox(8, new Label("Automatic choice"), preset, modern), new HBox(8, new Label("Stream IDs"), format, audio)));
        advanced.setId("advanced-formats"); advanced.setExpanded(false);
        advanced.expandedProperty().addListener((o, a, b) -> javafx.application.Platform.runLater(this::fitToScreen));
        content.getChildren().addAll(row, inventory, new Label("Choose the quality you want. Every available format is listed."), formats,
                new HBox(10, includeSound, audioTrack), selectedQuality, advanced);
        refreshMediaSelection();
    }
    private static ListCell<JsonObject> audioCell() {
        return new ListCell<>() {
            @Override protected void updateItem(JsonObject row, boolean empty) { super.updateItem(row, empty); setText(empty || row == null ? null : MediaChoices.audioLabel(row)); }
        };
    }
    private static String presetLabel(String value) {
        return switch (value == null ? "Custom" : value) {
            case "BEST" -> "Best up to 4K"; case "P1080" -> "Up to 1080p (Full HD)"; case "P720" -> "Up to 720p (HD)";
            case "AUDIO" -> "Best audio only"; default -> "Selected quality";
        };
    }
    private void refreshMediaSelection() {
        inventory.setText(inventorySummary);
        boolean analysed = !discoveredUrl.isEmpty() && discoveredUrl.equals(url.getText());
        var chosen = formats.getItems().stream().filter(item -> string(item, "formatId").equals(format.getText())).findFirst().orElse(null);
        boolean videoOnly = chosen != null && string(chosen, "kind").equals("VIDEO_ONLY") && preset.getValue().equals("Custom");
        includeSound.setDisable(!videoOnly || audioTrack.getItems().isEmpty());
        audioTrack.setDisable(includeSound.isDisabled() || !includeSound.isSelected());
        if (videoOnly && includeSound.isSelected() && audioTrack.getValue() == null && !audioTrack.getItems().isEmpty())
            audioTrack.setValue(MediaChoices.bestAudio(audioTrack.getItems(), chosen));
        var sound = videoOnly && includeSound.isSelected() ? audioTrack.getValue() : null;
        audio.setText(sound == null ? "" : string(sound, "formatId"));
        boolean ready = analysed && (!preset.getValue().equals("Custom") || chosen != null && MediaChoices.available(chosen));
        download.setDisable(!ready); preview.setDisable(!ready);
        if (!analysed || chosen == null && preset.getValue().equals("Custom")) selectedQuality.setText("Choose a quality from the list.");
        else if (!preset.getValue().equals("Custom")) selectedQuality.setText("Automatic choice: " + presetLabel(preset.getValue()) + ". Preview selection to inspect the result.");
        else if (!MediaChoices.available(chosen)) selectedQuality.setText(MediaChoices.quality(chosen) + " is listed, but this stream type is not supported yet.");
        else selectedQuality.setText("Selected: " + MediaChoices.quality(chosen) + "\n" +
                (videoOnly ? sound == null ? "Video only, without sound." : "With sound: " + MediaChoices.audioLabel(sound)
                        : string(chosen, "kind").equals("AUDIO_ONLY") ? "Audio download, without video."
                        : string(chosen, "kind").equals("UNKNOWN") ? "Download the original media file. Video and audio metadata are unknown." : "Video with sound included.") + "\n" + combinedSize(chosen, sound));
    }
    private static String combinedSize(JsonObject video, JsonObject sound) {
        if (sound == null) return MediaChoices.size(video);
        long first = video.get("sizeBytes").getAsLong(), second = sound.get("sizeBytes").getAsLong();
        if (first < 0 || second < 0) return "Unknown size";
        try { return (MediaChoices.flag(video, "approximateSize") || MediaChoices.flag(sound, "approximateSize") ? "About " : "") + Display.mib(Math.addExact(first, second)); }
        catch (ArithmeticException overflow) { return "Unknown size"; }
    }
    private void inspect(boolean selection) {
        if (busy) return;
        try {
            var base = FormRequests.base(url.getText(), folder.getText(), connections.getValue());
            var payload = new JsonObject(); payload.add("url", base.get("url")); payload.addProperty("showAll", true);
            payload.addProperty("preferModernCodecs", modern.isSelected());
            if (selection) FormRequests.selection(payload, preset.getValue(), format.getText(), audio.getText());
            String source = url.getText(); setBusy(true, "Extracting metadata…");
            model.request("formats", payload, result -> {
                setBusy(false, "Qualities ready. Choose a row, check your selection, then start the download.");
                String selectedId = format.getText();
                String selectedAudioId = audio.getText(); boolean soundIncluded = includeSound.isSelected();
                var extracted = new ArrayList<JsonObject>();
                for (var item : result.getAsJsonArray("formats")) extracted.add(item.getAsJsonObject());
                var shown = MediaChoices.withoutDuplicateStreams(extracted);
                int duplicates = extracted.size() - shown.size();
                formats.setItems(FXCollections.observableArrayList(shown));
                formats.getItems().sort(Comparator.comparingDouble((JsonObject item) -> MediaChoices.number(item, "height")).reversed()
                        .thenComparing(Comparator.comparingDouble((JsonObject item) -> MediaChoices.number(item, "fps")).reversed())
                        .thenComparing(Comparator.comparingDouble((JsonObject item) -> MediaChoices.number(item, "audioBitrateKbps")).reversed()));
                audioTrack.getItems().setAll(formats.getItems().stream().filter(item -> string(item, "kind").equals("AUDIO_ONLY") && !MediaChoices.flag(item, "fragmented")).toList());
                discoveredUrl = source;
                inventorySummary = string(result, "title") + "\n" + formats.getItems().size() + " formats"
                        + (duplicates > 0 ? " · " + duplicates + " duplicate streams hidden" : "")
                        + " · " + result.get("excludedCount").getAsInt() + " excluded (unsupported or protected)";
                if (selection && preset.getValue().equals("Custom")) {
                    formats.getItems().stream().filter(item -> string(item, "formatId").equals(selectedId)).findFirst().ifPresent(item -> formats.getSelectionModel().select(item));
                    includeSound.setSelected(soundIncluded);
                    audioTrack.setValue(audioTrack.getItems().stream().filter(item -> string(item, "formatId").equals(selectedAudioId)).findFirst().orElse(null));
                }
                else if (!selection) { format.clear(); audio.clear(); preset.setValue("Custom"); audioTrack.setValue(null); }
                refreshMediaSelection();
                String chosen = result.has("selection") ? "\nDownload file: " + string(result.getAsJsonObject("selection"), "container").toUpperCase(Locale.ROOT) + " · " + MediaChoices.size(result.getAsJsonObject("selection")) : "";
                inventory.setText(inventorySummary + chosen);
                fitToScreen();
            }, error -> {
                discoveredUrl = ""; formats.getItems().clear(); audioTrack.getItems().clear(); audioTrack.setValue(null);
                inventorySummary = "Analysis failed. " + error;
                formats.setPlaceholder(new Label("No qualities returned. Check the analysis error below."));
                refreshMediaSelection();
                setBusy(false, error);
                if (error.startsWith(io.ajdm.model.ErrorCode.PLAYLIST_REQUIRED.publicCode() + ":")) {
                    var open = new Button("Choose videos in playlist picker"); open.setId("open-playlist-picker");
                    open.setOnAction(event -> {
                        var picker = new PlaylistDialog((Stage) dialog.getOwner(), model, Path.of(folder.getText()), connections.getValue());
                        picker.source(url.getText()); dialog.close(); picker.show();
                    });
                    if (content.lookup("#open-playlist-picker") == null) content.getChildren().add(open);
                }
            });
        } catch (IllegalArgumentException invalid) { message.setText("Check the URL, download folder and selected formats."); }
    }
    private void add() {
        if (busy) return;
        try {
            var payload = FormRequests.base(url.getText(), folder.getText(), connections.getValue()); FormRequests.filename(payload, filename.getText());
            if (media) {
                if (!discoveredUrl.equals(url.getText())) { message.setText("Analyse qualities for this URL first."); return; }
                FormRequests.selection(payload, preset.getValue(), format.getText(), audio.getText()); payload.addProperty("preferModernCodecs", modern.isSelected());
            } else {
                FormRequests.checksum(payload, checksum.getText());
                FormRequests.requestDetails(payload, referer.getText(), userAgent.getText(), cookies.getText(), authorization.getText());
            }
            if (adaptive.isSelected()) payload.addProperty("adaptive", true);
            setBusy(true, "Starting download…");
            model.request(media ? "addMedia" : "add", payload, result -> { setBusy(false, "Download added."); dialog.close(); }, error -> setBusy(false, error));
        } catch (IllegalArgumentException invalid) { message.setText("Check the URL, filename, folder, checksum and format selection."); }
    }
    private void setBusy(boolean value, String text) { busy = value; content.setDisable(value); message.setText(text); }
    static String string(JsonObject row, String field) { return row.has(field) && !row.get(field).isJsonNull() ? row.get(field).getAsString() : ""; }
    static int number(JsonObject row, String field) { return row.has(field) && !row.get(field).isJsonNull() ? row.get(field).getAsInt() : 0; }
    private static TableColumn<JsonObject, String> column(String name, Function<JsonObject, String> text, double width) {
        var column = new TableColumn<JsonObject, String>(name); column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue()))); return column;
    }
    void show() { dialog.show(); }
}
