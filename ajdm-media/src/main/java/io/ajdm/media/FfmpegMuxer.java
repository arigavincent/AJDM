package io.ajdm.media;

import io.ajdm.model.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Bounded local-only copy mux; explicit demuxers prevent downloaded playlists from becoming instructions. */
public final class FfmpegMuxer implements MediaMuxer {
    private final Path executable;
    private final Duration timeout;
    private final Semaphore slot = new Semaphore(1);
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Process running;
    private volatile boolean closed;
    public FfmpegMuxer(Path executable) { this(executable, Duration.ofMinutes(30)); }
    FfmpegMuxer(Path executable, Duration timeout) { this.executable = executable.toAbsolutePath().normalize(); this.timeout = timeout; }
    public static Path defaultBinary(Path database) {
        return database.toAbsolutePath().normalize().getParent().resolve("bin").resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "ffmpeg.exe" : "ffmpeg");
    }
    private static String demuxer(String ext) {
        return switch (ext) { case "mp4", "m4a", "mov" -> "mov"; case "webm", "mkv" -> "matroska"; case "mp3" -> "mp3"; default -> throw new IllegalArgumentException("Unsupported local demuxer"); };
    }
    private static void input(List<String> args, Path path, String ext) {
        String format = demuxer(ext);
        args.addAll(List.of("-protocol_whitelist", "file", "-f", format));
        if (format.equals("mov")) args.addAll(List.of("-enable_drefs", "0", "-use_absolute_path", "0"));
        args.addAll(List.of("-i", path.toAbsolutePath().normalize().toString()));
    }
    @Override public void mux(Path video, String videoExt, Path audio, String audioExt, Path output, String container, BooleanSupplier canceled) throws IOException, InterruptedException {
        var args = new ArrayList<>(List.of(executable.toString(), "-hide_banner", "-nostdin", "-loglevel", "error", "-n"));
        input(args, video, videoExt); input(args, audio, audioExt);
        if (!Set.of("mp4", "mkv").contains(container)) throw new IllegalArgumentException("Unsupported output container");
        args.addAll(List.of("-map", "0:v:0", "-map", "1:a:0", "-map_metadata", "-1", "-map_chapters", "-1", "-c", "copy"));
        if (container.equals("mp4")) args.addAll(List.of("-movflags", "+faststart"));
        args.addAll(List.of("-protocol_whitelist", "file", "-f", container.equals("mp4") ? "mp4" : "matroska", output.toAbsolutePath().normalize().toString()));
        if (!Files.isRegularFile(video, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(audio, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new DownloadException(ErrorCode.MUX_FAILED, "Unsafe local mux input/output");
        while (!slot.tryAcquire(50, TimeUnit.MILLISECONDS)) check(canceled);
        Process process = null; Future<?> out = null, err = null; var descendants = new HashSet<ProcessHandle>();
        try {
            check(canceled); var builder = new ProcessBuilder(args); var env = new HashMap<>(builder.environment()); builder.environment().clear();
            for (String key : List.of("PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL")) if (env.containsKey(key)) builder.environment().put(key, env.get(key));
            process = builder.start(); running = process; process.getOutputStream().close(); Process child = process;
            out = readers.submit(() -> drain(child.getInputStream())); err = readers.submit(() -> drain(child.getErrorStream()));
            long deadline = System.nanoTime() + timeout.toNanos();
            do {
                check(canceled); process.descendants().forEach(descendants::add);
                if (System.nanoTime() >= deadline) throw new IOException("Mux timeout");
                if (out.isDone()) out.get(); if (err.isDone()) err.get();
            } while (!process.waitFor(20, TimeUnit.MILLISECONDS));
            out.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); err.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            check(canceled);
            if (process.exitValue() != 0 || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) == 0) throw new IOException("Mux did not produce output");
        } catch (DownloadException e) { throw e; }
        catch (IOException | ExecutionException | TimeoutException | RuntimeException e) { throw new DownloadException(ErrorCode.MUX_FAILED, "Local mux failed"); }
        finally {
            if (process != null) {
                process.descendants().forEach(descendants::add); descendants.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
                if (process.isAlive()) process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { process.getInputStream().close(); process.getErrorStream().close(); } catch (IOException e) { /* Preserve safe primary result. */ }
            }
            if (out != null) out.cancel(true); if (err != null) err.cancel(true); running = null; slot.release();
        }
    }
    private void check(BooleanSupplier canceled) throws DownloadException {
        if (closed || canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new DownloadException(ErrorCode.CANCELED, "Mux stopped");
    }
    private static Void drain(InputStream stream) throws IOException {
        try (stream) { byte[] bytes = new byte[8192]; int count, total = 0;
            while ((count = stream.read(bytes)) != -1) { if (count > 256 * 1024 - total) throw new IOException("Mux diagnostic limit"); total += count; }
        } return null;
    }
    @Override public void close() { closed = true; Process child = running; if (child != null) { child.descendants().forEach(p -> p.destroyForcibly()); child.destroyForcibly(); } readers.shutdownNow(); }
}
