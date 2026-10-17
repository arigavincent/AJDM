package io.ajdm.media;

import io.ajdm.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MediaParserTest {
    @Test void sharedCollectionPagesSelectAndRefreshTheExactChildRatherThanTheFirstVideo() throws Exception {
        String json = """
            {"_type":"playlist","id":"post","http_headers":{"User-Agent":"Fixture"},"entries":[
              {"id":"one","url":"https://cdn.test/one.mp4","ext":"mp4"},
              {"id":"two","url":"https://cdn.test/two.mp4","ext":"mp4"}]}
            """;
        var selected = new MediaParser().parseEntry(json.getBytes(StandardCharsets.UTF_8), PAGE, "two");
        assertEquals("two", selected.id()); assertEquals("https://cdn.test/two.mp4", selected.formats().getFirst().source().uri().toString());
        assertEquals("Fixture", selected.formats().getFirst().source().headers().get("User-Agent"));
        assertEquals(PAGE, selected.pageUri());
        assertEquals(ErrorCode.REFRESH_FAILED, assertThrows(DownloadException.class,
                () -> new MediaParser().parseEntry(json.getBytes(StandardCharsets.UTF_8), PAGE, "missing")).code());
    }
    @Test void aSingleDirectSourceWithoutFormatsKeepsUnknownMetadataAndCanBeSelected() throws Exception {
        var info = parse("""
            {"id":"post","title":"Post","url":"https://cdn.test/movie.mp4?token=private","ext":"mp4",
             "http_headers":{"Referer":"https://site.test/","Cookie":"sid=private"}}
            """);
        assertEquals(1, info.formats().size());
        var format = info.formats().getFirst();
        assertEquals(MediaFormat.Kind.UNKNOWN, format.kind());
        assertEquals("source", format.formatId());
        assertEquals("sid=private", format.source().headers().get("Cookie"));
        assertEquals(format, MediaSelection.custom(info, "source", null).formats().getFirst());
        assertEquals(format, MediaSelection.preset(info, MediaSelection.Preset.BEST, true).formats().getFirst());
        assertThrows(IllegalArgumentException.class, () -> MediaSelection.preset(info, MediaSelection.Preset.P1080, true));
        assertFalse(info.toString().contains("private"));
    }
    @Test void collectionsDirectUsersToThePlaylistPicker() {
        assertEquals(ErrorCode.PLAYLIST_REQUIRED, assertThrows(DownloadException.class,
                () -> parse("{\"_type\":\"multi_video\",\"id\":\"post\",\"entries\":[]}")).code());
    }
    private static final URI PAGE = URI.create("https://site.test/watch?token=private");
    static MediaInfo parse(String json) throws Exception { return new MediaParser().parse(json.getBytes(StandardCharsets.UTF_8), PAGE); }
    @Test void preservesMetadataAndAllEligibleFormatsWithoutExposingSources() throws Exception {
        var info = parse("""
            {"id":"video","title":"Title","duration":12.5,"unknown":{"value":true},"http_headers":{"User-Agent":"Agent","Cookie":"sid=private"},"formats":[
              {"format_id":"v","ext":"webm","protocol":"https","url":"https://cdn.test/file?signature=secret","vcodec":"vp9","acodec":"none","width":3840,"height":2160,"fps":60,"tbr":9000,"filesize":123,"dynamic_range":"HDR10","format_note":"4K","language":"en"},
              {"format_id":"a","ext":"webm","protocol":"https","url":"https://cdn.test/audio","vcodec":"none","acodec":"opus","abr":160,"filesize_approx":456,"http_headers":{"Referer":"https://site.test/"}},
              {"format_id":"8k","ext":"mp4","protocol":"https","url":"https://cdn.test/8k","vcodec":"av01","acodec":"none","height":4320},
              {"format_id":"drm","protocol":"https","url":"https://cdn.test/drm","has_drm":true},
              {"format_id":"hls","protocol":"m3u8_native","url":"https://cdn.test/list.m3u8"},
              {"format_id":"http","protocol":"http","url":"http://cdn.test/file"}
            ]}
            """);
        // DRM and plain-HTTP formats are excluded; an HLS media playlist is kept and marked for part-by-part download.
        assertEquals(4, info.formats().size()); assertEquals(2, info.excludedFormats());
        var hls = info.format("hls"); assertTrue(hls.hls()); assertFalse(hls.fragmented()); assertEquals("ts", hls.ext());
        assertFalse(info.format("v").hls());
        var v = info.format("v"); assertEquals(3840, v.width()); assertEquals(2160, v.height()); assertEquals(60, v.fps()); assertTrue(v.hdr());
        assertEquals("sid=private", v.source().headers().get("Cookie")); assertEquals("https://site.test/", info.format("a").source().headers().get("Referer"));
        assertEquals(456, info.format("a").sizeBytes()); assertTrue(info.format("a").approximateSize());
        assertFalse(info.toString().contains("private")); assertFalse(v.toString().contains("secret"));
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"id\":\"x\",\"has_drm\":true,\"formats\":[]}",
        "{\"id\":\"x\",\"formats\":[{\"format_id\":\"a\",\"has_drm\":true}]}"})
    void drmCannotBeOffered(String json) {
        assertEquals(ErrorCode.DRM_PROTECTED, assertThrows(DownloadException.class, () -> parse(json)).code());
    }
    @ParameterizedTest @ValueSource(strings = {
        "{}", "{\"id\":\"x\",\"formats\":[]}", "{\"id\":\"x\",\"id\":\"y\",\"formats\":[]}",
        "{\"id\":\"x\",\"formats\":[{\"format_id\":\"a\",\"protocol\":\"https\",\"url\":\"http://cdn.test/a\"}]}",
        "{\"id\":\"x\",\"formats\":[{\"format_id\":\"a\",\"protocol\":\"https\",\"url\":\"https://user:secret@cdn.test/a\"}]}",
        "{\"id\":\"x\",\"formats\":[{\"format_id\":\"a\",\"protocol\":\"https\",\"url\":\"https://cdn.test/a\",\"http_headers\":{\"Range\":\"bytes=0-1\"}}]}"})
    void malformedOrUnsafeSourcesFailClosed(String json) {
        assertEquals(ErrorCode.EXTRACTION_FAILED, assertThrows(DownloadException.class, () -> parse(json)).code());
    }
    @Test void rejectsDeepOutputAndMalformedUtf8() {
        assertThrows(DownloadException.class, () -> parse("{\"x\":".repeat(40) + "0" + "}".repeat(40)));
        assertThrows(DownloadException.class, () -> new MediaParser().parse(new byte[]{(byte)0xff}, PAGE));
    }
    @Test void unknownCodecsAndFragmentedHttpsFormatsRemainVisibleWithoutInventedMetadata() throws Exception {
        var info = parse("""
          {"id":"x","formats":[
            {"format_id":"generic","protocol":"https","url":"https://cdn.test/file","ext":"mp4","vcodec":null,"acodec":null},
            {"format_id":"fragmented","protocol":"https","url":"https://cdn.test/file","ext":"mp4","vcodec":"avc1","acodec":"none","fragments":[{"url":"https://cdn.test/part"}]}
          ]}
          """);
        assertEquals(2, info.formats().size()); assertEquals(MediaFormat.Kind.UNKNOWN, info.format("generic").kind()); assertTrue(info.format("fragmented").fragmented());
    }
}
