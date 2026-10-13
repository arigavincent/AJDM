package io.ajdm.media;

import io.ajdm.model.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MuxerTest {
    @TempDir Path temp;
    private Path tool(String body) throws Exception {
        Path file = temp.resolve("mux-tool");
        Files.writeString(file, "#!/usr/bin/python3\nimport sys,time\nfrom pathlib import Path\n" + body);
        assertTrue(file.toFile().setExecutable(true)); return file;
    }
    private Path input(String name) throws Exception { return Files.writeString(temp.resolve(name), "stream"); }
    @Test void onlyLocalExplicitDemuxersCopyAndMapsArePassed() throws Exception {
        Path executable = tool("a=sys.argv[1:]\nassert a.count('-protocol_whitelist')==3\nassert a.count('file')==3\nassert '-nostdin' in a and '-n' in a\nassert a[a.index('-c')+1]=='copy'\nassert '0:v:0' in a and '1:a:0' in a\nassert a.count('mov')==2\nassert a[a.index('-enable_drefs')+1]=='0'\nPath(a[-1]).write_bytes(b'mux')\n");
        try (var mux = new FfmpegMuxer(executable)) {
            Path output = temp.resolve("stage.mp4"); mux.mux(input("video.mp4"), "mp4", input("audio.m4a"), "m4a", output, "mp4", () -> false);
            assertEquals("mux", Files.readString(output));
        }
    }
    @Test void failureIsSafeAndInputsRemain() throws Exception {
        Path executable = tool("sys.stderr.write('secret https://private.test/?token=secret')\nsys.exit(9)\n");
        Path video = input("v.webm"), audio = input("a.webm");
        try (var mux = new FfmpegMuxer(executable)) {
            var failure = assertThrows(DownloadException.class, () -> mux.mux(video, "webm", audio, "webm", temp.resolve("stage"), "mkv", () -> false));
            assertEquals(ErrorCode.MUX_FAILED, failure.code()); assertFalse(failure.userMessage().contains("secret"));
            assertEquals("stream", Files.readString(video)); assertEquals("stream", Files.readString(audio));
        }
    }
    @Test void deadlineTerminatesChildAndCancellationStopsMux() throws Exception {
        Path executable = tool("time.sleep(20)\n"); Path video = input("v.mp4"), audio = input("a.m4a");
        try (var mux = new FfmpegMuxer(executable, Duration.ofMillis(150))) {
            assertEquals(ErrorCode.MUX_FAILED, assertThrows(DownloadException.class, () -> mux.mux(video, "mp4", audio, "m4a", temp.resolve("stage"), "mp4", () -> false)).code());
        }
        try (var mux = new FfmpegMuxer(executable)) {
            assertEquals(ErrorCode.CANCELED, assertThrows(DownloadException.class, () -> mux.mux(video, "mp4", audio, "m4a", temp.resolve("stage"), "mp4", () -> true)).code());
        }
    }
    @Test void unsupportedDemuxerSymlinkAndExistingOutputAreRejected() throws Exception {
        Path executable = tool("raise Exception('must never run')\n"); Path video = input("v.mp4"), audio = input("a.m4a");
        try (var mux = new FfmpegMuxer(executable)) {
            assertThrows(IllegalArgumentException.class, () -> mux.mux(video, "m3u8", audio, "m4a", temp.resolve("stage"), "mp4", () -> false));
            Path stage = input("stage"); assertThrows(DownloadException.class, () -> mux.mux(video, "mp4", audio, "m4a", stage, "mp4", () -> false));
            Path link = Files.createSymbolicLink(temp.resolve("link"), video);
            assertThrows(DownloadException.class, () -> mux.mux(link, "mp4", audio, "m4a", temp.resolve("new-stage"), "mp4", () -> false));
        }
    }
    @Test void oversizedDiagnosticsAndEmptySuccessfulOutputFailSafely() throws Exception {
        Path video = input("v.mp4"), audio = input("a.m4a");
        Path executable = tool("sys.stderr.write('x'*300000)\nsys.stderr.flush()\ntime.sleep(20)\n");
        try (var mux = new FfmpegMuxer(executable)) {
            assertEquals(ErrorCode.MUX_FAILED, assertThrows(DownloadException.class, () -> mux.mux(video, "mp4", audio, "m4a", temp.resolve("stage"), "mp4", () -> false)).code());
        }
        executable = tool("sys.exit(0)\n");
        try (var mux = new FfmpegMuxer(executable)) {
            assertEquals(ErrorCode.MUX_FAILED, assertThrows(DownloadException.class, () -> mux.mux(video, "mp4", audio, "m4a", temp.resolve("stage"), "mp4", () -> false)).code());
        }
    }
    @Test void closeTerminatesActiveMuxAndReleasesCaller() throws Exception {
        Path executable = tool("Path(__file__).with_name('started').touch()\ntime.sleep(20)\n"); Path video = input("v.mp4"), audio = input("a.m4a");
        var mux = new FfmpegMuxer(executable);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> assertThrows(DownloadException.class, () -> mux.mux(video, "mp4", audio, "m4a", temp.resolve("stage"), "mp4", () -> false)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(temp.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(temp.resolve("started"))); mux.close(); assertEquals(ErrorCode.CANCELED, result.get(3, TimeUnit.SECONDS).code());
        } finally { mux.close(); }
    }
}
