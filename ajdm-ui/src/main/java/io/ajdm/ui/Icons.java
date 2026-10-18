package io.ajdm.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * Vector icons in IDM's spirit: colourful toolbar glyphs on a 24-unit grid and file-type badges. Drawn from shapes so
 * they stay sharp at any display scale and need no image files.
 */
final class Icons {
    private Icons() { }

    // Toolbar ------------------------------------------------------------------------------------------------------
    static Node addUrl(double size) { return badge(size, "#3B8BF5", "#1F64D6", path("M11 6h2v5h5v2h-5v5h-2v-5H6v-2h5z", Color.WHITE)); }
    static Node resume(double size) { return badge(size, "#3CC46A", "#1E9548", path("M9.5 7.2 17.5 12l-8 4.8z", Color.WHITE)); }
    static Node stop(double size) { return badge(size, "#FF8A3D", "#E25A12", path("M8.5 8.5h7v7h-7z", Color.WHITE)); }
    static Node stopAll(double size) {
        return scaled(size, new Group(path("M8 2h8l6 6v8l-6 6H8l-6-6V8z", gradient("#F25555", "#C62828")),
                path("M6.5 10.8h11v2.4h-11z", Color.WHITE)));
    }
    static Node delete(double size) {
        return badge(size, "#F05252", "#C81E1E",
                path("M8.6 7.2 12 10.6l3.4-3.4 1.4 1.4-3.4 3.4 3.4 3.4-1.4 1.4-3.4-3.4-3.4 3.4-1.4-1.4 3.4-3.4-3.4-3.4z", Color.WHITE));
    }
    static Node deleteCompleted(double size) {
        return scaled(size, new Group(path("M5.5 7h13l-1.1 14.2a1 1 0 0 1-1 .8H7.6a1 1 0 0 1-1-.8zM9 3.5h6l.8 1.7H20v1.6H4V5.2h4.2z", gradient("#94A3B8", "#5B6B82")),
                new Circle(17.5, 17.5, 5, gradient("#3CC46A", "#1E9548")), path("M15.1 17.4l1.6 1.6 3-3.1-.9-.9-2.1 2.2-.7-.7z", Color.WHITE)));
    }
    static Node options(double size) {
        return scaled(size, path("M19.14 12.94c.04-.31.06-.63.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
                gradient("#8796AB", "#4F5E73")));
    }
    static Node scheduler(double size) {
        return scaled(size, new Group(new Circle(12, 12, 10, gradient("#9B7BEA", "#6A45C9")), new Circle(12, 12, 7.6, Color.WHITE),
                path("M11.2 7h1.6v4.7l3.3 2-.8 1.3-4.1-2.5z", Color.web("#6A45C9"))));
    }
    static Node startQueue(double size) {
        return scaled(size, new Group(path("M3 5h12v2.2H3zm0 4.6h12v2.2H3zm0 4.6h7.5v2.2H3z", gradient("#94A3B8", "#5B6B82")),
                path("M13.5 12.5 22 17.3l-8.5 4.8z", gradient("#3CC46A", "#1E9548"))));
    }
    static Node stopQueue(double size) {
        return scaled(size, new Group(path("M3 5h12v2.2H3zm0 4.6h12v2.2H3zm0 4.6h7.5v2.2H3z", gradient("#94A3B8", "#5B6B82")),
                path("M14 13.5h7.5V21H14z", gradient("#F25555", "#C62828"))));
    }
    static Node speed(double size) {
        return scaled(size, new Group(path("M12 4.5A10 10 0 0 0 3.3 19.5h17.4A10 10 0 0 0 12 4.5zm0 2.2a7.8 7.8 0 0 1 7.3 10.6H4.7A7.8 7.8 0 0 1 12 6.7z", gradient("#2BB5A8", "#138276")),
                path("M16.6 8.6 12.9 14a1.6 1.6 0 1 1-1.8-1.3z", gradient("#F2A93B", "#D9822B"))));
    }
    static Node grabber(double size) {
        return scaled(size, new Group(new Circle(10, 10, 7, gradient("#3B8BF5", "#1F64D6")), new Circle(10, 10, 4.6, Color.WHITE),
                path("M14.6 13.2l1.4-1.4 6 6-1.4 1.4z", gradient("#5B6B82", "#3A475A"))));
    }

    // Files and categories ----------------------------------------------------------------------------------------
    static String color(Categories category) {
        return switch (category) {
            case VIDEO -> "#E5484D"; case MUSIC -> "#8E4EC6"; case DOCUMENTS -> "#2D7FF9";
            case COMPRESSED -> "#E2A13B"; case PROGRAMS -> "#22A447"; case OTHER -> "#8A99AD";
        };
    }
    /** A page with a folded corner and a coloured band naming the file type. */
    static Node file(Categories category, double size) {
        Color band = Color.web(color(category));
        var sheet = path("M6 2.5h8.5L19 7v14.5H6z", Color.WHITE); sheet.setStroke(Color.web("#9AA8BA")); sheet.setStrokeWidth(1);
        var fold = path("M14.5 2.5V7H19", Color.TRANSPARENT); fold.setStroke(Color.web("#9AA8BA")); fold.setStrokeWidth(1);
        var label = new Rectangle(4, 12.5, 13, 6); label.setArcWidth(2); label.setArcHeight(2); label.setFill(band);
        return scaled(size, new Group(sheet, fold, label));
    }
    static Node folder(double size, String color) {
        return scaled(size, new Group(path("M2.5 6.5A1.5 1.5 0 0 1 4 5h5l2 2h9a1.5 1.5 0 0 1 1.5 1.5v10A1.5 1.5 0 0 1 20 20H4a1.5 1.5 0 0 1-1.5-1.5z", gradient(color, Color.web(color).darker().toString().replace("0x", "#")))));
    }
    static Node allDownloads(double size) {
        return scaled(size, new Group(path("M2.5 6.5A1.5 1.5 0 0 1 4 5h5l2 2h9a1.5 1.5 0 0 1 1.5 1.5v10A1.5 1.5 0 0 1 20 20H4a1.5 1.5 0 0 1-1.5-1.5z", gradient("#F4C25B", "#D9962A")),
                path("M11 9.5h2v4.3h2.3L12 17.3l-3.3-3.5H11z", Color.WHITE)));
    }
    static Node unfinished(double size) { return badge(size, "#FF8A3D", "#E25A12", path("M11 6h2v6.3l3.6 2.1-1 1.7L11 13.4z", Color.WHITE)); }
    static Node finished(double size) { return badge(size, "#3CC46A", "#1E9548", path("M10.2 15.6 6.7 12.1l1.4-1.4 2.1 2.1 5.6-5.6 1.4 1.4z", Color.WHITE)); }
    static Node queue(double size) { return scaled(size, path("M3 5h18v2.4H3zm0 5.3h18v2.4H3zm0 5.3h18V18H3z", gradient("#94A3B8", "#5B6B82"))); }
    /** The AJDM mark: a teal disc with a download arrow. */
    static Node logo(double size) {
        return scaled(size, new Group(new Circle(12, 12, 11, gradient("#25C3B5", "#118175")),
                path("M10.6 5.5h2.8v6.2h3.1L12 16.6l-4.5-4.9h3.1z", Color.WHITE), path("M6.5 17.6h11v1.9h-11z", Color.WHITE)));
    }

    // Building blocks ---------------------------------------------------------------------------------------------
    private static Node badge(double size, String top, String bottom, Node glyph) {
        var disc = new Circle(12, 12, 10.5, gradient(top, bottom));
        disc.setStroke(Color.web(bottom)); disc.setStrokeWidth(0.6);
        var shine = new Circle(12, 8.5, 7, new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 255, 255, 0.35)), new Stop(1, Color.rgb(255, 255, 255, 0))));
        return scaled(size, new Group(disc, shine, glyph));
    }
    private static SVGPath path(String content, Paint fill) { var path = new SVGPath(); path.setContent(content); path.setFill(fill); return path; }
    private static Paint gradient(String top, String bottom) {
        return new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, new Stop(0, Color.web(top)), new Stop(1, Color.web(bottom)));
    }
    /** Fits a 24-unit drawing into {@code size} pixels. */
    private static Node scaled(double size, Node drawing) {
        var frame = new Rectangle(0, 0, 24, 24); frame.setFill(Color.TRANSPARENT);
        var group = new Group(frame, drawing);
        group.setScaleX(size / 24); group.setScaleY(size / 24);
        var holder = new Group(group);
        holder.getStyleClass().add("icon");
        return new javafx.scene.layout.StackPane(holder) {{ setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size); }};
    }
}
