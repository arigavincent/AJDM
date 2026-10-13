package io.ajdm.cli;

import io.ajdm.core.*;
import io.ajdm.media.*;
import io.ajdm.model.*;
import java.nio.file.*;

/** Independent production core killed only after its selected durable parent boundary. */
public final class PairedMediaWorker {
    private PairedMediaWorker() {}
    public static void main(String[] args) throws Exception {
        try (var core = new CoreService(Path.of(args[0]), new YtDlpExtractor(Path.of(args[2])),
                (video, ve, audio, ae, output, container, cancel) -> Files.write(output, Files.readAllBytes(video), StandardOpenOption.CREATE_NEW),
                (job, boundary) -> {
                    if (!boundary.name().equals(args[3])) return;
                    if (boundary == MediaJobObserver.Boundary.MERGING) Files.writeString(job.stagePath(), "interrupted mux stage");
                    Files.writeString(Path.of(args[4]), job.id().toString());
                    try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("Boundary interrupted"); }
                })) {
            core.addMedia("https://page.test/pair", "video", "audio", null, true, Path.of(args[1]), null, ConflictPolicy.RENAME, 1);
            if (args[3].equals("DOWNLOADING")) {
                while (core.list().getFirst().durableBytes() == 0) Thread.sleep(20);
                Files.writeString(Path.of(args[4]), core.list().getFirst().id().toString());
            }
            Thread.sleep(60_000);
        }
    }
}
