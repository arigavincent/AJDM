package io.ajdm.core;

import io.ajdm.media.*;
import io.ajdm.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MediaFilenameBoundaryTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unsafeFilenameIsRejectedBeforeMetadataOrJobCreation(boolean paired) throws Exception {
        var calls = new AtomicInteger();
        MediaExtractor extractor = (page, canceled) -> {
            calls.incrementAndGet();
            String json = """
                    {"id":"fixture","title":"Fixture","formats":[
                    {"format_id":"muxed","ext":"mp4","protocol":"https","url":"https://127.0.0.1:1/file","vcodec":"avc1","acodec":"mp4a"},
                    {"format_id":"video","ext":"mp4","protocol":"https","url":"https://127.0.0.1:1/file","vcodec":"avc1","acodec":"none"},
                    {"format_id":"audio","ext":"m4a","protocol":"https","url":"https://127.0.0.1:1/file","vcodec":"none","acodec":"mp4a"}]}
                    """;
            return new MediaParser().parse(json.getBytes(StandardCharsets.UTF_8), URI.create("https://example.test/page"));
        };
        try (var core = new CoreService(temp.resolve("state/db"), extractor,
                (v, ve, a, ae, out, container, canceled) -> { throw new AssertionError("No mux expected"); })) {
            for (String filename : new String[]{"../escaped.mp4", "nested/file.mp4", "..\\escaped.mp4",
                    temp.resolve("absolute.mp4").toString(), "bad\u0001.mp4", "CON.mp4"}) {
                assertThrows(IllegalArgumentException.class, () -> core.addMedia("https://example.test/page",
                        paired ? "video" : "muxed", paired ? "audio" : null, null, true,
                        temp.resolve("files"), filename, ConflictPolicy.RENAME, 4), filename);
                assertEquals(0, calls.get(), "Invalid filenames must not trigger metadata extraction");
                assertTrue(core.list().isEmpty(), "Invalid filename must not commit a job");
                assertFalse(Files.exists(temp.resolve("files")));
                assertFalse(Files.exists(temp.resolve("escaped.mp4")));
            }
        }
    }
}
