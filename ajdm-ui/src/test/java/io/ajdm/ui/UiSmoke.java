package io.ajdm.ui;

import com.google.gson.JsonObject;
import io.ajdm.bench.FaultyRangeServer;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.IpcServer;
import io.ajdm.media.*;
import io.ajdm.model.DownloadSource;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Explicit graphical check, separate from display-independent check. Uses only disposable fixture data. */
public final class UiSmoke {
    private static volatile Throwable failure;
    private UiSmoke() { }
    public static void main(String[] args) {
        Application.launch(SmokeApp.class, args);
        if (failure != null) throw new AssertionError("JavaFX interaction check failed", failure);
    }
    public static final class SmokeApp extends Application {
        private Path temp;
        private FaultyRangeServer source;
        private CoreService core;
        private IpcServer server;
        private DownloadsViewModel model;
        private MainWindow window;
        private Stage stage;
        private TableView<DownloadRow> table;
        private final java.util.concurrent.atomic.AtomicReference<String> mediaSession = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean blockMedia = new java.util.concurrent.atomic.AtomicBoolean();
        @Override public void init() throws Exception {
            temp = Files.createTempDirectory("ajdm-ui-smoke-");
            source = new FaultyRangeServer(8 * 1024 * 1024 + 17, FaultyRangeServer.Mode.CAPPED, 128 * 1024);
            var extractor = new MediaExtractor() {
                @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws io.ajdm.model.DownloadException {
                    if (blockMedia.get() || page.getPath().contains("access-blocked")) throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.MEDIA_ACCESS_BLOCKED, "secret diagnostics");
                    return new MediaInfo("fixture", "Local metadata fixture", "AJDM test", 30.0, page, List.of(
                            format("v1080", "avc1", "none", 1080), format("v4320", "vp9", "none", 4320),
                            format("a128", "none", "mp4a", null), format("a96", "none", "opus", null),
                            format("blocked", "vp9", "none", 1440), format("combined", "avc1", "mp4a", 720)), 0);
                }
                @Override public void configureBrowser(MediaBrowser browser) { mediaSession.set(browser.toolName()); }
                @Override public void configureCookies(Path file) { mediaSession.set(file == null ? null : "file"); }
                @Override public PlaylistInfo playlist(URI page, BooleanSupplier canceled) {
                    return new PlaylistInfo("fixture", "Local playlist fixture", page, List.of(new PlaylistInfo.Entry(1, "one", "Local video", 30.0, page,
                            page.getPath().contains("unavailable") ? io.ajdm.model.ErrorCode.AUTH_REQUIRED : null)));
                }
                private MediaFormat format(String id, String video, String audio, Integer height) {
                    return new MediaFormat(id, "mp4", video, audio, height == null ? null : height * 16 / 9, height, height == null ? null : 30.0,
                            1000.0, id.equals("a96") ? 96.0 : 128.0, 1024L, null, id.equals("v4320"), id.equals("blocked"), "", "en", new DownloadSource(URI.create("https://example.test/payload"), Map.of()));
                }
            };
            core = new CoreService(temp.resolve("state/ajdm.db"), extractor);
            server = new IpcServer(temp.resolve("ipc/socket"), core);
        }
        @Override public void start(Stage stage) throws Exception {
            this.stage = stage; model = new DownloadsViewModel(temp.resolve("ipc/socket"));
            window = new MainWindow(stage, model, temp.resolve("ipc/socket"), temp.resolve("downloads"));
            var loader = new FXMLLoader(AjdmApp.class.getResource("main.fxml")); loader.setControllerFactory(type -> window);
            var scene = new Scene(loader.load(), 1240, 880); scene.getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
            stage.setScene(scene); stage.setTitle("AJDM · Disposable UI test"); stage.show(); stage.setFullScreen(true);
            table = lookupTable(scene.getRoot());
            Thread.ofPlatform().daemon(true).name("ajdm-ui-smoke-driver").start(this::exercise);
        }
        @SuppressWarnings("unchecked")
        private static TableView<DownloadRow> lookupTable(Parent root) { return (TableView<DownloadRow>) root.lookup("#downloads-table"); }
        private void exercise() {
            try {
                await(() -> model.connectedProperty().get());
                fx(() -> {
                    var details = (Button) stage.getScene().lookup("#details-download");
                    require(details != null && details.isDisabled(), "Details must explain its selection requirement");
                    return null;
                });
                fx(() -> {
                    window.addDownload(); var form = dialogRoot();
                    ((TextField) form.lookup("#source-url")).setText(source.uri().toString());
                    ((TextField) form.lookup("#download-filename")).setText("verified.bin");
                    ((Button) form.lookup("#start-download")).fire(); return null;
                });
                await(() -> table.getItems().size() == 1 && table.getItems().getFirst().reading().bytesPerSecond() > 0);
                fx(() -> {
                    table.getSelectionModel().selectFirst();
                    require(!((Button) stage.getScene().lookup("#pause-download")).isDisabled(), "Pause unavailable for active download");
                    require(((Button) stage.getScene().lookup("#delete-download")).isDisabled(), "Active stream can be deleted without pausing");
                    Snapshot.write(stage.getScene(), Path.of("build/ui-smoke/downloads.png"));
                    ((Button) stage.getScene().lookup("#pause-download")).fire(); return null;
                });
                await(() -> table.getItems().getFirst().state().equals("PAUSED") && !((Button) stage.getScene().lookup("#resume-download")).isDisabled());
                long saved = fx(() -> table.getItems().getFirst().durable()); require(saved > 0, "Pause did not save progress");
                fx(() -> {
                    require(!((Button) stage.getScene().lookup("#delete-download")).isDisabled(), "Paused stream has no Delete control");
                    ((CheckBox) stage.getScene().lookup("#change-connections")).setSelected(true);
                    require(!((Button) stage.getScene().lookup("#resume-download")).isDisabled(), "Resume unavailable");
                    ((Button) stage.getScene().lookup("#resume-download")).fire(); return null;
                });
                await(() -> table.getItems().getFirst().state().equals("COMPLETED"));
                require(java.util.Arrays.equals(source.data(), Files.readAllBytes(temp.resolve("downloads/verified.bin"))), "Downloaded bytes differ");
                fx(() -> { ((Button) stage.getScene().lookup("#details-download")).fire(); return null; });
                await(() -> ((Label) dialogRoot().lookup("#download-details-state")).getText().contains("Completed"));
                fx(() -> {
                    require(((Label) dialogRoot().lookup("#download-details-state")).getText().contains("verified.bin"), "Details lost selected file");
                    require(dialogRoot().lookup("#open-download-folder") != null && !dialogRoot().lookup("#open-download-file").isDisabled(), "Completed file has no open controls");
                    require(DesktopGateway.localTarget(table.getItems().getFirst(), false).equals(temp.resolve("downloads/verified.bin")), "Open file resolved the wrong selected path");
                    Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/download-details.png"));
                    closeDialog(); return null;
                });
                fx(() -> {
                    for (String control : List.of("pause-download", "resume-download", "cancel-download"))
                        require(((Button) stage.getScene().lookup("#" + control)).isDisabled(), "Completed stream permits " + control);
                    window.addDownload(); var form = dialogRoot();
                    var analyse = (Button) form.lookup("#analyse-qualities");
                    require(analyse != null && analyse.isDisabled(), "Analyse qualities should wait for a URL");
                    pasteFixtureUrl(form);
                    ((TextField) form.lookup("#download-filename")).setText("chosen-video.mkv");
                    setConnections(form, 8);
                    var tuning = (CheckBox) form.lookup("#adaptive-connections");
                    require(tuning != null && !tuning.isSelected(), "Adaptive setting must remain an explicit choice");
                    tuning.setSelected(true);
                    require(!analyse.isDisabled(), "Add download has no visible Analyse qualities action after URL entry");
                    Snapshot.write(form.getScene(), Path.of("build/ui-smoke/analyse-entry.png"));
                    analyse.fire(); return null;
                });
                await(() -> ((TableView<?>) dialogRoot().lookup("#formats-table")).getItems().size() == 6);
                fx(() -> {
                    require(((TextField) dialogRoot().lookup("#source-url")).getText().equals("https://example.test/video"), "Analysis lost pasted URL");
                    require(((TextField) dialogRoot().lookup("#download-filename")).getText().equals("chosen-video.mkv"), "Analysis lost filename");
                    require(((TextField) dialogRoot().lookup("#destination-folder")).getText().equals(temp.resolve("downloads").toString()), "Analysis lost folder");
                    require(((Spinner<?>) dialogRoot().lookup("#download-connections")).getValue().equals(8), "Analysis lost connection count");
                    require(((CheckBox) dialogRoot().lookup("#adaptive-connections")).isSelected(), "Media picker lost adaptive choice");
                    require(((Button) dialogRoot().lookup("#discover-formats")).getText().equals("Analyse qualities"), "Media analysis button has an unclear name");
                    require(table.getItems().size() == 1, "Metadata analysis started a download");
                    var formats = (TableView<?>) dialogRoot().lookup("#formats-table");
                    require(formats.getColumns().getFirst().getText().equals("Quality"), "Quality list leads with technical IDs");
                    require(!((TitledPane) dialogRoot().lookup("#advanced-formats")).isExpanded(), "Technical controls dominate the quality picker");
                    require(((Button) dialogRoot().lookup("#start-download")).isDisabled(), "Picker chose a quality before user selection");
                    selectFormat(dialogRoot(), "v4320");
                    require(((Label) dialogRoot().lookup("#selected-quality")).getText().contains("4320p (8K)"), "Above-4K quality was hidden or mislabeled");
                    selectFormat(dialogRoot(), "v1080");
                    require(((TextField) dialogRoot().lookup("#custom-format")).getText().equals("v1080"), "Video selection lost");
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().equals("a128"), "Audio was not automatically paired with chosen video");
                    require(((Label) dialogRoot().lookup("#selected-quality")).getText().contains("Full HD"), "Chosen quality is not human-readable");
                    selectAudio(dialogRoot(), "a96");
                    ((Button) dialogRoot().lookup("#preview-selection")).fire(); return null;
                });
                await(() -> !dialogRoot().lookup("#formats-table").isDisabled());
                fx(() -> {
                    require(((TextField) dialogRoot().lookup("#custom-format")).getText().equals("v1080"), "Preview changed chosen video");
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().equals("a96"), "Preview changed chosen audio track");
                    require(((Label) dialogRoot().lookup("#format-inventory")).getText().contains("Download file:"), "Preview did not show its resolved file type");
                    selectFormat(dialogRoot(), "blocked");
                    require(!((Label) dialogRoot().lookup("#format-inventory")).getText().contains("Download file:"), "Quality change kept a stale preview file type");
                    require(((Button) dialogRoot().lookup("#start-download")).isDisabled(), "Unsupported stream allowed download");
                    selectFormat(dialogRoot(), "combined");
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().isEmpty(), "Combined video unnecessarily paired another audio track");
                    selectFormat(dialogRoot(), "a128");
                    require(((TextField) dialogRoot().lookup("#custom-format")).getText().equals("a128"), "Audio-only choice kept prior video");
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().isEmpty(), "Audio-only choice kept video pairing");
                    selectFormat(dialogRoot(), "v1080");
                    ((CheckBox) dialogRoot().lookup("#include-sound")).setSelected(false);
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().isEmpty(), "Silent-video choice still includes sound");
                    ((Button) dialogRoot().lookup("#preview-selection")).fire(); return null;
                });
                await(() -> !dialogRoot().lookup("#formats-table").isDisabled());
                fx(() -> {
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().isEmpty(), "Preview added audio to silent video");
                    ((CheckBox) dialogRoot().lookup("#include-sound")).setSelected(true);
                    require(((TextField) dialogRoot().lookup("#custom-audio")).getText().equals("a128"), "Re-enabling sound after preview did not restore an audio track");
                    Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/media.png"));
                    blockMedia.set(true); ((Button) dialogRoot().lookup("#discover-formats")).fire(); return null;
                });
                await(() -> !dialogRoot().lookup("#formats-table").isDisabled());
                fx(() -> {
                    require(((TableView<?>) dialogRoot().lookup("#formats-table")).getItems().isEmpty(), "Failed re-analysis kept stale formats for the same URL");
                    require(((Button) dialogRoot().lookup("#start-download")).isDisabled(), "Failed analysis allowed a stale selection");
                    String error = ((Label) dialogRoot().lookup("#format-inventory")).getText();
                    require(error.contains("AJDM-4006") && error.contains("Settings") && !error.contains("secret"), "Analysis error was hidden or leaked diagnostics");
                    Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/media-access-error.png"));
                    blockMedia.set(false); ((Button) dialogRoot().lookup("#discover-formats")).fire(); return null;
                });
                await(() -> ((TableView<?>) dialogRoot().lookup("#formats-table")).getItems().size() == 6);
                fx(() -> {
                    var formats = (TableView<?>) dialogRoot().lookup("#formats-table");
                    ((TextField) dialogRoot().lookup("#source-url")).setText("https://example.test/other-video");
                    require(formats.getItems().isEmpty(), "URL change left stale qualities visible");
                    require(((Button) dialogRoot().lookup("#start-download")).isDisabled(), "URL change allowed download before new analysis");
                    closeDialog(); window.playlist(); var form = dialogRoot(); ((TextField) form.lookup("#playlist-url")).setText("https://example.test/playlist"); ((Button) form.lookup("#discover-playlist")).fire(); return null;
                });
                await(() -> ((TableView<?>) dialogRoot().lookup("#playlist-table")).getItems().size() == 1);
                fx(() -> {
                    Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/playlist.png")); closeDialog();
                    window.about(); Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/about.png")); closeDialog();
                    return null;
                });
                Platform.runLater(window::settings);
                fx(() -> {
                    var pane = dialogRoot();
                    require(mediaSession.get() == null, "Settings automatically read a browser session");
                    ((ComboBox<?>) pane.lookup("#media-browser")).getSelectionModel().select(0);
                    ((Button) pane.lookup("#configure-media-browser")).fire(); return null;
                });
                await(() -> "firefox".equals(mediaSession.get()) && !dialogRoot().lookup("#configure-media-browser").isDisabled());
                fx(() -> {
                    Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/media-session.png"));
                    var pane = dialogRoot();
                    pane.lookupAll(".button").stream().map(Button.class::cast).filter(b -> b.getText().equals("Clear media cookies")).findFirst().orElseThrow().fire();
                    return null;
                });
                await(() -> mediaSession.get() == null);
                fx(() -> {
                    var pane = (DialogPane) dialogRoot();
                    pane.lookupAll(".check-box").stream().map(CheckBox.class::cast).filter(check -> check.getText().equals("Dark theme")).findFirst().orElseThrow().setSelected(true);
                    ((Button) pane.lookupButton(ButtonType.APPLY)).fire();
                    return null;
                });
                await(() -> stage.getScene().getRoot().getStyleClass().contains("dark"));
                fx(() -> {
                    Snapshot.write(stage.getScene(), Path.of("build/ui-smoke/dark.png"));
                    window.about(); require(dialogRoot().getStyleClass().contains("dark"), "Dialog did not inherit dark theme"); closeDialog(); return null;
                });
                fx(() -> {
                    table.getSelectionModel().selectFirst(); ((Button) stage.getScene().lookup("#delete-download")).fire();
                    var pane = (DialogPane) dialogRoot(); var files = (CheckBox) pane.lookup("#delete-files");
                    require(!files.isSelected(), "Delete defaults to destroying files"); files.setSelected(true);
                    ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                    require(table.getItems().size() == 1 && Files.exists(temp.resolve("downloads/verified.bin")), "Canceling Delete removed data");
                    ((Button) stage.getScene().lookup("#delete-download")).fire();
                    pane = (DialogPane) dialogRoot(); require(!((CheckBox) pane.lookup("#delete-files")).isSelected(), "New confirmation retained an old delete-files choice");
                    ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
                });
                await(() -> table.getItems().isEmpty());
                require(Files.exists(temp.resolve("downloads/verified.bin")), "Entry-only deletion removed the output file");
                fx(() -> {
                    window.addDownload(); var form = dialogRoot(); ((TextField) form.lookup("#source-url")).setText(source.uri().toString());
                    ((TextField) form.lookup("#download-filename")).setText("canceled.bin"); ((Button) form.lookup("#start-download")).fire(); return null;
                });
                await(() -> table.getItems().size() == 1 && table.getItems().getFirst().active());
                fx(() -> {
                    table.getSelectionModel().selectFirst(); ((Button) stage.getScene().lookup("#cancel-download")).fire();
                    var pane = (DialogPane) dialogRoot(); ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
                });
                await(() -> table.getItems().getFirst().state().equals("CANCELED"));
                fx(() -> {
                    require(((Button) stage.getScene().lookup("#resume-download")).isDisabled(), "Canceled stream can be resumed");
                    Snapshot.write(stage.getScene(), Path.of("build/ui-smoke/stream-controls.png"));
                    ((Button) stage.getScene().lookup("#delete-download")).fire(); var pane = (DialogPane) dialogRoot();
                    ((CheckBox) pane.lookup("#delete-files")).setSelected(true);
                    Snapshot.write(pane.getScene(), Path.of("build/ui-smoke/delete-confirmation.png"));
                    ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
                });
                await(() -> table.getItems().isEmpty());
                try (var files = Files.list(temp.resolve("downloads"))) { require(files.count() == 1, "File deletion left canceled partials or removed unrelated output"); }
                var client = new DesktopGateway(temp.resolve("ipc/socket"));
                var playlistRequest = FormRequests.base("https://example.test/unavailable", temp.resolve("downloads").toString(), 4);
                client.call("addPlaylist", playlistRequest);
                await(() -> table.getItems().size() == 1 && table.getItems().getFirst().state().equals("COMPLETED"));
                fx(() -> {
                    table.getSelectionModel().selectFirst(); require(table.getItems().getFirst().name().equals("Local playlist fixture"), "Playlist row shows a folder instead of its title");
                    ((Button) stage.getScene().lookup("#details-download")).fire(); return null;
                });
                await(() -> ((Label) dialogRoot().lookup("#download-details-state")).getText().contains("1 skipped"));
                fx(() -> {
                    Snapshot.write(dialogRoot().getScene(), Path.of("build/ui-smoke/playlist-details.png")); closeDialog();
                    return null;
                });
                await(() -> table.getSelectionModel().getSelectedItem() != null && !((Button) stage.getScene().lookup("#delete-download")).isDisabled());
                fx(() -> {
                    ((Button) stage.getScene().lookup("#delete-download")).fire(); var pane = (DialogPane) dialogRoot();
                    ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
                });
                await(() -> table.getItems().isEmpty());
                var request = FormRequests.base(source.uri().toString(), temp.resolve("downloads").toString(), 8);
                FormRequests.filename(request, "outlives-ui.bin"); client.call("add", request);
                fx(() -> { model.close(); return null; });
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (client.downloads(System.nanoTime()).stream().noneMatch(row -> row.name().equals("outlives-ui.bin") && row.state().equals("COMPLETED"))) {
                    require(System.nanoTime() < deadline, "Core did not continue after UI client shutdown"); Thread.sleep(30);
                }
                require(java.util.Arrays.equals(source.data(), Files.readAllBytes(temp.resolve("downloads/outlives-ui.bin"))), "Client shutdown affected transfer bytes");
                System.out.println("UI_SMOKE_PASS: add, live progress, pause, resume8, verified bytes, state-aware controls, Cancel, Delete confirmation/cancellation, keep-files/delete-files, paste/analyse, preserved inputs, metadata-only all-quality list, readable qualities, automatic/explicit audio, preview preserves choices, unsupported/combined/audio-only/silent-video handling, URL invalidation, playlist, About, dark theme, core outlives UI client.");
            } catch (Throwable exception) { failure = exception; }
            finally { Platform.runLater(stage::close); }
        }
        @SuppressWarnings("unchecked")
        private static void setConnections(Parent form, int value) { ((Spinner<Integer>) form.lookup("#download-connections")).getValueFactory().setValue(value); }
        @SuppressWarnings("unchecked")
        private static void selectFormat(Parent form, String id) {
            var choices = (TableView<JsonObject>) form.lookup("#formats-table");
            choices.getSelectionModel().select(choices.getItems().stream().filter(row -> row.get("formatId").getAsString().equals(id)).findFirst().orElseThrow());
        }
        @SuppressWarnings("unchecked")
        private static void selectAudio(Parent form, String id) {
            var choices = (ComboBox<JsonObject>) form.lookup("#audio-track");
            choices.setValue(choices.getItems().stream().filter(row -> row.get("formatId").getAsString().equals(id)).findFirst().orElseThrow());
        }
        private static void pasteFixtureUrl(Parent form) {
            var clipboard = Clipboard.getSystemClipboard(); var previous = new ClipboardContent();
            for (var type : clipboard.getContentTypes()) {
                var value = clipboard.getContent(type); if (value != null) previous.put(type, value);
            }
            var fixture = new ClipboardContent(); fixture.putString("https://example.test/video");
            try { clipboard.setContent(fixture); ((Button) form.lookup("#paste-url")).fire(); }
            finally { clipboard.setContent(previous); }
        }
        private Parent dialogRoot() {
            return Window.getWindows().stream().filter(window -> window != stage && window.isShowing()).findFirst().orElseThrow().getScene().getRoot();
        }
        private void closeDialog() {
            var pane = (DialogPane) dialogRoot(); ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
        }
        private static <T> T fx(Callable<T> action) throws Exception { var task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS); }
        private static void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!fx(condition::getAsBoolean)) { if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for UI state"); Thread.sleep(30); }
        }
        private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
        @Override public void stop() throws Exception {
            if (model != null) model.close(); if (server != null) server.close(); if (core != null) core.close(); if (source != null) source.close();
        }
    }
}
