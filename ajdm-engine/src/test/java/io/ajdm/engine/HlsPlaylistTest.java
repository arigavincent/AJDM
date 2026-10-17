package io.ajdm.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.ajdm.model.ByteRange;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HlsPlaylistTest {
    private static final URI BASE = URI.create("https://cdn.example/video/720p/index.m3u8?sig=abc");

    @Test void aVideoOnDemandPlaylistListsItsSegmentsInOrderResolvedAgainstThePlaylist() throws Exception {
        var playlist = HlsPlaylist.parse("""
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                seg0.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:5.5,title
                /other/seg1.ts?token=1
                #EXTINF:2,
                https://mirror.example/seg2.ts
                #EXT-X-ENDLIST
                """, BASE);
        assertEquals(3, playlist.parts().size());
        assertEquals(URI.create("https://cdn.example/video/720p/seg0.ts"), playlist.parts().get(0).uri());
        assertEquals(URI.create("https://cdn.example/other/seg1.ts?token=1"), playlist.parts().get(1).uri());
        assertEquals(URI.create("https://mirror.example/seg2.ts"), playlist.parts().get(2).uri());
        assertEquals(13.5, playlist.durationSeconds(), 1e-9);
        assertTrue(playlist.parts().stream().noneMatch(HlsPlaylist.Part::initialization));
    }

    @Test void anInitializationSectionComesFirstAndByteRangesContinueFromTheirPredecessor() throws Exception {
        var playlist = HlsPlaylist.parse("""
                #EXTM3U
                #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
                #EXTINF:4,
                #EXT-X-BYTERANGE:1000@720
                main.mp4
                #EXTINF:4,
                #EXT-X-BYTERANGE:500
                main.mp4
                #EXT-X-ENDLIST
                """, BASE);
        assertEquals(3, playlist.parts().size());
        assertTrue(playlist.parts().getFirst().initialization());
        assertEquals(new ByteRange(0, 720), playlist.parts().get(0).range());
        assertEquals(new ByteRange(720, 1720), playlist.parts().get(1).range());
        assertEquals(new ByteRange(1720, 2220), playlist.parts().get(2).range());
    }

    @Test void clearKeysAndQuotedCommasAreAccepted() throws Exception {
        var playlist = HlsPlaylist.parse("""
                #EXTM3U
                #EXT-X-KEY:METHOD=NONE
                #EXT-X-MAP:URI="init,v=1.mp4"
                #EXTINF:4,
                a.m4s
                #EXT-X-ENDLIST
                """, BASE);
        assertEquals(URI.create("https://cdn.example/video/720p/init,v=1.mp4"), playlist.parts().getFirst().uri());
    }

    @ParameterizedTest @ValueSource(strings = {
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"",
            "#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,AA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/key\",KEYFORMAT=\"com.microsoft.playready\"",
            "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\""})
    void drmKeyedStreamsAreRefusedAsProtected(String key) {
        var failure = assertThrows(DownloadException.class, () -> HlsPlaylist.parse("#EXTM3U\n" + key + "\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", BASE));
        assertEquals(ErrorCode.DRM_PROTECTED, failure.code());
    }

    @Test void aes128KeysApplyUntilChangedAndDefaultToTheMediaSequenceAsIv() throws Exception {
        var playlist = HlsPlaylist.parse("""
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:7
                #EXTINF:4,
                clear.ts
                #EXT-X-KEY:METHOD=AES-128,URI="keys/1.key"
                #EXTINF:4,
                a.ts
                #EXTINF:4,
                b.ts
                #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/2.key",IV=0x0102
                #EXTINF:4,
                c.ts
                #EXT-X-KEY:METHOD=NONE
                #EXTINF:4,
                d.ts
                #EXT-X-ENDLIST
                """, BASE);
        var parts = playlist.parts();
        assertNull(parts.get(0).key()); assertNull(parts.get(4).key());
        assertEquals(URI.create("https://cdn.example/video/720p/keys/1.key"), parts.get(1).key().uri());
        assertEquals(parts.get(1).key(), parts.get(2).key());
        assertEquals(8, parts.get(1).sequence()); assertEquals(9, parts.get(2).sequence());
        byte[] sequenceIv = new byte[16]; sequenceIv[15] = 9;
        assertArrayEquals(sequenceIv, parts.get(2).iv());
        byte[] explicitIv = new byte[16]; explicitIv[14] = 1; explicitIv[15] = 2;
        assertArrayEquals(explicitIv, parts.get(3).iv());
        assertFalse(parts.get(3).toString().contains("keys.example"));
    }

    @Test void anEncryptedInitializationSectionNeedsAnExplicitIv() throws Exception {
        assertEquals(ErrorCode.PROTOCOL_VIOLATION, code("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXT-X-MAP:URI=\"i.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST\n"));
        var playlist = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x1\n#EXT-X-MAP:URI=\"i.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST\n", BASE);
        assertTrue(playlist.parts().getFirst().initialization()); assertNotNull(playlist.parts().getFirst().key());
        for (String bad : new String[]{"IV=12", "IV=0x", "IV=0x" + "1".repeat(33), "IV=0xZZ"}) {
            assertEquals(ErrorCode.PROTOCOL_VIOLATION, code("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"," + bad + "\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"), bad);
        }
        assertEquals(ErrorCode.PROTOCOL_VIOLATION, code("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(ErrorCode.PROTOCOL_VIOLATION, code("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://k.example/key\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"));
    }

    @Test void unsupportedButLegitimatePlaylistsSayWhy() {
        assertEquals(ErrorCode.FEATURE_UNAVAILABLE, code("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"));
        assertEquals(ErrorCode.FEATURE_UNAVAILABLE, code("#EXTM3U\n#EXTINF:4,\na.ts\n"), "Live playlists have no end list");
        assertEquals(ErrorCode.FEATURE_UNAVAILABLE, code("#EXTM3U\n#EXT-X-MAP:URI=\"i1.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-MAP:URI=\"i2.mp4\"\n#EXTINF:4,\nb.m4s\n#EXT-X-ENDLIST\n"));
    }

    @Test void malformedOrUnsafePlaylistsAreProtocolViolations() {
        for (String text : new String[]{
                "",
                "#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\na.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXTINF:abc,\na.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXTINF:4,\nhttp://cdn.example/a.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXTINF:4,\nhttps://user:pw@cdn.example/a.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXTINF:4,\n#EXT-X-BYTERANGE:100\na.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXT-X-MAP:URI=\"i.mp4\",BYTERANGE=\"10\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXT-X-KEY:METHOD=NONE,METHOD=NONE\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n",
                "#EXTM3U\n#EXT-X-ENDLIST\n"}) {
            assertEquals(ErrorCode.PROTOCOL_VIOLATION, code(text), text);
        }
    }

    @Test void diagnosticsNeverExposeSegmentLinks() throws Exception {
        var part = HlsPlaylist.parse("#EXTM3U\n#EXTINF:4,\na.ts?token=secret\n#EXT-X-ENDLIST\n", BASE).parts().getFirst();
        assertFalse(part.toString().contains("secret"));
    }

    private static ErrorCode code(String text) {
        return assertThrows(DownloadException.class, () -> HlsPlaylist.parse(text, BASE)).code();
    }
}
