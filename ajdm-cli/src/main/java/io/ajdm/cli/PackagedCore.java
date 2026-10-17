package io.ajdm.cli;

import io.ajdm.core.CoreService;
import io.ajdm.ipc.IpcServer;
import io.ajdm.media.*;
import io.ajdm.model.PrivateFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Native installer core entry: bundled tools and private preview state, supervised by the desktop launcher. */
public final class PackagedCore {
    private PackagedCore() { }
    public static void main(String[] args) throws Exception {
        Path profile = defaultProfile();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--profile" -> profile = Path.of(args[++i]).toAbsolutePath().normalize();
                default -> throw new IllegalArgumentException("Unknown packaged core option");
            }
        }
        PrivateFiles.directory(profile);
        Path executable = Path.of(Objects.requireNonNull(System.getProperty("jpackage.app-path"), "Use the installed AJDM-core launcher"));
        registerBrowsers(profile, executable);
        serve(profile, profile.resolve("core.endpoint"), tools(executable));
    }
    /** Lets the AJDM browser extension reach this install; a failure here never stops the core. */
    static void registerBrowsers(Path profile, Path executable) {
        Path host = executable.resolveSibling(windows() ? "AJDM-browser.exe" : "AJDM-browser");
        if (!java.nio.file.Files.isRegularFile(host)) return;
        try {
            if (windows()) BrowserHosts.registerWindows(profile, host);
            else BrowserHosts.registerLinux(Path.of(System.getProperty("user.home")), host);
        } catch (java.io.IOException | RuntimeException failure) { /* Browser integration stays unavailable. */ }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    /** jpackage keeps app files in {@code app/} beside the Windows launchers and in {@code ../lib/app/} on Linux. */
    static Path tools(Path executable) {
        Path windows = executable.getParent().resolve("app"), linux = executable.getParent().resolveSibling("lib").resolve("app");
        return (java.nio.file.Files.isDirectory(windows) ? windows : linux).resolve("tools");
    }
    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    /** A bundled tool, else (ffmpeg on Linux, from the distribution's package) the system copy. */
    static Path tool(Path tools, String name) {
        Path bundled = tools.resolve(windows() ? name + ".exe" : name);
        if (java.nio.file.Files.isExecutable(bundled) || windows()) return bundled;
        return Path.of("/usr/bin", name);
    }
    static Path defaultProfile() {
        String local = System.getenv("LOCALAPPDATA");
        return (local == null ? Path.of(System.getProperty("user.home"), ".ajdm") : Path.of(local).resolve("AJDM")).resolve("v1-preview");
    }
    @SuppressWarnings("try") // JVM shutdown must also close these idempotent resources.
    private static void serve(Path profile, Path endpoint, Path tools) throws Exception {
        try (var core = new CoreService(profile.resolve("state.db"),
                new PlatformMediaExtractor(new YtDlpExtractor(tool(tools, "yt-dlp"), tool(tools, "node"))),
                new FfmpegMuxer(tool(tools, "ffmpeg")));
             var server = new IpcServer(endpoint, core)) {
            Thread shutdown = Thread.ofPlatform().unstarted(() -> {
                try { server.close(); } catch (IOException ignored) { }
                core.close();
            });
            Runtime.getRuntime().addShutdownHook(shutdown);
            try { server.await(); }
            finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { } }
        }
    }
}
