package io.ajdm.media;

import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class BrowserSessionTest {
    @TempDir Path temp;
    private YtDlpExtractor tool() {
        return new YtDlpExtractor(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("ajdm.media.test.classpath"), FakeExtractor.class.getName()), Duration.ofSeconds(3), 200_000);
    }
    @Test void browserAccessRequiresExplicitSelectionAndWritesOnlyDeletedPrivateCookieCopy() throws Exception {
        try (var tool = tool()) {
            var page = URI.create("https://www.instagram.com/reel/ok/");
            assertFalse(tool.extract(page, () -> false).title().contains("--cookies-from-browser"));
            tool.configureBrowser(MediaBrowser.FIREFOX);
            String args = tool.extract(page, () -> false).title();
            var parts = List.of(args.split("\\|"));
            assertEquals("firefox", parts.get(parts.indexOf("--cookies-from-browser") + 1));
            assertFalse(Files.exists(Path.of(parts.get(parts.indexOf("--cookies") + 1))));
            assertTrue(args.contains("--simulate")); assertTrue(args.contains("--skip-download"));
            tool.configureBrowser(null);
            assertFalse(tool.extract(page, () -> false).title().contains("--cookies-from-browser"));
        }
    }
    @Test void selectingFileReplacesBrowserAndSelectingBrowserReplacesFile() throws Exception {
        Path file = temp.resolve("original.txt");
        String cookies = "# Netscape HTTP Cookie File\n.test\tTRUE\t/\tTRUE\t4102444800\ttest-cookie\tsecret\n";
        Files.writeString(file, cookies);
        try (var tool = tool()) {
            tool.configureBrowser(MediaBrowser.CHROME); tool.configureCookies(file);
            assertFalse(tool.extract(URI.create("https://site.test/ok"), () -> false).title().contains("--cookies-from-browser"));
            tool.configureBrowser(MediaBrowser.FIREFOX);
            assertTrue(tool.extract(URI.create("https://site.test/ok"), () -> false).title().contains("--cookies-from-browser|firefox"));
            assertEquals(cookies, Files.readString(file));
        }
    }
    @Test void browserNamesRejectProfilesPathsOptionsAndUnsupportedNames() throws Exception {
        assertEquals(MediaBrowser.FIREFOX, MediaBrowser.parse("firefox"));
        for (String browser : List.of("firefox:secret", "chrome+basictext", "--exec", "/tmp/cookies", "unknown", "", "firefox\n"))
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class, () -> MediaBrowser.parse(browser)).code());
    }
    @Test void siteErrorsHaveSafeActionableCatalogMessagesWithoutRawDiagnostics() throws Exception {
        try (var tool = tool()) {
            for (String site : List.of("www.facebook.com", "www.instagram.com")) {
                var access = assertThrows(DownloadException.class, () -> tool.extract(URI.create("https://" + site + "/access-blocked?token=secret"), () -> false));
                assertEquals(ErrorCode.MEDIA_ACCESS_BLOCKED, access.code());
                assertTrue(access.userMessage().contains("Settings")); assertFalse(access.userMessage().contains("secret"));
                var auth = assertThrows(DownloadException.class, () -> tool.extract(URI.create("https://" + site + "/auth-blocked?token=secret"), () -> false));
                assertEquals(ErrorCode.MEDIA_AUTH_REQUIRED, auth.code()); assertFalse(auth.userMessage().contains("secret"));
            }
            assertEquals(ErrorCode.EXTRACTION_FAILED, assertThrows(DownloadException.class,
                    () -> tool.extract(URI.create("https://notinstagram.com/access-blocked"), () -> false)).code());
        }
    }
    @Test void desktopKeyringEnvironmentIsPassedOnlyForAnExplicitBrowserAndOtherSecretsStayIsolated() {
        var inherited = Map.of("HOME", "/home/test", "PATH", "/bin", "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus",
                "XDG_CURRENT_DESKTOP", "GNOME", "XDG_RUNTIME_DIR", "/run/user/1000", "AWS_SECRET_ACCESS_KEY", "secret");
        assertEquals(Map.of("HOME", "/home/test", "PATH", "/bin"), YtDlpExtractor.environment(inherited, false));
        var browser = YtDlpExtractor.environment(inherited, true);
        assertEquals("GNOME", browser.get("XDG_CURRENT_DESKTOP"));
        assertEquals("unix:path=/run/user/1000/bus", browser.get("DBUS_SESSION_BUS_ADDRESS"));
        assertEquals("/run/user/1000", browser.get("XDG_RUNTIME_DIR"));
        assertFalse(browser.containsKey("AWS_SECRET_ACCESS_KEY"));
    }
    @Test void browserDatabaseFailuresOfferAFileAlternativeAndNeverExposePrivatePaths() throws Exception {
        try (var tool = tool()) {
            tool.configureBrowser(MediaBrowser.CHROME);
            var failure = assertThrows(DownloadException.class, () -> tool.extract(URI.create("https://site.test/session-blocked"), () -> false));
            assertEquals(ErrorCode.MEDIA_SESSION_FAILED, failure.code()); assertTrue(failure.userMessage().contains("cookie file"));
            assertFalse(failure.userMessage().contains("/private/secret"));
        }
    }
}
