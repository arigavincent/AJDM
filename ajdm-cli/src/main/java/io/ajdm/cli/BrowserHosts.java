package io.ajdm.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers the AJDM native-messaging host for the current user so the AJDM extension can reach it: manifest files in
 * each installed Chromium-family browser's and Firefox's per-user folder on Linux, or HKCU registry keys pointing at
 * manifests under the AJDM profile on Windows. Only the AJDM extension IDs are allowed to connect.
 */
final class BrowserHosts {
    static final String NAME = "io.ajdm.host";
    static final String CHROME_EXTENSION = "clhifbiomhnomfhpjafndoalloopnmmk";
    static final String FIREFOX_EXTENSION = "ajdm@ariga.dev";
    private static final List<String> LINUX_CHROMIUM = List.of(".config/google-chrome", ".config/chromium", ".config/microsoft-edge",
            ".config/BraveSoftware/Brave-Browser", ".config/vivaldi");
    private static final List<String> WINDOWS_CHROMIUM = List.of("Software\\Google\\Chrome", "Software\\Chromium", "Software\\Microsoft\\Edge",
            "Software\\BraveSoftware\\Brave-Browser", "Software\\Vivaldi");

    private BrowserHosts() {}

    static JsonObject chromiumManifest(Path host) {
        var manifest = base(host); var origins = new JsonArray(); origins.add("chrome-extension://" + CHROME_EXTENSION + "/");
        manifest.add("allowed_origins", origins); return manifest;
    }
    static JsonObject firefoxManifest(Path host) {
        var manifest = base(host); var ids = new JsonArray(); ids.add(FIREFOX_EXTENSION);
        manifest.add("allowed_extensions", ids); return manifest;
    }
    private static JsonObject base(Path host) {
        var manifest = new JsonObject();
        manifest.addProperty("name", NAME); manifest.addProperty("description", "AJDM browser integration");
        manifest.addProperty("path", host.toAbsolutePath().normalize().toString()); manifest.addProperty("type", "stdio");
        return manifest;
    }

    /** Linux: writes manifests for the browsers present under {@code home}; returns the files written. */
    static List<Path> registerLinux(Path home, Path host) throws IOException {
        var written = new ArrayList<Path>();
        for (String browser : LINUX_CHROMIUM) {
            Path config = home.resolve(browser);
            if (Files.isDirectory(config)) written.add(write(config.resolve("NativeMessagingHosts").resolve(NAME + ".json"), chromiumManifest(host)));
        }
        if (Files.isDirectory(home.resolve(".mozilla"))) {
            written.add(write(home.resolve(".mozilla/native-messaging-hosts").resolve(NAME + ".json"), firefoxManifest(host)));
        }
        return written;
    }

    /** Windows: manifests in the AJDM profile plus HKCU keys naming them; reg.exe runs with fixed arguments, no shell. */
    static List<Path> registerWindows(Path profile, Path host) throws IOException, InterruptedException {
        Path chromium = write(profile.resolve("native-messaging").resolve(NAME + ".chromium.json"), chromiumManifest(host));
        Path firefox = write(profile.resolve("native-messaging").resolve(NAME + ".firefox.json"), firefoxManifest(host));
        for (String browser : WINDOWS_CHROMIUM) reg(browser + "\\NativeMessagingHosts\\" + NAME, chromium);
        reg("Software\\Mozilla\\NativeMessagingHosts\\" + NAME, firefox);
        return List.of(chromium, firefox);
    }

    private static void reg(String key, Path manifest) throws IOException, InterruptedException {
        var process = new ProcessBuilder("reg.exe", "add", "HKCU\\" + key, "/ve", "/t", "REG_SZ", "/d", manifest.toString(), "/f")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.waitFor();
    }

    private static Path write(Path file, JsonObject manifest) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, manifest.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
