package io.ajdm.ui;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaChoicesTest {
    @Test void resolverTiersStayReadableWithoutInventingPixelDimensions() {
        var row=video("resolver-tiktok-standard",0,0,"unknown");row.addProperty("kind","UNKNOWN");
        row.addProperty("note","Standard · No watermark · TikTok resolver");
        assertEquals("Standard · No watermark · TikTok resolver · Resolution unknown",MediaChoices.quality(row));
        assertFalse(MediaChoices.quality(row).contains("1080p"));
    }
    @Test void showsReadableResolutionFrameRateAndEncoding() {
        var row = video("699", 2160, 59.94, "av01.0.12M.10"); row.addProperty("hdr", true);
        assertEquals("2160p (4K) · 59.94 fps · HDR", MediaChoices.quality(row));
        assertEquals("AV1", MediaChoices.encoding(row));
        assertFalse(MediaChoices.quality(row).contains("699"));
        row.addProperty("height", 4320); row.addProperty("fps", 60.0);
        assertEquals("4320p (8K) · 60 fps · HDR", MediaChoices.quality(row));
    }
    @Test void audioAndMissingMetadataStayReadable() {
        var audio = audio("251", "opus", 160); audio.addProperty("language", "en");
        assertEquals("Audio only · 160 kb/s", MediaChoices.quality(audio));
        assertEquals("English · Opus · 160 kb/s", MediaChoices.audioLabel(audio));
        audio.addProperty("note", "DRC"); assertTrue(MediaChoices.audioLabel(audio).contains("volume balanced"));
        audio.addProperty("sizeBytes", -1); audio.addProperty("approximateSize", true);
        assertEquals("Unknown size", MediaChoices.size(audio));
        audio.addProperty("sizeBytes", 1048576);
        assertEquals("About 1.0 MiB", MediaChoices.size(audio));
        var unknown = video("mystery", 0, 0, "unknown");
        assertEquals("Resolution unknown", MediaChoices.quality(unknown));
    }
    @Test void fragmentsAreBlockedButUnknownDirectFilesCanBeChosenWithoutInventingCodecs() {
        var video = video("137", 1080, 30, "avc1"); assertTrue(MediaChoices.available(video));
        video.addProperty("fragmented", true); assertFalse(MediaChoices.available(video));
        video.addProperty("fragmented", false); video.addProperty("kind", "UNKNOWN"); assertTrue(MediaChoices.available(video));
    }
    @Test void pairsOnlyAvailableAudioAndBreaksBitrateTiesByCompatibility() {
        var video = video("137", 1080, 30, "avc1");
        var aac = audio("140", "mp4a.40.2", 128);
        var opus = audio("251", "opus", 128);
        var blocked = audio("fragment", "opus", 320); blocked.addProperty("fragmented", true);
        assertSame(aac, MediaChoices.bestAudio(List.of(opus, blocked, aac), video));
        opus.addProperty("audioBitrateKbps", 160);
        assertSame(opus, MediaChoices.bestAudio(List.of(aac, opus), video));
        assertNull(MediaChoices.bestAudio(List.of(video, blocked), video));
    }
    static JsonObject video(String id, int height, double fps, String codec) {
        var row = base(id, "VIDEO_ONLY", "mp4"); row.addProperty("height", height); row.addProperty("fps", fps);
        row.addProperty("videoCodec", codec); row.addProperty("audioCodec", "none"); return row;
    }
    static JsonObject audio(String id, String codec, double rate) {
        var row = base(id, "AUDIO_ONLY", "webm"); row.addProperty("audioCodec", codec);
        row.addProperty("videoCodec", "none"); row.addProperty("audioBitrateKbps", rate); return row;
    }
    private static JsonObject base(String id, String kind, String container) {
        var row = new JsonObject(); row.addProperty("formatId", id); row.addProperty("kind", kind);
        row.addProperty("container", container); row.addProperty("fragmented", false); row.addProperty("hdr", false);
        row.addProperty("sizeBytes", -1); row.addProperty("approximateSize", false); return row;
    }
}
