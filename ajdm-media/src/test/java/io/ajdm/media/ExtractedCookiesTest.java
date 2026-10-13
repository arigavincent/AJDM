package io.ajdm.media;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtractedCookiesTest {
    @Test void forwardsOnlyLiveCookiesMatchingThePayloadDomainPathAndSecureScheme() {
        String serialized = "sid=secret; Domain=.cdn.test; Path=/media; Secure; Expires=4102444800; "
                + "other=private; Domain=site.test; Path=/; "
                + "expired=x; Domain=cdn.test; Expires=1; "
                + "quoted=\"a;b\"; Domain=cdn.test; Path=/media";
        assertEquals("sid=secret; quoted=\"a;b\"", ExtractedCookies.header(serialized, URI.create("https://a.cdn.test/media/video")));
        assertEquals("quoted=\"a;b\"", ExtractedCookies.header(serialized, URI.create("http://cdn.test/media/video")));
        assertEquals("", ExtractedCookies.header(serialized, URI.create("https://evilcdn.test/media/video")));
        assertEquals("", ExtractedCookies.header(serialized, URI.create("https://cdn.test/media2/video")));
        assertEquals("", ExtractedCookies.header("unscoped=secret", URI.create("https://cdn.test/")));
    }
    @Test void unsafeAndMalformedMetadataFailsInsteadOfEnteringRequestHeaders() {
        for (String value : new String[]{"sid=foo\r\nRange: x", "sid=\"unterminated", "Domain=cdn.test; sid=x", "sid=x; Domain=cdn.test; Expires=invalid"})
            assertThrows(IllegalArgumentException.class, () -> ExtractedCookies.header(value, URI.create("https://cdn.test/")));
    }
}
