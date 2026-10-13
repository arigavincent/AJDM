package io.ajdm.media;

import io.ajdm.model.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Explicit simulation, no external downloader, no shell, no inherited tool configuration/plugins. */
public final class YtDlpExtractor implements MediaExtractor {
    private final List<String> executable;
    private final Duration timeout;
    private final int outputLimit;
    private final Semaphore slots = new Semaphore(3);
    private final ExecutorService processes = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("ajdm-extract-", 0).factory());
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Process> running = ConcurrentHashMap.newKeySet();
    private final Set<Job> jobs = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private record Session(Path file, MediaBrowser browser) {}
    private volatile Session session = new Session(null, null);
    @Override public void configureCookies(Path file) throws IOException {
        if (closed) throw new DownloadException(ErrorCode.CANCELED, "Extractor closed");
        Path next = file == null ? null : file.toAbsolutePath().normalize();
        if (next != null) cookieBytes(next);
        session = new Session(next, null);
    }
    @Override public void configureBrowser(MediaBrowser browser) throws IOException {
        if (closed) throw new DownloadException(ErrorCode.CANCELED, "Extractor closed");
        session = new Session(null, browser);
    }
    private static byte[] cookieBytes(Path file) throws IOException {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid cookie file");
            byte[] bytes = read(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), 2 * 1024 * 1024);
            String first = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().findFirst().orElse("");
            if (!first.equals("# Netscape HTTP Cookie File") && !first.equals("# HTTP Cookie File")) throw new IOException("Invalid cookie format");
            return bytes;
        } catch (IOException | IllegalArgumentException e) { throw new DownloadException(ErrorCode.INVALID_REQUEST, "Invalid cookie file"); }
    }
    public YtDlpExtractor(Path executable) { this(List.of(executable.toAbsolutePath().normalize().toString()), Duration.ofSeconds(60), MediaParser.MAX_BYTES); }
    /** Packaged runtime is explicitly selected; no dependency on the user's PATH. */
    public YtDlpExtractor(Path executable, Path node) {
        this(List.of(executable.toAbsolutePath().normalize().toString(), "--js-runtimes", "node:" + node.toAbsolutePath().normalize()), Duration.ofSeconds(60), MediaParser.MAX_BYTES);
    }
    YtDlpExtractor(List<String> executable, Duration timeout, int outputLimit) {
        this.executable = List.copyOf(executable); this.timeout = timeout; this.outputLimit = outputLimit;
    }
    public static Path defaultBinary(Path database) {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "yt-dlp.exe" : "yt-dlp";
        return database.toAbsolutePath().normalize().getParent().resolve("bin").resolve(name);
    }
    @Override public MediaInfo extract(URI page, BooleanSupplier canceled) throws IOException, InterruptedException {
        return new MediaParser().parse(output(page, canceled, false), page);
    }
    @Override public MediaInfo extractEntry(URI page, String mediaId, BooleanSupplier canceled) throws IOException, InterruptedException {
        return new MediaParser().parseEntry(output(page, canceled, false), page, mediaId);
    }
    @Override public PlaylistInfo playlist(URI page, BooleanSupplier canceled) throws IOException, InterruptedException {
        return new PlaylistParser().parse(output(page, canceled, true), page);
    }
    private byte[] output(URI page, BooleanSupplier canceled, boolean flat) throws IOException, InterruptedException {
        new DownloadSource(page, Map.of());
        if (closed || canceled.getAsBoolean()) throw new DownloadException(ErrorCode.CANCELED, "Extraction canceled");
        if (!slots.tryAcquire()) throw new DownloadException(ErrorCode.INVALID_STATE, "Extraction admission limit");
        var job = new Job(); jobs.add(job);
        try {
            processes.execute(() -> {
                job.worker = Thread.currentThread();
                try {
                    if (job.result.isCancelled() || closed) throw new DownloadException(ErrorCode.CANCELED, "Extraction canceled");
                    job.result.complete(execute(page, canceled, flat));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); job.result.completeExceptionally(new DownloadException(ErrorCode.CANCELED, "Extraction interrupted")); }
                catch (IOException | RuntimeException e) { job.result.completeExceptionally(e); }
                finally { slots.release(); jobs.remove(job); }
            });
        } catch (RejectedExecutionException e) { slots.release(); jobs.remove(job); throw new DownloadException(ErrorCode.CANCELED, "Extractor closed"); }
        try { return job.result.get(); }
        catch (InterruptedException e) { job.result.cancel(false); if (job.worker != null) job.worker.interrupt(); throw e; }
        catch (CancellationException e) { throw new DownloadException(ErrorCode.CANCELED, "Extraction canceled"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof DownloadException failure) throw failure;
            throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Extraction failed");
        }
    }
    private byte[] execute(URI page, BooleanSupplier canceled, boolean flat) throws IOException, InterruptedException {
        var arguments = new ArrayList<>(executable);
        arguments.addAll(List.of("--ignore-config", "--no-plugin-dirs", "--simulate", "--skip-download", "--no-check-formats",
                "--no-cache-dir", "--no-warnings", "--no-progress", "--encoding", "utf-8", "--dump-single-json"));
        arguments.addAll(flat ? List.of("--flat-playlist", "--yes-playlist", "--ignore-errors") : List.of("--no-playlist"));
        Path privateCookies = null;
        Process process = null; var descendants = new HashSet<ProcessHandle>(); Future<byte[]> out = null, err = null;
        Session selected = session;
        Path selectedCookies = selected.file();
        // Public TikTok discovery reserves time for up to two 10-second resolver attempts within IPC's 75-second window.
        long duration = selectedCookies == null && selected.browser() == null && TikTokResolver.supports(page)
                ? Math.min(timeout.toNanos(), Duration.ofSeconds(45).toNanos()) : timeout.toNanos();
        long deadline = System.nanoTime() + duration;
        try {
            if (closed || canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new DownloadException(ErrorCode.CANCELED, "Extraction canceled");
            if (selectedCookies != null || selected.browser() != null) {
                byte[] bytes = selectedCookies == null
                        ? "# Netscape HTTP Cookie File\n".getBytes(java.nio.charset.StandardCharsets.UTF_8) : cookieBytes(selectedCookies);
                Path temp = Path.of(System.getProperty("java.io.tmpdir"));
                privateCookies = Files.createTempFile(temp, "ajdm-extract-cookies-", ".txt", io.ajdm.model.PrivateFiles.attributes(temp, false));
                io.ajdm.model.PrivateFiles.check(privateCookies, false);
                Files.write(privateCookies, bytes);
                if (selected.browser() != null) arguments.addAll(List.of("--cookies-from-browser", selected.browser().toolName()));
                arguments.addAll(List.of("--cookies", privateCookies.toString()));
            }
            arguments.addAll(List.of("--", page.toString()));
            var builder = new ProcessBuilder(arguments);
            var inherited = new HashMap<>(builder.environment()); builder.environment().clear();
            builder.environment().putAll(environment(inherited, selected.browser() != null));
            process = builder.start(); running.add(process); process.getOutputStream().close();
            Process child = process;
            out = readers.submit(() -> read(child.getInputStream(), outputLimit));
            err = readers.submit(() -> read(child.getErrorStream(), 256 * 1024));
            while (true) {
                process.descendants().forEach(descendants::add);
                if (closed || canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new DownloadException(ErrorCode.CANCELED, "Extraction canceled");
                if (System.nanoTime() >= deadline) throw new IOException("Extraction deadline exceeded");
                if (out.isDone()) result(out);
                if (err.isDone()) result(err);
                if (process.waitFor(10, TimeUnit.MILLISECONDS)) break;
            }
            if (process.exitValue() != 0) {
                byte[] diagnostic = err.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                throw new DownloadException(siteFailure(page, diagnostic, selected.browser() != null), "Extractor exit failed");
            }
            byte[] output = out.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            err.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return output;
        } catch (DownloadException e) { throw e; }
        catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
            throw new DownloadException(ErrorCode.EXTRACTION_FAILED, "Extractor process failed");
        } finally {
            if (process != null) {
                process.descendants().forEach(descendants::add);
                kill(process, descendants);
                try { process.getInputStream().close(); } catch (IOException e) { /* Preserve the primary safe failure. */ }
                try { process.getErrorStream().close(); } catch (IOException e) { /* Preserve the primary safe failure. */ }
                running.remove(process);
            }
            if (out != null) out.cancel(true); if (err != null) err.cancel(true);
            if (privateCookies != null) Files.deleteIfExists(privateCookies);
        }
    }
    static Map<String, String> environment(Map<String, String> inherited, boolean browserSelected) {
        var keys = new ArrayList<>(List.of("PATH", "HOME", "USERPROFILE", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "APPDATA", "LOCALAPPDATA", "LANG", "LC_ALL"));
        // An explicitly selected Linux browser needs its desktop/keyring and profile locations.
        // General extraction and cookie-file sessions retain the original isolated environment.
        if (browserSelected) keys.addAll(List.of("DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
                "XDG_CURRENT_DESKTOP", "DESKTOP_SESSION", "KDE_SESSION_VERSION", "KDE_FULL_SESSION", "GNOME_DESKTOP_SESSION_ID"));
        var result = new HashMap<String, String>();
        for (String key : keys) if (inherited.containsKey(key)) result.put(key, inherited.get(key));
        return Map.copyOf(result);
    }
    private static ErrorCode siteFailure(URI page, byte[] diagnostic, boolean browserSelected) {
        String text = new String(diagnostic, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (browserSelected && (text.contains("cookies database") || text.contains("cookie database") || text.contains("failed to decrypt")
                || text.contains("keyring") || text.contains("cookie decryption"))) return ErrorCode.MEDIA_SESSION_FAILED;
        String host = page.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("instagram.com") || host.endsWith(".instagram.com") || host.equals("facebook.com")
                || host.endsWith(".facebook.com") || host.equals("fb.watch"))) return ErrorCode.EXTRACTION_FAILED;
        if (text.contains("login required") || text.contains("requires login") || text.contains("log in to access")
                || text.contains("authentication required")) return ErrorCode.MEDIA_AUTH_REQUIRED;
        if (text.contains("empty media response") || text.contains("cannot parse data") || text.contains("rate-limit reached")
                || text.contains("requested content is not available")) return ErrorCode.MEDIA_ACCESS_BLOCKED;
        return ErrorCode.EXTRACTION_FAILED;
    }
    private static final class Job {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Thread worker;
    }
    private static void kill(Process process, Collection<ProcessHandle> descendants) {
        var exits = new ArrayList<CompletableFuture<ProcessHandle>>();
        for (ProcessHandle child : descendants) if (child.isAlive()) { child.destroyForcibly(); exits.add(child.onExit()); }
        // Give a live parent time to reap its observed children before terminating it.
        try { CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new)).get(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException e) { /* Native descendant termination remains bounded. */ }
        if (process.isAlive()) process.destroyForcibly();
    }
    private static byte[] result(Future<byte[]> future) throws IOException, InterruptedException {
        try { return future.get(); } catch (ExecutionException e) { throw new IOException("Extractor pipe failed"); }
    }
    private static byte[] read(InputStream stream, int maximum) throws IOException {
        try (stream; var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > maximum - bytes.size()) throw new IOException("Extractor output limit"); bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }
    @Override public void close() {
        closed = true;
        for (Job job : jobs) {
            job.result.completeExceptionally(new DownloadException(ErrorCode.CANCELED, "Extractor closed"));
            if (job.worker != null) job.worker.interrupt();
        }
        for (Process process : running) kill(process, process.descendants().toList());
        processes.shutdownNow(); readers.shutdownNow();
        try { processes.awaitTermination(5, TimeUnit.SECONDS); readers.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
