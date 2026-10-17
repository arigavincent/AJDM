package io.ajdm.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Desktop preferences that survive restarts, in a private file beside the core endpoint. Only choices are stored:
 * the media browser name or cookie-file path, never cookie contents. Unreadable or invalid entries fall back to
 * defaults instead of blocking the window.
 */
final class UiSettings {
    static final Set<String> BROWSERS = Set.of("firefox", "chrome", "chromium", "brave", "edge", "opera", "vivaldi", "whale", "safari");

    record Values(Path directory, int connections, int mediaConnections, boolean dark, boolean notifications,
                  boolean keepInTray, String mediaBrowser, Path cookieFile) {
        Values {
            if (connections < 1 || connections > 32 || mediaConnections < 1 || mediaConnections > 32) throw new IllegalArgumentException("Invalid connections");
            if (mediaBrowser != null && !BROWSERS.contains(mediaBrowser)) throw new IllegalArgumentException("Unknown browser");
            if (mediaBrowser != null && cookieFile != null) throw new IllegalArgumentException("Choose a browser or a cookie file");
            directory = directory.toAbsolutePath().normalize();
        }
        Values withMedia(String browser, Path cookies) {
            return new Values(directory, connections, mediaConnections, dark, notifications, keepInTray, browser, cookies);
        }
    }

    private UiSettings() {}

    /** The tray keeps AJDM running after its window closes only where tray icons are reliably visible (Windows). */
    static Values defaults(Path directory) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return new Values(directory, 16, 8, false, true, windows, null, null);
    }

    static Path file(Path socket) { return socket.toAbsolutePath().normalize().resolveSibling("ui-settings.properties"); }

    static Values load(Path file, Path fallbackDirectory) {
        var defaults = defaults(fallbackDirectory);
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
        catch (IOException | IllegalArgumentException missingOrUnreadable) { return defaults; }
        Path directory = path(properties.getProperty("directory"), defaults.directory());
        int connections = number(properties.getProperty("connections"), defaults.connections());
        int media = number(properties.getProperty("mediaConnections"), defaults.mediaConnections());
        String browser = properties.getProperty("mediaBrowser");
        if (browser != null && !BROWSERS.contains(browser)) browser = null;
        Path cookies = browser != null ? null : path(properties.getProperty("cookieFile"), null);
        return new Values(directory, connections, media, flag(properties, "dark", defaults.dark()),
                flag(properties, "notifications", defaults.notifications()), flag(properties, "keepInTray", defaults.keepInTray()), browser, cookies);
    }

    static void save(Path file, Values values) throws IOException {
        var properties = new Properties();
        properties.setProperty("directory", values.directory().toString());
        properties.setProperty("connections", Integer.toString(values.connections()));
        properties.setProperty("mediaConnections", Integer.toString(values.mediaConnections()));
        properties.setProperty("dark", Boolean.toString(values.dark()));
        properties.setProperty("notifications", Boolean.toString(values.notifications()));
        properties.setProperty("keepInTray", Boolean.toString(values.keepInTray()));
        if (values.mediaBrowser() != null) properties.setProperty("mediaBrowser", values.mediaBrowser());
        if (values.cookieFile() != null) properties.setProperty("cookieFile", values.cookieFile().toString());
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) { properties.store(out, "AJDM desktop settings"); }
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path path(String value, Path fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { Path path = Path.of(value); return path.isAbsolute() ? path.normalize() : fallback; }
        catch (InvalidPathException e) { return fallback; }
    }
    private static int number(String value, int fallback) {
        try { int parsed = Integer.parseInt(value == null ? "" : value.strip()); return parsed >= 1 && parsed <= 32 ? parsed : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }
    private static boolean flag(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.strip());
    }
}
