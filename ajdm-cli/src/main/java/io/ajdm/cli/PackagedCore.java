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
        serve(profile, profile.resolve("core.endpoint"), executable.getParent().resolve("app/tools"));
    }
    static Path defaultProfile() {
        String local = System.getenv("LOCALAPPDATA");
        return (local == null ? Path.of(System.getProperty("user.home"), ".ajdm") : Path.of(local).resolve("AJDM")).resolve("v1-preview");
    }
    @SuppressWarnings("try") // JVM shutdown must also close these idempotent resources.
    private static void serve(Path profile, Path endpoint, Path tools) throws Exception {
        try (var core = new CoreService(profile.resolve("state.db"),
                new PlatformMediaExtractor(new YtDlpExtractor(tools.resolve("yt-dlp.exe"), tools.resolve("node.exe"))),
                new FfmpegMuxer(tools.resolve("ffmpeg.exe")));
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
