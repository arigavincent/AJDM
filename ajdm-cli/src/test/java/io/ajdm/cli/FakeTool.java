package io.ajdm.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.junit.jupiter.api.Assumptions;

/**
 * Python stand-ins for external executables (yt-dlp, ffmpeg) and the real ffmpeg used by media tests.
 * POSIX runs a {@code #!/usr/bin/python3} script. Windows has no shebangs and a .cmd wrapper would let cmd.exe
 * reinterpret '&' in URLs, so the script is fronted by pip's console-script launcher, which forwards the raw command
 * line, exit code and standard streams unchanged and ends the interpreter with the launcher. Keep in sync with
 * io.ajdm.media.FakeTool.
 */
final class FakeTool {
    private FakeTool() { }
    static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }

    /** Returns the path to execute: {@code path} itself on POSIX, {@code path + ".exe"} on Windows. */
    static Path python(Path path, String source) throws IOException {
        if (!windows()) {
            Files.writeString(path, "#!/usr/bin/python3\n" + source);
            if (!path.toFile().setExecutable(true)) throw new IOException("Fixture not executable");
            return path;
        }
        String[] tools = windowsTools();
        Path script = path.resolveSibling(path.getFileName() + ".py"); Files.writeString(script, source);
        if (script.toString().contains("'")) throw new IOException("Unsupported fixture path");
        var zip = new ByteArrayOutputStream();
        try (var entries = new ZipOutputStream(zip)) {
            entries.putNextEntry(new ZipEntry("__main__.py"));
            entries.write(("import runpy\nrunpy.run_path(r'" + script + "', run_name='__main__')\n").getBytes(StandardCharsets.UTF_8));
        }
        var exe = new ByteArrayOutputStream(); exe.write(Files.readAllBytes(Path.of(tools[1])));
        exe.write(("#!\"" + tools[0] + "\"\n").getBytes(StandardCharsets.UTF_8)); exe.write(zip.toByteArray());
        return Files.write(path.resolveSibling(path.getFileName() + ".exe"), exe.toByteArray());
    }

    /** {@code AJDM_TEST_FFMPEG}, else /usr/bin/ffmpeg on POSIX, else ffmpeg.exe from PATH on Windows. */
    static Path ffmpeg() {
        String configured = System.getenv("AJDM_TEST_FFMPEG");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        if (!windows()) return Path.of("/usr/bin/ffmpeg");
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            try { Path candidate = Path.of(dir.strip(), "ffmpeg.exe"); if (Files.isRegularFile(candidate)) return candidate; }
            catch (InvalidPathException ignored) { /* Malformed PATH entries are not candidates. */ }
        }
        return Path.of("ffmpeg.exe");
    }

    /** Windows grants symbolic-link creation only with Developer Mode or elevation; elsewhere a failure is a real failure. */
    static Path symbolicLink(Path link, Path target) throws IOException {
        try { return Files.createSymbolicLink(link, target); }
        catch (FileSystemException e) {
            if (windows()) Assumptions.abort("Symbolic links need Windows Developer Mode or elevation"); throw e;
        }
    }

    private static String[] windowsTools;

    /** {python.exe, pip launcher}; probed once and aborts (skips) rather than fails when Python or pip is missing. */
    private static synchronized String[] windowsTools() {
        if (windowsTools != null) return windowsTools;
        String python = null;
        for (List<String> probe : List.of(List.of("py", "-3"), List.of("python"))) {
            var command = new ArrayList<>(probe); command.addAll(List.of("-c", "import sys; print(sys.executable)"));
            String found = output(command);
            if (found != null && Files.isRegularFile(Path.of(found))) { python = found; break; }
        }
        Assumptions.assumeTrue(python != null, "Windows fake-tool fixtures need Python 3 (py launcher or python on PATH)");
        String dir = output(List.of(python, "-c", "import os, pip._vendor.distlib as d; print(os.path.dirname(d.__file__))"));
        String name = System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("aarch64") ? "t64-arm.exe" : "t64.exe";
        Assumptions.assumeTrue(dir != null && Files.isRegularFile(Path.of(dir, name)), "Windows fake-tool fixtures need pip installed for " + python);
        return windowsTools = new String[]{python, Path.of(dir, name).toString()};
    }
    private static String output(List<String> command) {
        try {
            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && !text.isEmpty() ? text : null;
        } catch (IOException e) { return null; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }
}
