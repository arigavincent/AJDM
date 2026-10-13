package io.ajdm.media;

import io.ajdm.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistParserTest {
    private PlaylistInfo parse(String json) throws Exception { return new PlaylistParser().parse(json.getBytes(StandardCharsets.UTF_8), URI.create("https://page.test/list?secret=token")); }
    @Test void orderRepeatedEntriesAndUnavailablePositionsArePreserved() throws Exception {
        var list = parse("""
            {"_type":"playlist","id":"list","title":"List","entries":[
            {"id":"v","title":"One","url":"https://page.test/v","duration":2.5},null,
            {"id":"v","title":"Repeated","webpage_url":"https://page.test/v"},
            {"id":"private","availability":"private","url":"https://page.test/private"}]}
            """);
        assertEquals(4, list.entries().size()); assertEquals(1, list.entries().getFirst().index()); assertEquals(4, list.entries().getLast().index());
        assertEquals(ErrorCode.EXTRACTION_FAILED, list.entries().get(1).unavailable()); assertEquals(ErrorCode.AUTH_REQUIRED, list.entries().getLast().unavailable());
        assertEquals(list.entries().getFirst().page(), list.entries().get(2).page()); assertFalse(list.toString().contains("secret"));
    }
    @Test void unsafeAndNestedEntriesAreSkippedAndYouTubeIdsResolveOnlyWithExplicitKey() throws Exception {
        var list = parse("""
            {"_type":"playlist","id":"list","entries":[
            {"id":"dQw4w9WgXcQ","ie_key":"Youtube","url":"dQw4w9WgXcQ"},
            {"id":"bad","url":"file:///private"},{"id":"nested","_type":"playlist","url":"https://page.test/nested"}]}
            """);
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", list.entries().getFirst().page().toString());
        assertEquals(ErrorCode.INVALID_URL, list.entries().get(1).unavailable()); assertEquals(ErrorCode.FEATURE_UNAVAILABLE, list.entries().getLast().unavailable());
    }
    @Test void wrongShapeDuplicateKeysMalformedNumbersAndOversizedListFailSafely() throws Exception {
        for (String json : new String[]{"{}", "{\"_type\":\"video\",\"entries\":[]}", "{\"_type\":\"playlist\",\"id\":\"a\",\"id\":\"b\",\"entries\":[]}",
                "{\"_type\":\"playlist\",\"id\":\"a\",\"entries\":[{\"id\":\"v\",\"duration\":-1,\"url\":\"https://page.test/v\"}]}",
                "{\"_type\":\"playlist\",\"id\":\"a\",\"entries\":[" + "null,".repeat(1000) + "null]}"}) {
            assertEquals(ErrorCode.EXTRACTION_FAILED, assertThrows(DownloadException.class, () -> parse(json)).code());
        }
    }
    @Test void allRangesAndExplicitChoicesUseStablePositionsAndRejectInvalidSelections() throws Exception {
        var list = parse("{\"_type\":\"playlist\",\"id\":\"a\",\"entries\":[null,null,null,null]}");
        assertEquals(java.util.List.of(1,2,3,4), PlaylistSelection.indices("all", list.entries().size()));
        assertEquals(java.util.List.of(1,2,4), PlaylistSelection.indices("4,1-2,2", list.entries().size()));
        for (String input : new String[]{"0", "5", "3-1", "1-", "", "1;bad", "1-999999999"}) assertThrows(IllegalArgumentException.class, () -> PlaylistSelection.indices(input, 4));
    }
    @Test void drmRootIsRefusedAndProtectedEntriesKeepTheirReason() throws Exception {
        assertEquals(ErrorCode.DRM_PROTECTED, assertThrows(DownloadException.class, () -> parse("{\"has_drm\":true}")).code());
        var list = parse("{\"_type\":\"playlist\",\"id\":\"a\",\"entries\":[{\"id\":\"v\",\"has_drm\":true}]}");
        assertEquals(ErrorCode.DRM_PROTECTED, list.entries().getFirst().unavailable());
    }
}
