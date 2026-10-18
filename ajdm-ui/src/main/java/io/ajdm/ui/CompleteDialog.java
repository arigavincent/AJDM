package io.ajdm.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** IDM's "Download complete" box: where the file was saved, with Open, Open folder and Close. */
final class CompleteDialog {
    private final Stage stage = new Stage();

    CompleteDialog(MainWindow main, DownloadRow row) {
        var heading = new Label("Download complete"); heading.getStyleClass().add("complete-title");
        var sizeLine = new Label("Downloaded " + Display.size(Math.max(row.total(), row.written())));
        var path = new TextField(row.target()); path.setEditable(false); path.setFocusTraversable(false); path.setId("complete-path"); path.setPrefColumnCount(42);
        var open = new Button("Open"); open.setId("complete-open"); open.setDefaultButton(true); open.getStyleClass().add("primary-button");
        var folder = new Button("Open folder"); folder.setId("complete-folder");
        var close = new Button("Close"); close.setId("complete-close"); close.setCancelButton(true);
        open.setDisable(row.kind().equals("PLAYLIST"));
        open.setOnAction(event -> { main.openFile(row, false); stage.close(); });
        folder.setOnAction(event -> { main.openFile(row, true); stage.close(); });
        close.setOnAction(event -> stage.close());
        var dontShow = new CheckBox("Don't show this dialog again"); dontShow.setId("complete-dont-show");
        dontShow.selectedProperty().addListener((o, a, hide) -> main.update(main.currentSettings().withDialogs(main.currentSettings().showFileInfo(), main.currentSettings().showProgress(), !hide)));
        var top = new HBox(14, Icons.finished(44), new VBox(4, heading, new Label(row.name()), sizeLine)); top.setAlignment(Pos.CENTER_LEFT);
        var buttons = new HBox(8, dontShow, MainWindow.spacer(), open, folder, close); buttons.setAlignment(Pos.CENTER_LEFT);
        var content = new VBox(12, top, new Label("The file was saved as:"), path, buttons); content.setPadding(new Insets(16)); content.setId("complete-dialog");
        main.style(stage, content);
        stage.setTitle("Download complete"); stage.setScene(new Scene(content)); stage.setResizable(false);
    }
    void show() { stage.show(); stage.toFront(); stage.getScene().lookup("#complete-open").requestFocus(); }
}
