package io.ajdm.ui;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;

/**
 * Tray icon (Open, Pause all, Resume all, Quit) and completion pop-ups. Desktops without a system tray, common on
 * GNOME and Wayland, still get pop-ups through the freedesktop notify-send command when it exists; otherwise
 * notifications are silently skipped. Menu actions run on the JavaFX thread.
 */
final class DesktopNotifier implements AutoCloseable {
    private static final Path NOTIFY_SEND = Path.of("/usr/bin/notify-send");
    private final AtomicReference<TrayIcon> tray = new AtomicReference<>();

    /** Installs the tray icon when the platform has one; {@link #trayAvailable()} tells whether it did. */
    DesktopNotifier(Runnable open, Runnable pauseAll, Runnable resumeAll, Runnable quit) {
        if (GraphicsEnvironment.isHeadless()) return;
        try {
            EventQueue.invokeAndWait(() -> {
                if (!SystemTray.isSupported()) return;
                var image = java.awt.Toolkit.getDefaultToolkit().getImage(AjdmApp.class.getResource("ajdm-logo.png"));
                var menu = new PopupMenu();
                menu.add(item("Open AJDM", open)); menu.addSeparator();
                menu.add(item("Pause all", pauseAll)); menu.add(item("Resume all", resumeAll)); menu.addSeparator();
                menu.add(item("Quit AJDM", quit));
                var icon = new TrayIcon(image, "AJDM", menu);
                icon.setImageAutoSize(true);
                icon.addActionListener(event -> Platform.runLater(open));
                try { SystemTray.getSystemTray().add(icon); tray.set(icon); }
                catch (java.awt.AWTException unavailable) { /* No usable tray on this desktop. */ }
            });
        } catch (Exception unavailable) { /* AJDM still works without a tray. */ }
    }

    boolean trayAvailable() { return tray.get() != null; }

    void tooltip(String text) {
        var icon = tray.get();
        if (icon != null) EventQueue.invokeLater(() -> icon.setToolTip(text));
    }

    void notify(String title, String body, boolean failure) {
        var icon = tray.get();
        if (icon != null) {
            EventQueue.invokeLater(() -> icon.displayMessage(title, body, failure ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO));
        } else if (Files.isExecutable(NOTIFY_SEND)) {
            // Fixed arguments, no shell: the file name is passed as data.
            try {
                new ProcessBuilder(NOTIFY_SEND.toString(), "--app-name=AJDM", failure ? "--urgency=normal" : "--urgency=low", title, body)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (java.io.IOException unavailable) { /* No notification service. */ }
        }
    }

    @Override public void close() {
        var icon = tray.getAndSet(null);
        if (icon != null) EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(icon));
    }

    private static MenuItem item(String label, Runnable action) {
        var item = new MenuItem(label);
        item.addActionListener(event -> Platform.runLater(action));
        return item;
    }
}
