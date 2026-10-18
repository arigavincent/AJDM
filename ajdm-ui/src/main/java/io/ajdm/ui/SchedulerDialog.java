package io.ajdm.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * IDM's Scheduler: the queues on the left (Main download queue and any you create), the chosen queue's download
 * window and days on the right, Start now and Stop for it, and what to do when all downloads finish. The Main queue
 * also holds the limits all queues share: files at the same time and the daily data limit.
 */
final class SchedulerDialog {
    private static final String MAIN = "Main download queue";
    private final MainWindow main;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ListView<String> queues = new ListView<>();
    private final CheckBox scheduled = new CheckBox("Download only between");
    private final TextField from = new TextField("23:00"), to = new TextField("06:00");
    private final Map<DayOfWeek, CheckBox> days = new EnumMap<>(DayOfWeek.class);
    private final Spinner<Integer> maxActive = new Spinner<>(1, 10, 3);
    private final CheckBox budgeted = new CheckBox("Stop when today's downloads reach");
    private final Spinner<Integer> dailyMiB = new Spinner<>(1, 1_048_576, 1024);
    private final VBox shared;
    private final Label state = new Label("Loading…");
    private final ComboBox<AfterQueue> after = new ComboBox<>();
    private JsonObject mainStatus;
    private final Map<String, JsonObject> named = new java.util.LinkedHashMap<>();
    private String shown;

    SchedulerDialog(MainWindow main) {
        this.main = main;
        queues.setId("scheduler-queues"); queues.setPrefWidth(190);
        scheduled.setId("queue-scheduled"); from.setId("queue-start"); to.setId("queue-end"); maxActive.setId("queue-max-active");
        budgeted.setId("queue-budgeted"); dailyMiB.setId("queue-daily-mib"); dailyMiB.setEditable(true); dailyMiB.setPrefWidth(120);
        from.setPrefColumnCount(5); to.setPrefColumnCount(5);
        from.disableProperty().bind(scheduled.selectedProperty().not()); to.disableProperty().bind(scheduled.selectedProperty().not());
        dailyMiB.disableProperty().bind(budgeted.selectedProperty().not());
        var dayRow = new HBox(6); dayRow.setAlignment(Pos.CENTER_LEFT); dayRow.getChildren().add(new Label("On"));
        for (var day : DayOfWeek.values()) {
            var box = new CheckBox(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)); box.setId("day-" + day.name().toLowerCase(Locale.ROOT));
            box.disableProperty().bind(scheduled.selectedProperty().not()); days.put(day, box); dayRow.getChildren().add(box);
        }
        var dayNote = new Label("No day ticked means every day. A night window counts for the day it starts."); dayNote.getStyleClass().add("muted");
        var window = new HBox(8, scheduled, from, new Label("and"), to); window.setAlignment(Pos.CENTER_LEFT);
        var simultaneous = new HBox(8, new Label("Download"), maxActive, new Label("files at the same time (all queues)")); simultaneous.setAlignment(Pos.CENTER_LEFT);
        var budget = new HBox(8, budgeted, dailyMiB, new Label("MB, then continue tomorrow")); budget.setAlignment(Pos.CENTER_LEFT);
        shared = new VBox(10, new Separator(), simultaneous, budget);
        var startNow = new Button("Start now"); startNow.setId("scheduler-start"); startNow.setOnAction(event -> main.queueCommand("startQueue", queueKey()));
        var stop = new Button("Stop queue"); stop.setId("scheduler-stop"); stop.setOnAction(event -> main.queueCommand("stopQueue", queueKey()));
        after.getItems().setAll(AfterQueue.values()); after.setValue(main.afterQueue()); after.setId("after-queue");
        after.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(AfterQueue value) { return value == null ? "" : value.label; }
            @Override public AfterQueue fromString(String text) { return null; }
        });
        after.valueProperty().addListener((o, a, b) -> { if (b != null && b != main.afterQueue()) main.afterQueue(b); });
        var afterRow = new HBox(8, new Label("When all downloads finish"), after); afterRow.setAlignment(Pos.CENTER_LEFT);
        state.setWrapText(true); state.getStyleClass().add("muted");
        var heading = new Label(); heading.getStyleClass().add("options-heading"); heading.setId("scheduler-heading");
        var settings = new VBox(12, heading, window, dayRow, dayNote, shared, new HBox(8, startNow, stop), new Separator(), afterRow, state);
        settings.setPadding(new Insets(0, 0, 0, 14)); HBox.setHgrow(settings, Priority.ALWAYS);
        var add = new Button("New queue…"); add.setId("scheduler-new"); add.setOnAction(event -> newQueue());
        var remove = new Button("Delete"); remove.setId("scheduler-delete"); remove.setOnAction(event -> deleteQueue());
        var left = new VBox(8, queues, new HBox(6, add, remove)); VBox.setVgrow(queues, Priority.ALWAYS);
        var content = new HBox(left, settings); content.setPrefSize(820, 380);
        queues.getSelectionModel().selectedItemProperty().addListener((o, previous, next) -> {
            if (previous != null && shown != null) remember();
            if (next != null) { show(next); heading.setText(next); remove.setDisable(next.equals(MAIN)); }
        });
        dialog.initOwner(main.stage()); dialog.setTitle("Scheduler"); dialog.getDialogPane().setContent(content); dialog.getDialogPane().setId("scheduler-dialog");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CLOSE);
        var apply = (Button) dialog.getDialogPane().lookupButton(ButtonType.APPLY); apply.setId("scheduler-apply"); apply.setDisable(true);
        apply.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { event.consume(); apply(); });
        MainWindow.style(dialog);
        load(apply);
    }
    private String queueKey() { var name = queues.getSelectionModel().getSelectedItem(); return name == null || name.equals(MAIN) ? "" : name; }
    private void load(Button apply) {
        main.model().request("queueStatus", new JsonObject(), status -> {
            mainStatus = status;
            main.model().request("queues", new JsonObject(), result -> {
                named.clear();
                for (var element : result.getAsJsonArray("queues")) { var queue = element.getAsJsonObject(); named.put(queue.get("name").getAsString(), queue); }
                var items = new java.util.ArrayList<String>(); items.add(MAIN); items.addAll(named.keySet());
                String keep = queues.getSelectionModel().getSelectedItem();
                shown = null; queues.getItems().setAll(items);
                queues.getSelectionModel().select(keep != null && items.contains(keep) ? keep : MAIN);
                state.setText(MainWindow.queueText(status)); apply.setDisable(false);
            }, error -> state.setText("Queues are unavailable: " + error));
        }, error -> state.setText("The queue settings are unavailable: " + error));
    }
    /** Fills the form from the chosen queue. */
    private void show(String name) {
        shown = name;
        var source = name.equals(MAIN) ? mainStatus : named.get(name);
        if (source == null) return;
        scheduled.setSelected(source.get("scheduled").getAsBoolean());
        from.setText(source.get("start").getAsString()); to.setText(source.get("end").getAsString());
        var chosen = new java.util.HashSet<String>();
        if (source.has("days")) for (var day : source.getAsJsonArray("days")) chosen.add(day.getAsString());
        days.forEach((day, box) -> box.setSelected(chosen.contains(day.name())));
        shared.setVisible(name.equals(MAIN)); shared.setManaged(name.equals(MAIN));
        if (name.equals(MAIN)) {
            maxActive.getValueFactory().setValue(mainStatus.get("maxActive").getAsInt());
            budgeted.setSelected(mainStatus.get("budgeted").getAsBoolean());
            dailyMiB.getValueFactory().setValue((int) Math.min(1_048_576, mainStatus.get("dailyMiB").getAsLong()));
        }
    }
    /** Keeps the edits of the queue being left, so switching queues before Apply loses nothing. */
    private void remember() {
        var target = shown.equals(MAIN) ? mainStatus : named.get(shown);
        if (target == null) return;
        target.addProperty("scheduled", scheduled.isSelected()); target.addProperty("start", from.getText().strip()); target.addProperty("end", to.getText().strip());
        target.add("days", chosenDays());
        if (shown.equals(MAIN)) {
            target.addProperty("maxActive", maxActive.getValue()); target.addProperty("budgeted", budgeted.isSelected()); target.addProperty("dailyMiB", dailyMiB.getValue());
        }
    }
    private JsonArray chosenDays() { var list = new JsonArray(); days.forEach((day, box) -> { if (box.isSelected()) list.add(day.name()); }); return list; }
    private void apply() {
        if (mainStatus == null || shown == null) return;
        remember();
        var queue = new JsonObject();
        for (String key : new String[]{"maxActive", "scheduled", "start", "end", "budgeted", "dailyMiB"}) queue.add(key, mainStatus.get(key));
        queue.addProperty("limitKiBps", mainStatus.has("limitKiBps") ? mainStatus.get("limitKiBps").getAsLong() : 0);
        queue.add("days", mainStatus.has("days") ? mainStatus.getAsJsonArray("days") : new JsonArray());
        main.model().request("configureQueue", queue, configured -> {
            for (var entry : named.values()) {
                var save = new JsonObject();
                for (String key : new String[]{"name", "scheduled", "start", "end", "days"}) save.add(key, entry.get(key));
                main.model().request("saveQueue", save, saved -> { }, failure -> state.setText(entry.get("name").getAsString() + ": " + failure));
            }
            state.setText("Saved."); main.refreshQueue();
        }, failure -> state.setText("Not saved: " + failure));
    }
    private void newQueue() {
        var ask = new TextInputDialog(); ask.initOwner(dialog.getDialogPane().getScene().getWindow()); ask.setTitle("New queue");
        ask.setHeaderText("Name the new queue"); ask.getEditor().setPromptText("Night films"); MainWindow.style(ask);
        ask.showAndWait().map(String::strip).filter(name -> !name.isEmpty()).ifPresent(name -> {
            var save = new JsonObject(); save.addProperty("name", name); save.addProperty("scheduled", false);
            save.addProperty("start", "23:00"); save.addProperty("end", "06:00"); save.add("days", new JsonArray());
            main.model().request("saveQueue", save, saved -> {
                if (shown != null) remember();
                named.put(name, save); queues.getItems().add(name); queues.getSelectionModel().select(name); main.refreshQueue();
            }, failure -> state.setText("Use 1 to 40 letters, digits, spaces or . _ - ( ) — and not the name Main."));
        });
    }
    private void deleteQueue() {
        String name = queueKey(); if (name.isEmpty()) return;
        var payload = new JsonObject(); payload.addProperty("name", name);
        main.model().request("deleteQueue", payload, deleted -> {
            named.remove(name); shown = null; queues.getItems().remove(name); queues.getSelectionModel().select(MAIN);
            state.setText("\"" + name + "\" deleted; its downloads are back in the Main download queue."); main.refreshQueue();
        }, failure -> state.setText(failure));
    }
    void show() { dialog.show(); }
}
