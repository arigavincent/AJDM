package io.ajdm.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionTest {
    private MediaInfo info() throws Exception { return MediaParserTest.parse("""
      {"id":"video","title":"Title","formats":[
        {"format_id":"avc","protocol":"https","url":"https://cdn.test/avc","ext":"mp4","vcodec":"avc1","acodec":"none","height":2160,"fps":60,"tbr":9000,"filesize":100},
        {"format_id":"av1","protocol":"https","url":"https://cdn.test/av1","ext":"mp4","vcodec":"av01","acodec":"none","height":2160,"fps":60,"tbr":8000,"filesize":90},
        {"format_id":"vp9","protocol":"https","url":"https://cdn.test/vp9","ext":"webm","vcodec":"vp9","acodec":"none","height":2160,"fps":60,"tbr":8500},
        {"format_id":"1080","protocol":"https","url":"https://cdn.test/1080","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":1080,"fps":30},
        {"format_id":"8k","protocol":"https","url":"https://cdn.test/8k","ext":"mp4","vcodec":"av01","acodec":"none","height":4320,"fps":60},
        {"format_id":"aac","protocol":"https","url":"https://cdn.test/aac","ext":"m4a","vcodec":"none","acodec":"mp4a","abr":160,"filesize_approx":10},
        {"format_id":"opus","protocol":"https","url":"https://cdn.test/opus","ext":"webm","vcodec":"none","acodec":"opus","abr":160,"filesize":11}
      ]}
      """); }
    @Test void codecPreferenceFollowsResolutionAndFpsAndChoosesCompatibleAudio() throws Exception {
        var modern = MediaSelection.preset(info(), MediaSelection.Preset.BEST, true);
        assertEquals("av1", modern.formats().getFirst().formatId()); assertEquals("aac", modern.formats().getLast().formatId());
        assertEquals("mp4", modern.container()); assertEquals(100, modern.sizeBytes()); assertTrue(modern.approximateSize());
        var compatible = MediaSelection.preset(info(), MediaSelection.Preset.BEST, false);
        assertEquals("avc", compatible.formats().getFirst().formatId());
    }
    @Test void presetsRespectCeilingAndMuxedFormatsDoNotAddAudioTwice() throws Exception {
        var plan = MediaSelection.preset(info(), MediaSelection.Preset.P1080, true);
        assertEquals(1, plan.formats().size()); assertEquals("1080", plan.formats().getFirst().formatId()); assertFalse(plan.requiresMux());
        assertEquals(1, MediaSelection.preset(info(), MediaSelection.Preset.AUDIO, true).formats().size());
    }
    @Test void customRejectsDuplicateVideoAndInvalidPairAndRetainsUnknownSize() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MediaSelection.custom(info(), "avc", "av1"));
        assertThrows(IllegalArgumentException.class, () -> MediaSelection.custom(info(), "1080", "aac"));
        var pair = MediaSelection.custom(info(), "vp9", "opus"); assertTrue(pair.requiresMux()); assertEquals("mkv", pair.container()); assertEquals(-1, pair.sizeBytes());
        assertEquals("4320", Integer.toString(MediaSelection.custom(info(), "8k", null).formats().getFirst().height()));
    }
    @Test void hlsFormatsAreChosenOnlyWhenTheyAddQualityAndJoinAsTransportStreams() throws Exception {
        var info = MediaParserTest.parse("""
          {"id":"video","title":"Title","formats":[
            {"format_id":"file-720","protocol":"https","url":"https://cdn.test/720","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":720,"fps":30,"tbr":2000},
            {"format_id":"hls-720","protocol":"m3u8_native","url":"https://cdn.test/720.m3u8","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":720,"fps":30,"tbr":2000},
            {"format_id":"hls-1080","protocol":"m3u8_native","url":"https://cdn.test/1080.m3u8","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":1080,"fps":30,"tbr":5000},
            {"format_id":"hls-audio","protocol":"m3u8","url":"https://cdn.test/a.m3u8","ext":"mp4","vcodec":"none","acodec":"mp4a","abr":128},
            {"format_id":"file-audio","protocol":"https","url":"https://cdn.test/a","ext":"m4a","vcodec":"none","acodec":"mp4a","abr":128}
          ]}
          """);
        // A plain file of equal quality wins: it supports byte ranges and costs no per-part requests.
        assertEquals("file-720", MediaSelection.preset(info, MediaSelection.Preset.P720, true).formats().getFirst().formatId());
        assertEquals("file-audio", MediaSelection.preset(info, MediaSelection.Preset.AUDIO, true).formats().getFirst().formatId());
        var best = MediaSelection.preset(info, MediaSelection.Preset.BEST, true);
        assertEquals("hls-1080", best.formats().getFirst().formatId()); assertEquals("ts", best.container()); assertFalse(best.requiresMux());
        var split = MediaSelection.custom(MediaParserTest.parse("""
          {"id":"video","title":"Title","formats":[
            {"format_id":"v","protocol":"m3u8_native","url":"https://cdn.test/v.m3u8","ext":"mp4","vcodec":"avc1","acodec":"none","height":1080},
            {"format_id":"a","protocol":"m3u8_native","url":"https://cdn.test/a.m3u8","ext":"mp4","vcodec":"none","acodec":"mp4a"}
          ]}
          """), "v", "a");
        assertTrue(split.requiresMux()); assertEquals("mp4", split.container());
        assertEquals("ts", split.formats().getFirst().ext()); assertEquals("ts", split.formats().getLast().ext());
    }
    @Test void selectionIsStableAcrossExtractorOrdering() throws Exception {
        var info = info(); var expected = MediaSelection.preset(info, MediaSelection.Preset.BEST, true).formats().stream().map(MediaFormat::formatId).toList();
        var random = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            var formats = new java.util.ArrayList<>(info.formats()); java.util.Collections.shuffle(formats, random);
            var shuffled = new MediaInfo(info.id(), info.title(), info.uploader(), info.duration(), info.pageUri(), formats, info.excludedFormats());
            assertEquals(expected, MediaSelection.preset(shuffled, MediaSelection.Preset.BEST, true).formats().stream().map(MediaFormat::formatId).toList());
        }
    }
}
