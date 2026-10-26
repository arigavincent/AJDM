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
    @Test void findsBundledToolsInBothNativeLayoutsAndFallsBackToSystemFfmpegOnLinux() throws Exception {
        Path windows = temp.resolve("win"); Files.createDirectories(windows.resolve("app/tools"));
        assertEquals(windows.resolve("app/tools"), PackagedCore.tools(windows.resolve("AJDM-core.exe")));
        Path linux = temp.resolve("opt/ajdm"); Files.createDirectories(linux.resolve("bin")); Files.createDirectories(linux.resolve("lib/app/tools"));
        Path tools = PackagedCore.tools(linux.resolve("bin/AJDM-core"));
        assertEquals(linux.resolve("lib/app/tools"), tools);
        Assumptions.assumeFalse(PackagedCore.windows());
        Path ytDlp = Files.createFile(tools.resolve("yt-dlp")); ytDlp.toFile().setExecutable(true);
        assertEquals(ytDlp, PackagedCore.tool(tools, "yt-dlp"));
        assertEquals(Path.of("/usr/bin/ffmpeg"), PackagedCore.tool(tools, "ffmpeg"));
    }
}
