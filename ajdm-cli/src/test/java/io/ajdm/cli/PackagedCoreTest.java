package io.ajdm.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** The packaged core owns bootstrap; it must refuse to run outside its native launcher. */
@Timeout(30)
class PackagedCoreTest {
    @TempDir Path temp;
    @Test void rejectsDesktopOnlyAndUnknownOptions() {
        assertThrows(IllegalArgumentException.class, () -> PackagedCore.main(new String[] {"--core"}));
        assertThrows(IllegalArgumentException.class, () -> PackagedCore.main(new String[] {"--close-after", "1"}));
    }
    @Test void requiresTheInstalledLauncherAndPreparesAPrivateProfile() {
        Assumptions.assumeTrue(System.getProperty("jpackage.app-path") == null);
        Path profile = temp.resolve("profile");
        var failure = assertThrows(NullPointerException.class, () -> PackagedCore.main(new String[] {"--profile", profile.toString()}));
        assertEquals("Use the installed AJDM-core launcher", failure.getMessage());
        assertTrue(Files.isDirectory(profile));
        assertFalse(Files.exists(profile.resolve("core.endpoint")));
    }
    @Test void defaultsToTheVersionedPreviewProfile() {
        assertEquals("v1-preview", PackagedCore.defaultProfile().getFileName().toString());
    }
}
