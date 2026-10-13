package io.ajdm.media;

import io.ajdm.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ExtractorProcessTest {
    @Test void explicitlySelectedCookiesAreCopiedPrivatelyDeletedAndNeverOverwriteTheOriginal() throws Exception {
        Path original = temp.resolve("cookies.txt");
        String content = "# Netscape HTTP Cookie File\n.test\tTRUE\t/\tTRUE\t4102444800\ttest-cookie\tsecret\n";
        java.nio.file.Files.writeString(original, content);
        try (var tool = new YtDlpExtractor(command(), Duration.ofSeconds(3), 200_000)) {
            tool.configureCookies(original);
            var info = tool.extract(URI.create("https://site.test/ok"), () -> false);
            var args = info.title().split("\\|"); int index = java.util.List.of(args).indexOf("--cookies"); assertTrue(index > 0);
            Path copy = Path.of(args[index + 1]); assertNotEquals(original, copy); assertFalse(java.nio.file.Files.exists(copy));
            assertEquals(content, java.nio.file.Files.readString(original));
            tool.configureCookies(null);
            assertFalse(tool.extract(URI.create("https://site.test/ok"), () -> false).title().contains("--cookies"));
            java.nio.file.Files.writeString(original, "invalid");
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DownloadException.class, () -> tool.configureCookies(original)).code());
        }
    }
    @org.junit.jupiter.api.io.TempDir Path temp;
    private List<String> command() {
        return List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("ajdm.media.test.classpath"), FakeExtractor.class.getName());
    }
    @Test void explicitSimulationAndIsolationPrecedeLiteralUrlArgument() throws Exception {
        try (var tool = new YtDlpExtractor(command(), Duration.ofSeconds(3), 200_000)) {
            var info = tool.extract(URI.create("https://site.test/watch?literal=$()"), () -> false);
            String args = info.title();
            for (String flag : List.of("--ignore-config", "--simulate", "--skip-download", "--no-plugin-dirs", "--no-check-formats")) assertTrue(args.contains(flag));
            assertTrue(args.endsWith("--|https://site.test/watch?literal=$()")); assertFalse(args.contains("--no-simulate"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"sleep", "oversize", "stderr", "fail"})
    void timeoutOutputLimitsAndFailureDoNotLeakDiagnostics(String mode) throws Exception {
        try (var tool = new YtDlpExtractor(command(), Duration.ofMillis(350), 32_000)) {
            var failure = assertThrows(DownloadException.class, () -> tool.extract(URI.create("https://site.test/" + mode + "?token=secret"), () -> false));
            assertEquals(ErrorCode.EXTRACTION_FAILED, failure.code()); assertFalse(failure.userMessage().contains("secret"));
        }
    }
    @Test void callerCancellationStopsProcessAndFreesAdmission() throws Exception {
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        try (var tool = new YtDlpExtractor(command(), Duration.ofSeconds(10), 200_000); var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MediaInfo> work = caller.submit(() -> tool.extract(URI.create("https://site.test/sleep"), canceled::get));
            Thread.sleep(200); canceled.set(true);
            var failure = assertThrows(ExecutionException.class, () -> work.get(3, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CANCELED, ((DownloadException) failure.getCause()).code());
            assertEquals("fixture", tool.extract(URI.create("https://site.test/ok"), () -> false).id());
        }
    }
    @Test void closeCancelsLiveExtraction() throws Exception {
        var tool = new YtDlpExtractor(command(), Duration.ofSeconds(10), 200_000);
        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = caller.submit(() -> tool.extract(URI.create("https://site.test/sleep"), () -> false));
            Thread.sleep(200); tool.close(); assertThrows(ExecutionException.class, () -> work.get(3, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CANCELED, assertThrows(DownloadException.class, () -> tool.extract(URI.create("https://site.test/ok"), () -> false)).code());
        } finally { tool.close(); }
    }
    @Test void timeoutKillsObservedDescendantAsWellAsParent() throws Exception {
        Path pid = temp.resolve("child.pid"); var args = new ArrayList<>(command()); args.add(1, "-Dajdm.fake.pidFile=" + pid);
        try (var tool = new YtDlpExtractor(args, Duration.ofSeconds(1), 200_000)) {
            assertEquals(ErrorCode.EXTRACTION_FAILED, assertThrows(DownloadException.class, () -> tool.extract(URI.create("https://site.test/child"), () -> false)).code());
            long child = Long.parseLong(java.nio.file.Files.readString(pid)); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        }
    }
}
