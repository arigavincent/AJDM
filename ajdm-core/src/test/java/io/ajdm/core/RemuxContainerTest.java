package io.ajdm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ajdm.media.MediaFormat;
import io.ajdm.model.DownloadSource;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemuxContainerTest {
    private static MediaFormat hls(String video, String audio) {
        return new MediaFormat("f", "ts", video, audio, null, null, null, null, null, null, null, false, false, "", "",
                new DownloadSource(URI.create("https://cdn.test/index.m3u8"), Map.of()), true);
    }

    @Test void streamCopyTargetsTheEverydayContainerTheCodecsAllow() {
        assertEquals("mp4", CoreService.remuxContainer(hls("avc1.64001f", "mp4a.40.2")));
        assertEquals("mp4", CoreService.remuxContainer(hls("hvc1.1.6.L93", "ec-3")));
        assertEquals("mp4", CoreService.remuxContainer(hls("avc1.4d401e", "none")));
        assertEquals("mkv", CoreService.remuxContainer(hls("vp09.00.10.08", "opus")));
        assertEquals("mkv", CoreService.remuxContainer(hls("avc1.64001f", "opus")));
        assertEquals("m4a", CoreService.remuxContainer(hls("none", "mp4a.40.2")));
        assertEquals("mkv", CoreService.remuxContainer(hls("none", "opus")));
    }
}
