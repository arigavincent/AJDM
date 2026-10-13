package io.ajdm.net;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentDispositionTest {
    private final URI uri = URI.create("https://example.com/fallback.bin?signature=secret");
    @Test void extendedFilenameWinsAndPreservesUtf8AndPlus() {
        assertEquals("café+notes.mp4", ContentDisposition.filename("attachment; filename=old.bin; filename*=UTF-8''caf%C3%A9+notes.mp4", uri));
        assertEquals("café.pdf", ContentDisposition.filename("attachment; filename*=ISO-8859-1'en'caf%E9.pdf", uri));
    }
    @Test void quotedSemicolonAndEscapedQuoteDoNotBreakParameters() {
        assertEquals("report;final.pdf", ContentDisposition.filename("attachment; filename=\"report;final.pdf\"", uri));
        assertEquals("a_b.pdf", ContentDisposition.filename("attachment; filename=\"a\\\"b.pdf\"", uri));
    }
    @Test void invalidExtendedValueFallsBackWithoutDecodingPlainFilename() {
        for (String value : new String[] {"UTF-8''bad%", "UTF-8''bad%C3%28", "UTF-16''a%00", "not-extended"}) {
            assertEquals("ok%20.pdf", ContentDisposition.filename("attachment; filename=ok%20.pdf; filename*=" + value, uri));
        }
        assertEquals("fallback.bin", ContentDisposition.filename("attachment; filename=one; filename=two", uri));
        assertEquals("fallback.bin", ContentDisposition.filename(null, uri));
    }
    @Test void hostileHeaderNamesNeverBecomePaths() {
        assertEquals("_NUL.txt", ContentDisposition.filename("attachment; filename*=UTF-8''..%2F..%2FNUL.txt", uri));
        assertEquals("download", ContentDisposition.filename("attachment; filename=\"..\"", uri));
    }
}
