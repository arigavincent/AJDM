package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.nio.file.Path;

/**
 * Options > Video sites: sign-in for private or age-restricted videos, through a signed-in browser or an exported
 * cookies.txt. Applied at once and remembered; only the browser name or file path is saved, never cookies.
 */
final class MediaAccess {
    private final MainWindow main;
    MediaAccess(MainWindow main) { this.main = main; }

    Node content() {
        var settings = main.currentSettings();
        var browser = new ComboBox<String>(); browser.setId("media-browser"); browser.setPromptText("Choose your signed-in browser");
        browser.getItems().setAll("chrome", "edge", "firefox", "brave", "chromium", "opera", "vivaldi", "whale", "safari");
        if (settings.mediaBrowser() != null) browser.setValue(settings.mediaBrowser());
        var useBrowser = new Button("Use this browser"); useBrowser.setId("configure-media-browser");
        var cookies = new TextField(); cookies.setPromptText("Netscape cookies.txt file"); cookies.setId("media-cookie-file");
        if (settings.cookieFile() != null) cookies.setText(settings.cookieFile().toString());
        var chooseCookies = new Button("…"); chooseCookies.setOnAction(event -> {
            var chooser = new javafx.stage.FileChooser(); chooser.setTitle("Choose exported cookies");
            var file = chooser.showOpenDialog(main.stage()); if (file != null) cookies.setText(file.toPath().toString());
        });
        var useCookies = new Button("Use this file"); useCookies.setId("configure-media-cookies");
        var clear = new Button("Stop using sign-in"); clear.setId("clear-media-cookies");
        var status = new Label("Remembered and applied again when AJDM starts."); status.setWrapText(true); status.setId("media-session-status");
        var busy = new SimpleBooleanProperty(false);
        useBrowser.disableProperty().bind(browser.valueProperty().isNull().or(busy)); useCookies.disableProperty().bind(busy); clear.disableProperty().bind(busy);
        useBrowser.setOnAction(event -> {
            var request = new JsonObject(); request.addProperty("browser", browser.getValue()); String chosen = browser.getValue(); busy.set(true);
            main.model().request("mediaCookies", request, result -> {
                busy.set(false); cookies.clear(); main.update(main.currentSettings().withMedia(chosen, null));
                status.setText("AJDM reads that browser's cookies only to analyse videos and renew their links. Sign in to the site there, then try again.");
            }, error -> { busy.set(false); status.setText(error); });
        });
        useCookies.setOnAction(event -> {
            if (cookies.getText().isBlank()) { status.setText("Choose a cookies.txt file first."); return; }
            var request = new JsonObject(); request.addProperty("file", cookies.getText().strip()); busy.set(true);
            main.model().request("mediaCookies", request, result -> {
                busy.set(false); browser.setValue(null); main.update(main.currentSettings().withMedia(null, Path.of(cookies.getText().strip())));
                status.setText("Cookie file in use for videos.");
            }, error -> { busy.set(false); status.setText(error); });
        });
        clear.setOnAction(event -> {
            busy.set(true);
            main.model().request("mediaCookies", new JsonObject(), result -> {
                busy.set(false); cookies.clear(); browser.setValue(null); main.update(main.currentSettings().withMedia(null, null)); status.setText("Sign-in cleared.");
            }, error -> { busy.set(false); status.setText(error); });
        });
        var intro = new Label("Public videos need nothing here. For private, members-only or age-restricted videos, let AJDM use the browser where you are signed in.");
        intro.setWrapText(true);
        var tiktok = new Label("Public TikTok links may also use the TikWM/Douyin resolvers; your cookies are never sent to them.");
        tiktok.setWrapText(true); tiktok.getStyleClass().add("muted");
        var heading = new Label("Signed-in videos"); heading.getStyleClass().add("options-heading");
        var box = new VBox(12, heading, intro, new HBox(8, browser, useBrowser), new Label("Or use an exported cookie file"),
                new HBox(8, cookies, chooseCookies, useCookies), clear, status, tiktok);
        HBox.setHgrow(cookies, javafx.scene.layout.Priority.ALWAYS);
        box.setPadding(new Insets(16));
        return box;
    }
}
