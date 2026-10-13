package io.ajdm.ui;

import io.ajdm.ipc.IpcClient;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.nio.file.Path;

public final class AjdmApp extends Application {
    private DownloadsViewModel model;
    @Override public void start(Stage stage) throws Exception {
        var options = getParameters().getRaw();
        Path socket = IpcClient.defaultSocket(), directory = Path.of(System.getProperty("user.home"), "Downloads");
        Path snapshot = null; int closeAfter = 0;
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (i + 1 >= options.size()) throw new IllegalArgumentException("Missing UI option value");
            String value = options.get(++i);
            switch (option) {
                case "--socket" -> socket = Path.of(value);
                case "--directory" -> directory = Path.of(value);
                case "--snapshot" -> snapshot = Path.of(value);
                case "--close-after" -> closeAfter = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("Unknown UI option");
            }
        }
        model = new DownloadsViewModel(socket);
        var window = new MainWindow(stage, model, socket, directory);
        var loader = new FXMLLoader(AjdmApp.class.getResource("main.fxml"));
        loader.setControllerFactory(type -> window);
        var scene = new Scene(loader.load(), 1240, 780);
        scene.getStylesheets().add(AjdmApp.class.getResource("ajdm.css").toExternalForm());
        stage.setTitle("AJDM · Ariga Justine Download Manager");
        stage.setMinWidth(1000); stage.setMinHeight(640); stage.setScene(scene); stage.show();
        stage.setMaximized(true);
        stage.setFullScreenExitHint("Press Esc to return to the desktop.");
        window.shortcuts(scene);
        if (snapshot != null) {
            stage.setFullScreen(true);
            Path output = snapshot;
            var delay = new PauseTransition(Duration.seconds(3));
            delay.setOnFinished(event -> {
                try { Snapshot.write(scene, output); }
                catch (java.io.IOException failure) { System.err.println("Could not save UI snapshot."); }
            });
            delay.play();
        }
        if (closeAfter > 0) {
            var delay = new PauseTransition(Duration.seconds(closeAfter));
            delay.setOnFinished(event -> stage.close()); delay.play();
        }
    }
    @Override public void stop() { if (model != null) model.close(); }
}
