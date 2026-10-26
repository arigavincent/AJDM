package io.ajdm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * IDM's "Enter new address to download": the address (filled from the clipboard when it holds a link) and optional
 * sign-in. OK opens the Download File Info box for a file, or the quality list for a video page.
 */
final class AddUrlDialog {
    private final MainWindow window;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField address = new TextField();
    private final CheckBox authorize = new CheckBox("Use authorization");
    private final TextField login = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label message = new Label();

    AddUrlDialog(MainWindow window) {
        this.window = window;
        dialog.initOwner(window.stage()); dialog.setTitle("Enter new address to download");
        address.setId("address"); address.setPromptText("https://…"); address.setPrefColumnCount(46);
        try {
            String copied = Clipboard.getSystemClipboard().getString();
            if (copied != null && copied.strip().matches("(?i)https?://\\S+")) { address.setText(copied.strip()); address.selectAll(); }
        } catch (RuntimeException unavailable) { /* No clipboard text. */ }
        login.setPromptText("Login"); password.setPromptText("Password"); login.setId("auth-login"); password.setId("auth-password");
        login.disableProperty().bind(authorize.selectedProperty().not()); password.disableProperty().bind(authorize.selectedProperty().not());
        var form = new GridPane(); form.setHgap(10); form.setVgap(10);
        form.addRow(0, new Label("Address:"), address); GridPane.setHgrow(address, Priority.ALWAYS);
        form.add(authorize, 1, 1);
        form.addRow(2, new Label("Login:"), login); form.addRow(3, new Label("Password:"), password);
        message.getStyleClass().add("dialog-message"); message.setWrapText(true);
        var icon = new HBox(Icons.addUrl(40)); icon.setPadding(new Insets(0, 8, 0, 0));
        var content = new HBox(14, icon, new VBox(10, form, message)); HBox.setHgrow(content.getChildren().get(1), Priority.ALWAYS);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setId("add-url-dialog");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        var ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); ok.setId("add-url-ok");
        ok.disableProperty().bind(address.textProperty().isEmpty());
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { if (!accept()) event.consume(); });
        MainWindow.style(dialog);
        dialog.setOnShown(event -> address.requestFocus());
    }
    private boolean accept() {
        String link = address.getText().strip();
        try {
            FormRequests.base(link, window.currentSettings().directory().toString(), 1);
        } catch (IllegalArgumentException invalid) { message.setText("Enter an http:// or https:// address."); return false; }
        String authorization = null;
        if (authorize.isSelected()) {
            if (login.getText().isEmpty()) { message.setText("Enter the login, or untick Use authorization."); return false; }
            authorization = "Basic " + java.util.Base64.getEncoder().encodeToString((login.getText() + ":" + password.getText()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var kind = ClipboardWatcher.classify(link);
        if (kind != null && kind.kind() == ClipboardWatcher.Kind.MEDIA && authorization == null) {
            javafx.application.Platform.runLater(() -> new QualityDialog(window, link, null, null).show());
        } else {
            String auth = authorization;
            javafx.application.Platform.runLater(() -> {
                var info = new FileInfoDialog(window, link, null, MainWindow.guessName(link), -1); info.authorization(auth); info.show();
            });
        }
        return true;
    }
    void show() { dialog.show(); }
}
