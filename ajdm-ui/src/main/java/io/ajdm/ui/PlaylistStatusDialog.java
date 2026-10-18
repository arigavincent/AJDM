package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** A playlist's videos and their state, a hundred at a time, refreshed every second while open. */
final class PlaylistStatusDialog {
    private final Dialog<Void> dialog = new Dialog<>();

    PlaylistStatusDialog(MainWindow main, DownloadRow row) {
        var state = new Label("Loading the playlist…"); state.setId("playlist-status"); state.setWrapText(true); state.setMaxWidth(640);
        var rows = new ListView<String>(); rows.setPrefSize(640, 360);
        Button previous = new Button("Previous 100"), next = new Button("Next 100");
        var folder = new Button("Open folder"); folder.setOnAction(event -> main.openFile(row, true));
        dialog.initOwner(main.stage()); dialog.setTitle(row.name());
        dialog.getDialogPane().setContent(new VBox(10, state, rows, new HBox(8, previous, next, MainWindow.spacer(), folder)));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); MainWindow.style(dialog);
        int[] offset = {0}; boolean[] loading = {false};
        Runnable load = () -> {
            if (loading[0] || !dialog.isShowing()) return; loading[0] = true; previous.setDisable(true); next.setDisable(true);
            var request = new JsonObject(); request.addProperty("downloadId", row.id().toString()); request.addProperty("offset", offset[0]); request.addProperty("limit", 100);
            main.model().request("playlistStatus", request, result -> {
                rows.getItems().clear();
                for (var value : result.getAsJsonArray("entries")) {
                    var entry = value.getAsJsonObject();
                    rows.getItems().add(entry.get("index").getAsInt() + ".  " + QualityDialog.AddFormats.text(entry, "state") + "  ·  " + QualityDialog.AddFormats.text(entry, "title"));
                }
                state.setText(QualityDialog.AddFormats.text(result, "title") + "\n" + result.get("completed").getAsInt() + " completed · "
                        + result.get("failed").getAsInt() + " failed · " + result.get("skipped").getAsInt() + " skipped");
                loading[0] = false; previous.setDisable(offset[0] == 0); next.setDisable(offset[0] + 100 >= result.get("selected").getAsInt());
            }, error -> { loading[0] = false; state.setText(error); });
        };
        previous.setOnAction(event -> { offset[0] = Math.max(0, offset[0] - 100); load.run(); });
        next.setOnAction(event -> { offset[0] += 100; load.run(); });
        var refresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> load.run()));
        refresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        dialog.setOnShown(event -> { load.run(); refresh.play(); });
        dialog.setOnHidden(event -> refresh.stop());
    }
    void show() { dialog.show(); }
}
