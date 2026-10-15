package io.ajdm.ui;

import com.google.gson.JsonObject;
import io.ajdm.ipc.IpcClient;
import io.ajdm.model.PrivateFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native installer desktop entry: supervises the separately packaged AJDM-core process and talks to it over IPC only. */
public final class PackagedLauncher {
    private PackagedLauncher() { }
    public static void main(String[] args) throws Exception {
        Path profile = defaultProfile(); int closeAfter = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--profile" -> profile = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--close-after" -> closeAfter = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown packaged option");
            }
        }
        PrivateFiles.directory(profile);
        Path endpoint = profile.resolve("core.endpoint");
        Path executable = Path.of(Objects.requireNonNull(System.getProperty("jpackage.app-path"), "Use the installed AJDM launcher"));
        Process child = null;
        try {
            if (!ready(endpoint)) {
                var builder = new ProcessBuilder(executable.resolveSibling("AJDM-core.exe").toString(), "--profile", profile.toString());
                builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(profile.resolve("core.log").toFile()));
                child = builder.start(); Process owned = child;
                Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> stop(owned, endpoint)));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!ready(endpoint)) {
                    if (!child.isAlive() || System.nanoTime() >= until) throw new IOException("AJDM core could not start; inspect the preview core.log");
                    Thread.sleep(100);
                }
            }
            Path downloads = Path.of(System.getProperty("user.home"), "Downloads", "AJDM-v1-preview");
            Files.createDirectories(downloads);
            var ui = new ArrayList<>(List.of("--socket", endpoint.toString(), "--directory", downloads.toString()));
            if (closeAfter > 0) ui.addAll(List.of("--close-after", Integer.toString(closeAfter)));
            UiLauncher.main(ui.toArray(String[]::new));
        } finally { if (child != null) stop(child, endpoint); }
    }
    private static Path defaultProfile() {
        String local = System.getenv("LOCALAPPDATA");
        return (local == null ? Path.of(System.getProperty("user.home"), ".ajdm") : Path.of(local).resolve("AJDM")).resolve("v1-preview");
    }
    private static boolean ready(Path endpoint) {
        try { new IpcClient(endpoint).call("ping", new JsonObject()); return true; }
        catch (IOException e) { return false; }
    }
    private static void stop(Process child, Path endpoint) {
        if (!child.isAlive()) return;
        try { new IpcClient(endpoint).call("shutdown", new JsonObject()); }
        catch (IOException ignored) { /* A startup failure may have no responding endpoint. */ }
        try { if (!child.waitFor(30, TimeUnit.SECONDS)) child.destroyForcibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
