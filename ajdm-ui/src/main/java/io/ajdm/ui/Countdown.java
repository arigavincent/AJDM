package io.ajdm.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * IDM's countdown before acting after the downloads finish: 60 seconds with Do it now and Cancel. The delay can be
 * shortened with -Dajdm.afterQueueSeconds and the command only recorded with -Dajdm.afterQueueDryRun (tests).
 */
final class Countdown {
    static volatile String lastCommand;
    private final Stage stage = new Stage();
    private final MainWindow main;
    private final AfterQueue action;
    private final javafx.animation.Timeline ticker;
    private int left = Integer.getInteger("ajdm.afterQueueSeconds", 60);

    Countdown(MainWindow main, AfterQueue action) {
        this.main = main; this.action = action;
        var title = new Label("All downloads have finished"); title.getStyleClass().add("complete-title");
        var message = new Label(); message.setId("countdown-message");
        var now = new Button("Do it now"); now.setId("countdown-now"); now.getStyleClass().add("primary-button");
        var cancel = new Button("Cancel"); cancel.setId("countdown-cancel"); cancel.setCancelButton(true); cancel.setDefaultButton(true);
        ticker = new javafx.animation.Timeline(new KeyFrame(Duration.seconds(1), event -> {
            left--; message.setText(text());
            if (left <= 0) run();
        }));
        ticker.setCycleCount(Animation.INDEFINITE);
        now.setOnAction(event -> run());
        cancel.setOnAction(event -> { ticker.stop(); stage.close(); main.tell(action.label + " canceled."); });
        message.setText(text());
        var buttons = new HBox(8, now, cancel); buttons.setAlignment(Pos.CENTER_RIGHT);
        var content = new VBox(12, new HBox(14, Icons.scheduler(40), new VBox(4, title, message)), buttons);
        content.setPadding(new Insets(16)); content.setId("countdown-dialog");
        main.style(stage, content);
        stage.setTitle("AJDM"); stage.setScene(new Scene(content)); stage.setResizable(false); stage.setAlwaysOnTop(true);
        stage.setOnHidden(event -> ticker.stop());
    }
    private String text() { return action.label + " in " + left + " second" + (left == 1 ? "" : "s") + "."; }
    private void run() {
        ticker.stop(); stage.close();
        if (action == AfterQueue.EXIT) { if (!Boolean.getBoolean("ajdm.afterQueueDryRun")) Platform.exit(); else lastCommand = "exit"; return; }
        var command = AfterQueue.command(action, System.getProperty("os.name", ""));
        if (Boolean.getBoolean("ajdm.afterQueueDryRun")) { lastCommand = String.join(" ", command); return; }
        main.model().localAction(() -> {
            try { new ProcessBuilder(command).inheritIO().start(); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }, failure -> main.tell("Could not " + action.label.toLowerCase(java.util.Locale.ROOT) + ". Your system may need you to do it yourself."));
    }
    void show() { stage.show(); stage.toFront(); ticker.play(); }
}
