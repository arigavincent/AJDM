package io.ajdm.bench;

import io.ajdm.engine.HlsDownload;
import io.ajdm.model.DownloadException;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import io.ajdm.net.CancellationScope;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One HLS media-playlist download for real-link checks: {@code HlsLabDownload <connections> <playlist-url> <output>}.
 * Prints one result line; never prints the source URL.
 */
public final class HlsLabDownload {
    private HlsLabDownload() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) { System.err.println("usage: HlsLabDownload <connections> <playlist-url> <output>"); System.exit(2); }
        Path target = Path.of(args[2]).toAbsolutePath().normalize();
        var options = TransferOptions.defaults().withConnections(Integer.parseInt(args[0]));
        var request = new TransferRequest(UUID.randomUUID(), URI.create(args[1]), target, Map.of(), options);
        long started = System.nanoTime();
        try {
            var result = new HlsDownload(request, target.resolveSibling("." + target.getFileName() + ".hls"), new CancellationScope(),
                    (done, total, joined, bytes) -> System.err.printf(Locale.ROOT, "\r%d/%d parts, %.1f MiB", done, total, bytes / 1048576.0)).run();
            double seconds = (System.nanoTime() - started) / 1e9;
            System.err.println();
            System.out.printf(Locale.ROOT, "HLS_RESULT seconds=%.2f bytes=%d mib_s=%.3f parts=%d duration=%.1f sha256=%s%n",
                    seconds, result.bytes(), result.bytes() / 1048576.0 / seconds, result.parts(), result.durationSeconds(), result.sha256());
        } catch (DownloadException e) { System.err.println(e.userMessage()); System.exit(1); }
    }
}
