package io.ajdm.ui;

import com.google.gson.JsonObject;
import io.ajdm.bench.FaultyRangeServer;
import io.ajdm.core.CoreService;
import io.ajdm.ipc.IpcServer;
import io.ajdm.media.*;
import io.ajdm.model.DownloadSource;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
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

/**
 * Explicit graphical check of the IDM-style window, separate from display-independent check. Uses only disposable
 * fixture data, drives the real dialogs against a real core, and saves a screenshot of every window in
 * build/ui-smoke/ (light and dark).
 */
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
        private final java.util.concurrent.atomic.AtomicInteger analyses = new java.util.concurrent.atomic.AtomicInteger();
        @Override public void init() throws Exception {
            temp = Files.createTempDirectory("ajdm-ui-smoke-");
            source = new FaultyRangeServer(8 * 1024 * 1024 + 17, FaultyRangeServer.Mode.CAPPED, 128 * 1024);
            var extractor = new MediaExtractor() {
                @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws io.ajdm.model.DownloadException {
                    analyses.incrementAndGet();
                    if (page.getPath().contains("access-blocked")) throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.MEDIA_ACCESS_BLOCKED, "secret diagnostics");
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
            var scene = new Scene(window.build(), 1280, 760); scene.getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
            stage.setScene(scene); stage.setTitle("AJDM · Disposable UI test"); stage.show();
            table = lookupTable(scene.getRoot());
            Thread.ofPlatform().daemon(true).name("ajdm-ui-smoke-driver").start(this::exercise);
        }
        @SuppressWarnings("unchecked")
        private static TableView<DownloadRow> lookupTable(Parent root) { return (TableView<DownloadRow>) root.lookup("#downloads-table"); }
        private Button tool(String id) { return (Button) stage.getScene().lookup("#" + id); }
        private void exercise() {
            try {
                await(() -> model.connectedProperty().get());
                fx(() -> {
                    for (String id : List.of("tool-resume", "tool-stop", "tool-delete")) require(tool(id).isDisabled(), id + " must wait for a selection");
                    require(stage.getScene().lookup("#category-tree") != null && stage.getScene().lookup("#menu-bar") != null, "IDM layout missing");
                    require(table.getColumns().stream().map(TableColumn::getText).toList().equals(List.of("File Name", "Size", "Status", "Time left", "Transfer rate", "Description")),
                            "Columns differ from IDM's");
                    tool("tool-add").fire(); return null;
                });
                // Add URL, then the Download File Info box; Start closes it at once and the progress window opens.
                fx(() -> {
                    var add = (DialogPane) windowWith("add-url-dialog");
                    ((TextField) add.lookup("#address")).setText(source.uri().toString());
                    snapshot(add, "add-url.png");
                    ((Button) add.lookup("#add-url-ok")).fire(); return null;
                });
                await(() -> windowWith("file-info-dialog") != null);
                fx(() -> {
                    var info = windowWith("file-info-dialog");
                    var saveAs = (TextField) info.lookup("#file-info-save-as");
                    saveAs.setText(Path.of(saveAs.getText()).resolveSibling("verified.bin").toString());
                    require(((ComboBox<?>) info.lookup("#file-info-category")).getValue() != null, "No category chosen");
                    snapshot(info, "file-info.png");
                    ((Button) info.lookup("#file-info-start")).fire(); return null;
                });
                await(() -> windowWith("file-info-dialog") == null);
                await(() -> windowWith("progress-window") != null && table.getItems().size() == 1 && table.getItems().getFirst().reading().bytesPerSecond() > 0);
                await(() -> !((Label) windowWith("progress-window").lookup("#progress-rate")).getText().isBlank());
                fx(() -> {
                    var progress = windowWith("progress-window");
                    require(((Label) progress.lookup("#progress-status")).getText().startsWith("Receiving"), "Progress window does not say it is receiving data");
                    require(((Label) progress.lookup("#progress-resumable")).getText().equals("Yes"), "Resume capability missing");
                    snapshot(progress, "progress.png");
                    table.getSelectionModel().selectFirst();
                    require(!tool("tool-stop").isDisabled(), "Stop unavailable for an active download");
                    require(tool("tool-resume").isDisabled(), "Resume offered for a running download");
                    snapshot(stage.getScene().getRoot(), "downloads.png");
                    ((Button) progress.lookup("#progress-pause")).fire(); return null;
                });
                await(() -> table.getItems().getFirst().state().equals("PAUSED") && !tool("tool-resume").isDisabled());
                long saved = fx(() -> table.getItems().getFirst().durable()); require(saved > 0, "Stop did not save progress");
                fx(() -> {
                    var progress = windowWith("progress-window");
                    require(((Button) progress.lookup("#progress-pause")).getText().equals("Resume"), "Pause did not become Resume");
                    ((CheckBox) progress.lookup("#progress-own-limit")).setSelected(true);
                    @SuppressWarnings("unchecked") var own = (Spinner<Integer>) progress.lookup("#progress-own-kibps"); own.getValueFactory().setValue(512);
                    ((Button) progress.lookup("#progress-apply-own")).fire(); return null;
                });
                await(() -> table.getItems().getFirst().limitKiBps() == 512);
                fx(() -> {
                    var progress = windowWith("progress-window");
                    ((javafx.scene.control.TabPane) progress.lookup(".tab-pane")).getSelectionModel().select(1);
                    snapshot(progress, "progress-limiter.png");
                    ((javafx.scene.control.TabPane) progress.lookup(".tab-pane")).getSelectionModel().select(0);
                    ((CheckBox) progress.lookup("#progress-own-limit")).setSelected(false);
                    ((Button) progress.lookup("#progress-apply-own")).fire(); return null;
                });
                await(() -> table.getItems().getFirst().limitKiBps() == 0);
                fx(() -> { tool("tool-resume").fire(); return null; });
                await(() -> table.getItems().getFirst().state().equals("COMPLETED"));
                require(java.util.Arrays.equals(source.data(), Files.readAllBytes(temp.resolve("downloads/verified.bin"))), "Downloaded bytes differ");
                // The app's completion watcher turns the core's completion into IDM's "Download complete" box.
                var watcher = new CompletionWatcher();
                fx(() -> {
                    var id = table.getItems().getFirst().id();
                    watcher.update(List.of(new DownloadRow(id, "DOWNLOADING", 0, 0, 1, "x", true, "", new RateTracker().sample(id, "DOWNLOADING", 0, 1, 1))));
                    for (var event : watcher.update(List.copyOf(model.rows()))) window.completed(event);
                    return null;
                });
                await(() -> windowWith("complete-dialog") != null && windowWith("progress-window") == null);
                fx(() -> {
                    var complete = windowWith("complete-dialog");
                    require(((TextField) complete.lookup("#complete-path")).getText().endsWith("verified.bin"), "Complete box names the wrong file");
                    require(DesktopGateway.localTarget(table.getItems().getFirst(), false).equals(temp.resolve("downloads/verified.bin")), "Open resolves the wrong path");
                    snapshot(complete, "complete.png");
                    ((Button) complete.lookup("#complete-close")).fire();
                    for (String id : List.of("tool-stop", "tool-resume")) require(tool(id).isDisabled(), "Completed download permits " + id);
                    return null;
                });
                // A browser link with a known size opens the File Info box immediately, already showing the size.
                fx(() -> { window.offer("https://cdn.example/debian-13.iso", "https://www.debian.org/", "debian-13.iso", false, 734_003_200L, null, null); return null; });
                await(() -> windowWith("file-info-dialog") != null);
                fx(() -> {
                    var info = windowWith("file-info-dialog");
                    require(((Label) info.lookup("#file-info-size")).getText().equals("700.00 MB"), "Browser-known size not shown");
                    require(((TextField) info.lookup("#file-info-save-as")).getText().endsWith("debian-13.iso"), "Browser filename lost");
                    snapshot(info, "file-info-browser.png");
                    ((Button) ((DialogPane) info).lookupButton(ButtonType.CANCEL)).fire(); return null;
                });
                // Video: the short quality list with sound paired in; Download Later keeps it stopped.
                fx(() -> { new QualityDialog(window, "https://example.test/video", null, null).show(); return null; });
                await(() -> windowWith("quality-dialog") != null && ((ListView<?>) windowWith("quality-dialog").lookup("#quality-list")).getItems().size() == 4);
                fx(() -> {
                    var dialog = windowWith("quality-dialog");
                    @SuppressWarnings("unchecked") var list = (ListView<JsonObject>) dialog.lookup("#quality-list");
                    var labels = list.getItems().stream().map(entry -> entry.get("label").getAsString()).toList();
                    require(labels.equals(List.of("MP4  4320p HDR", "MP4  1080p", "MP4  720p", "MP4  Audio 128 kb/s")), "Quality list: " + labels);
                    var first = list.getSelectionModel().getSelectedItem();
                    require(first != null && first.get("formatId").getAsString().equals("v4320") && first.get("audioId").getAsString().equals("a128"), "Best quality not preselected with sound");
                    require(((Label) dialog.lookup("#quality-title")).getText().equals("Local metadata fixture"), "Video title missing");
                    list.getSelectionModel().select(1); return null;
                });
                fx(() -> null); // One layout pass, so the screenshot shows the new selection.
                fx(() -> {
                    var dialog = windowWith("quality-dialog");
                    snapshot(dialog, "quality.png");
                    ((CheckBox) dialog.lookup("#quality-show-all")).setSelected(true);
                    require(((TableView<?>) dialog.lookup("#formats-table")).getItems().size() == 5, "Show all formats lost or added streams");
                    return null;
                });
                fx(() -> null);
                fx(() -> {
                    var dialog = windowWith("quality-dialog");
                    snapshot(dialog, "quality-all.png");
                    ((CheckBox) dialog.lookup("#quality-show-all")).setSelected(false);
                    ((Button) dialog.lookup("#quality-later")).fire(); return null;
                });
                await(() -> table.getItems().size() == 2);
                fx(() -> {
                    var video = table.getItems().stream().filter(row -> !row.name().equals("verified.bin")).findFirst().orElseThrow();
                    require(video.state().equals("PAUSED"), "Download Later started the video: " + video.state());
                    return null;
                });
                require(analyses.get() == 1, "Download Later analysed the video again (" + analyses.get() + " analyses)");
                // Named queues: shown in the tree and the Scheduler, with days; Move to queue files a download there.
                var queueClient = new DesktopGateway(temp.resolve("ipc/socket"));
                var night = new JsonObject(); night.addProperty("name", "Night films"); night.addProperty("scheduled", true);
                night.addProperty("start", "23:00"); night.addProperty("end", "06:00"); night.add("days", new com.google.gson.JsonArray());
                queueClient.call("saveQueue", night);
                fx(() -> { window.refreshQueue(); return null; });
                await(() -> window.queueNames().contains("Night films"));
                fx(() -> {
                    var video = table.getItems().stream().filter(row -> !row.name().equals("verified.bin")).findFirst().orElseThrow();
                    window.moveToQueue(List.of(video), "Night films"); return null;
                });
                await(() -> table.getItems().stream().anyMatch(row -> row.queue().equals("Night films")));
                fx(() -> {
                    @SuppressWarnings("unchecked") var tree = (TreeView<String>) stage.getScene().lookup("#category-tree");
                    var item = tree.getRoot().getChildren().stream().filter(i -> "queues".equals(i.getValue())).findFirst().orElseThrow()
                            .getChildren().stream().filter(i -> "queue:Night films".equals(i.getValue())).findFirst().orElseThrow();
                    tree.getSelectionModel().select(item);
                    require(table.getItems().size() == 1 && table.getItems().getFirst().queue().equals("Night films"), "Queue filter shows other downloads");
                    snapshot(stage.getScene().getRoot(), "queue-tree.png");
                    tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst());
                    window.scheduler(); return null;
                });
                await(() -> windowWith("scheduler-dialog") != null && ((ListView<?>) windowWith("scheduler-dialog").lookup("#scheduler-queues")).getItems().size() == 2);
                fx(() -> {
                    var dialog = windowWith("scheduler-dialog");
                    @SuppressWarnings("unchecked") var list = (ListView<String>) dialog.lookup("#scheduler-queues"); list.getSelectionModel().select("Night films");
                    ((CheckBox) dialog.lookup("#day-friday")).setSelected(true); ((CheckBox) dialog.lookup("#day-saturday")).setSelected(true);
                    snapshot(dialog, "scheduler-queue.png");
                    ((Button) dialog.lookup("#scheduler-apply")).fire(); return null;
                });
                await(() -> {
                    try { return queueClient.call("queues", new JsonObject()).getAsJsonArray("queues").get(0).getAsJsonObject().getAsJsonArray("days").size() == 2; }
                    catch (java.io.IOException e) { return false; }
                });
                fx(() -> { ((Button) ((DialogPane) windowWith("scheduler-dialog")).lookupButton(ButtonType.CLOSE)).fire(); return null; });
                // A blocked video explains itself without leaking diagnostics.
                fx(() -> { new QualityDialog(window, "https://example.test/access-blocked", null, null).show(); return null; });
                await(() -> windowWith("quality-dialog") != null && ((Label) windowWith("quality-dialog").lookup("#quality-status")).getText().contains("AJDM-4006"));
                fx(() -> {
                    var dialog = windowWith("quality-dialog");
                    require(!((Label) dialog.lookup("#quality-status")).getText().contains("secret"), "Diagnostics leaked");
                    require(dialog.lookup("#quality-start").isDisabled(), "Failed analysis allowed a download");
                    snapshot(dialog, "quality-error.png");
                    ((Button) ((DialogPane) dialog).lookupButton(ButtonType.CANCEL)).fire();
                    window.playlist(); var form = anyDialog(); ((TextField) form.lookup("#playlist-url")).setText("https://example.test/playlist"); ((Button) form.lookup("#discover-playlist")).fire(); return null;
                });
                await(() -> ((TableView<?>) anyDialog().lookup("#playlist-table")).getItems().size() == 1);
                fx(() -> {
                    snapshot(anyDialog(), "playlist.png"); ((Button) ((DialogPane) anyDialog()).lookupButton(ButtonType.CLOSE)).fire();
                    window.about(); var about = anyDialog();
                    require(about.lookupAll(".label").stream().map(node -> ((Label) node).getText()).anyMatch(text -> text.contains("in memory of my brother")), "The dedication is missing");
                    snapshot(about, "about.png"); ((Button) ((DialogPane) about).lookupButton(ButtonType.CLOSE)).fire();
                    window.scheduler(); return null;
                });
                await(() -> windowWith("scheduler-dialog") != null && !windowWith("scheduler-dialog").lookup("#scheduler-apply").isDisabled());
                fx(() -> { var dialog = windowWith("scheduler-dialog"); snapshot(dialog, "scheduler.png"); ((Button) ((DialogPane) dialog).lookupButton(ButtonType.CLOSE)).fire(); return null; });
                // Options: video-site sign-in is applied at once and cleared; dark theme applies on OK.
                Platform.runLater(() -> new OptionsDialog(window).show(OptionsDialog.Tab.VIDEO));
                await(() -> windowWith("options-dialog") != null);
                fx(() -> {
                    var pane = windowWith("options-dialog");
                    require(mediaSession.get() == null, "Options read a browser session by themselves");
                    @SuppressWarnings("unchecked") var browsers = (ComboBox<String>) pane.lookup("#media-browser"); browsers.setValue("firefox");
                    ((Button) pane.lookup("#configure-media-browser")).fire(); return null;
                });
                await(() -> "firefox".equals(mediaSession.get()) && !windowWith("options-dialog").lookup("#configure-media-browser").isDisabled());
                fx(() -> { var pane = windowWith("options-dialog"); snapshot(pane, "options-video.png"); ((Button) pane.lookup("#clear-media-cookies")).fire(); return null; });
                await(() -> mediaSession.get() == null);
                fx(() -> {
                    var pane = (DialogPane) windowWith("options-dialog");
                    var tabs = (TabPane) pane.lookup("#options-tabs"); tabs.getSelectionModel().select(0);
                    snapshot(pane, "options-general.png");
                    tabs.getSelectionModel().select(2); snapshot(pane, "options-downloads.png");
                    tabs.getSelectionModel().select(0);
                    ((CheckBox) pane.lookup("#dark-theme")).setSelected(true);
                    ((Button) pane.lookup("#options-ok")).fire(); return null;
                });
                await(() -> stage.getScene().getRoot().getStyleClass().contains("dark"));
                fx(() -> {
                    table.getSelectionModel().clearSelection(); table.getSelectionModel().select(0);
                    snapshot(stage.getScene().getRoot(), "dark.png");
                    window.about(); require(anyDialog().getStyleClass().contains("dark"), "Dialog did not follow the dark theme");
                    ((Button) ((DialogPane) anyDialog()).lookupButton(ButtonType.CLOSE)).fire(); return null;
                });
                // Delete: files are kept unless asked.
                fx(() -> {
                    table.getSelectionModel().clearSelection();
                    table.getSelectionModel().select(table.getItems().stream().filter(row -> row.name().equals("verified.bin")).findFirst().orElseThrow());
                    Platform.runLater(() -> tool("tool-delete").fire()); return null;
                });
                await(() -> windowWith("delete-confirmation") != null);
                fx(() -> {
                    var pane = (DialogPane) windowWith("delete-confirmation"); var files = (CheckBox) pane.lookup("#delete-files");
                    require(!files.isSelected(), "Delete defaults to destroying files");
                    snapshot(pane, "delete.png");
                    ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
                });
                await(() -> table.getItems().stream().noneMatch(row -> row.name().equals("verified.bin")));
                require(Files.exists(temp.resolve("downloads/verified.bin")), "Entry-only deletion removed the file");
                // Deleting a running download with its files stops it first and leaves no partials.
                fx(() -> { window.afterQueue(AfterQueue.SHUTDOWN); new FileInfoDialog(window, source.uri().toString(), null, "removed.bin", -1).startNow(); return null; });
                await(() -> table.getItems().stream().anyMatch(row -> row.name().equals("removed.bin") && row.active()));
                fx(() -> {
                    windowsWith("progress-window").forEach(root -> ((Stage) root.getScene().getWindow()).close());
                    table.getSelectionModel().clearSelection(); table.getSelectionModel().selectAll();
                    Platform.runLater(() -> tool("tool-delete").fire()); return null;
                });
                await(() -> windowWith("delete-confirmation") != null);
                fx(() -> { var pane = (DialogPane) windowWith("delete-confirmation"); ((CheckBox) pane.lookup("#delete-files")).setSelected(true); ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null; });
                await(() -> table.getItems().isEmpty());
                try (var files = Files.list(temp.resolve("downloads"))) {
                    var left = files.map(path -> path.getFileName().toString()).toList();
                    require(left.equals(List.of("verified.bin")), "Deleting with files left: " + left);
                }
                await(() -> Countdown.lastCommand != null);
                require(Countdown.lastCommand.equals(String.join(" ", AfterQueue.command(AfterQueue.SHUTDOWN, System.getProperty("os.name")))), "After-queue ran " + Countdown.lastCommand);
                require(fx(() -> window.afterQueue()) == AfterQueue.NOTHING, "The after-queue action must run only once");
                // A playlist shows its own status window.
                var client = new DesktopGateway(temp.resolve("ipc/socket"));
                client.call("addPlaylist", FormRequests.base("https://example.test/unavailable", temp.resolve("downloads").toString(), 4));
                await(() -> table.getItems().size() == 1 && table.getItems().getFirst().state().equals("COMPLETED"));
                fx(() -> { new PlaylistStatusDialog(window, table.getItems().getFirst()).show(); return null; });
                await(() -> ((Label) anyDialog().lookup("#playlist-status")).getText().contains("1 skipped"));
                fx(() -> { snapshot(anyDialog(), "playlist-status.png"); ((Button) ((DialogPane) anyDialog()).lookupButton(ButtonType.CLOSE)).fire(); return null; });
                // The core keeps downloading after the window's client closes.
                var request = FormRequests.base(source.uri().toString(), temp.resolve("downloads").toString(), 8);
                FormRequests.filename(request, "outlives-ui.bin"); client.call("add", request);
                fx(() -> { model.close(); return null; });
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (client.downloads(System.nanoTime()).stream().noneMatch(row -> row.name().equals("outlives-ui.bin") && row.state().equals("COMPLETED"))) {
                    require(System.nanoTime() < deadline, "Core did not continue after UI client shutdown"); Thread.sleep(30);
                }
                require(java.util.Arrays.equals(source.data(), Files.readAllBytes(temp.resolve("downloads/outlives-ui.bin"))), "Client shutdown affected transfer bytes");
                System.out.println("UI_SMOKE_PASS: IDM layout, Add URL > File Info > Start (closes at once, progress window), live progress, Stop/Resume with saved progress, verified bytes, Download complete box, browser offer with known size, quality list with paired sound and best preselected, Download Later without re-analysis, show-all formats, blocked-video message without diagnostics, playlist picker and status, About dedication, Scheduler, Options video sign-in, dark theme, Delete keeps files, delete running with files, per-download speed limit, named queues in tree and Scheduler with days, Move to queue, shut-down-after-queue countdown (dry run), core outlives UI.");
            } catch (Throwable exception) { failure = exception; exception.printStackTrace(); }
            finally { Platform.runLater(Platform::exit); }
        }
        /** The root of the showing window (other than the main one) that is or contains the node with this id. */
        private Parent windowWith(String id) {
            return Window.getWindows().stream().filter(window -> window != stage && window.isShowing() && window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(root -> id.equals(root.getId()) || root.lookup("#" + id) != null)
                    .findFirst().orElse(null);
        }
        private List<Parent> windowsWith(String id) {
            return Window.getWindows().stream().filter(window -> window != stage && window.isShowing() && window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(root -> id.equals(root.getId()) || root.lookup("#" + id) != null).toList();
        }
        private Parent anyDialog() {
            return Window.getWindows().stream().filter(window -> window != stage && window.isShowing() && window.getScene().getRoot() instanceof DialogPane)
                    .map(window -> window.getScene().getRoot()).findFirst().orElseThrow();
        }
        private static void snapshot(Node root, String name) throws java.io.IOException { Snapshot.write(root.getScene(), Path.of("build/ui-smoke/" + name)); }
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
