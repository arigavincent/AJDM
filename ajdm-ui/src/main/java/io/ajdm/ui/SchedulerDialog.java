package io.ajdm.ui;

import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * IDM's Scheduler for the main download queue: download only between two times (the window may cross midnight,
 * for night bundles), how many files at once and a daily data limit, plus Start now and Stop.
 */
final class SchedulerDialog {
    private final MainWindow main;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final CheckBox scheduled = new CheckBox("Download only between");
    private final TextField from = new TextField("23:00"), to = new TextField("06:00");
    private final Spinner<Integer> maxActive = new Spinner<>(1, 10, 3);
    private final CheckBox budgeted = new CheckBox("Stop when today's downloads reach");
    private final Spinner<Integer> dailyMiB = new Spinner<>(1, 1_048_576, 1024);
    private final Label state = new Label("Loading…");
    private JsonObject queue;

    SchedulerDialog(MainWindow main) {
        this.main = main;
        scheduled.setId("queue-scheduled"); from.setId("queue-start"); to.setId("queue-end"); maxActive.setId("queue-max-active");
        budgeted.setId("queue-budgeted"); dailyMiB.setId("queue-daily-mib"); dailyMiB.setEditable(true); dailyMiB.setPrefWidth(120);
        from.setPrefColumnCount(5); to.setPrefColumnCount(5);
        from.disableProperty().bind(scheduled.selectedProperty().not()); to.disableProperty().bind(scheduled.selectedProperty().not());
        dailyMiB.disableProperty().bind(budgeted.selectedProperty().not());
        var window = new HBox(8, scheduled, from, new Label("and"), to, new Label("(24-hour time)")); window.setAlignment(Pos.CENTER_LEFT);
        var simultaneous = new HBox(8, new Label("Download"), maxActive, new Label("files at the same time")); simultaneous.setAlignment(Pos.CENTER_LEFT);
        var budget = new HBox(8, budgeted, dailyMiB, new Label("MB, then continue tomorrow")); budget.setAlignment(Pos.CENTER_LEFT);
        var startNow = new Button("Start now"); startNow.setId("scheduler-start"); startNow.setOnAction(event -> main.resumeAll());
        var stop = new Button("Stop queue"); stop.setId("scheduler-stop"); stop.setOnAction(event -> main.pauseAll());
        state.setWrapText(true); state.getStyleClass().add("muted");
        var heading = new HBox(10, Icons.scheduler(34), new Label("Main download queue")); heading.setAlignment(Pos.CENTER_LEFT);
        heading.getChildren().get(1).getStyleClass().add("options-heading");
        var content = new VBox(14, heading, window, simultaneous, budget, new HBox(8, startNow, stop), state);
        content.setPadding(new Insets(6)); content.setPrefWidth(560);
        dialog.initOwner(main.stage()); dialog.setTitle("Scheduler"); dialog.getDialogPane().setContent(content); dialog.getDialogPane().setId("scheduler-dialog");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CLOSE);
        var apply = (Button) dialog.getDialogPane().lookupButton(ButtonType.APPLY); apply.setId("scheduler-apply"); apply.setDisable(true);
        apply.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { event.consume(); apply(); });
        MainWindow.style(dialog);
        main.model().request("queueStatus", new JsonObject(), status -> {
            queue = status; apply.setDisable(false);
            maxActive.getValueFactory().setValue(status.get("maxActive").getAsInt());
            scheduled.setSelected(status.get("scheduled").getAsBoolean());
            from.setText(status.get("start").getAsString()); to.setText(status.get("end").getAsString());
            budgeted.setSelected(status.get("budgeted").getAsBoolean());
            dailyMiB.getValueFactory().setValue((int) Math.min(1_048_576, status.get("dailyMiB").getAsLong()));
            state.setText(MainWindow.queueText(status));
        }, error -> state.setText("The queue settings are unavailable: " + error));
    }
    private void apply() {
        if (queue == null) return;
        var next = queue.deepCopy();
        next.addProperty("maxActive", maxActive.getValue()); next.addProperty("scheduled", scheduled.isSelected());
        next.addProperty("start", from.getText().strip()); next.addProperty("end", to.getText().strip());
        next.addProperty("budgeted", budgeted.isSelected()); next.addProperty("dailyMiB", dailyMiB.getValue());
        long limit = queue.has("limitKiBps") ? queue.get("limitKiBps").getAsLong() : 0;
        main.configureQueue(next, (int) limit, () -> {
            state.setText("Saved. " + (scheduled.isSelected() ? "Downloads run between " + from.getText().strip() + " and " + to.getText().strip() + "." : "Downloads run any time."));
            main.model().request("queueStatus", new JsonObject(), status -> { queue = status; }, error -> { });
        });
    }
    void show() { dialog.show(); }
}
