package io.ajdm.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiSettingsTest {
    @TempDir Path temp;

    @Test void settingsSurviveARestartInAnOwnerOnlyFileBesideTheCoreEndpoint() throws Exception {
        Path file = UiSettings.file(temp.resolve("profile/core.endpoint"));
        assertEquals(temp.resolve("profile/ui-settings.properties"), file);
        var missing = UiSettings.load(file, temp.resolve("Downloads"));
        assertEquals(UiSettings.defaults(temp.resolve("Downloads")), missing);
        var chosen = new UiSettings.Values(temp.resolve("Movies"), 24, 6, true, false, true, "firefox", null);
        UiSettings.save(file, chosen);
        assertEquals(chosen, UiSettings.load(file, temp.resolve("Downloads")));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        var cookies = chosen.withMedia(null, temp.resolve("cookies.txt"));
        UiSettings.save(file, cookies);
        assertEquals(temp.resolve("cookies.txt"), UiSettings.load(file, temp).cookieFile());
        assertFalse(Files.readString(file).contains("firefox"), "A cleared browser choice is not kept");
    }

    @Test void damagedOrForeignValuesFallBackInsteadOfBlockingTheWindow() throws Exception {
        Path file = temp.resolve("ui-settings.properties");
        Files.writeString(file, "directory=relative/path\nconnections=999\nmediaConnections=abc\ndark=yes\nmediaBrowser=evil;rm\ncookieFile=/tmp/c.txt\n");
        var loaded = UiSettings.load(file, temp.resolve("Downloads"));
        assertEquals(temp.resolve("Downloads"), loaded.directory());
        assertEquals(16, loaded.connections()); assertEquals(8, loaded.mediaConnections());
        assertFalse(loaded.dark()); assertNull(loaded.mediaBrowser());
        assertEquals(Path.of("/tmp/c.txt"), loaded.cookieFile());
        Files.writeString(file, "mediaBrowser=chrome\ncookieFile=/tmp/c.txt\n");
        var both = UiSettings.load(file, temp);
        assertEquals("chrome", both.mediaBrowser()); assertNull(both.cookieFile(), "A browser choice wins over a stale cookie file");
        assertThrows(IllegalArgumentException.class, () -> new UiSettings.Values(temp, 0, 8, false, true, false, null, null));
    }
}
