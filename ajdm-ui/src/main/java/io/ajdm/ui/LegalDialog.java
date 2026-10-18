package io.ajdm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shows one of the bundled documents (Terms of Use, Privacy Policy, third-party notices) as readable text. */
final class LegalDialog {
    enum Document {
        TERMS("Terms of Use", "TERMS_OF_USE.md"), PRIVACY("Privacy Policy", "PRIVACY_POLICY.md"), NOTICES("Third-party notices", "THIRD-PARTY-NOTICES.md");
        final String title, file;
        Document(String title, String file) { this.title = title; this.file = file; }
    }
    private final Dialog<Void> dialog = new Dialog<>();

    LegalDialog(MainWindow main, Document document) {
        var body = new VBox(8); body.setPadding(new Insets(4, 14, 14, 4)); body.setId("legal-body");
        String text;
        try (InputStream in = AjdmApp.class.getResourceAsStream("legal/" + document.file)) {
            text = in == null ? "This document is not included in this build." : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) { text = "This document could not be read."; }
        render(text, body);
        var scroller = new ScrollPane(body); scroller.setFitToWidth(true); scroller.setPrefSize(680, 560); scroller.getStyleClass().add("edge-to-edge");
        dialog.initOwner(main.stage()); dialog.setTitle(document.title); dialog.getDialogPane().setContent(scroller); dialog.getDialogPane().setId("legal-dialog");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); MainWindow.style(dialog);
    }
    /** A small Markdown subset: headings, bullets and paragraphs; emphasis markers are dropped. */
    static void render(String markdown, VBox into) {
        var paragraph = new StringBuilder();
        Runnable flush = () -> { if (!paragraph.isEmpty()) { into.getChildren().add(label(paragraph.toString(), "legal-text")); paragraph.setLength(0); } };
        for (String raw : markdown.split("\\R")) {
            String line = raw.strip().replace("**", "").replace("`", "");
            if (line.isEmpty()) { flush.run(); continue; }
            if (line.startsWith("# ")) { flush.run(); into.getChildren().add(label(line.substring(2), "about-title")); }
            else if (line.startsWith("## ") || line.startsWith("### ")) { flush.run(); var heading = label(line.replaceFirst("^#+ ", ""), "about-heading"); VBox.setMargin(heading, new Insets(8, 0, 0, 0)); into.getChildren().add(heading); }
            else if (line.startsWith("- ") || line.startsWith("* ")) { flush.run(); into.getChildren().add(label("•  " + line.substring(2), "legal-text")); }
            else { if (!paragraph.isEmpty()) paragraph.append(' '); paragraph.append(line); }
        }
        flush.run();
    }
    private static Label label(String text, String style) { var label = new Label(text); label.setWrapText(true); label.getStyleClass().add(style); label.setMaxWidth(640); return label; }
    void show() { dialog.show(); }
}
