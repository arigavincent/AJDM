package io.ajdm.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferRequestTest {
    @Test void rejectsUnsafeUrlsAndEngineOwnedHeaders() {
        for (String uri : new String[]{"file:///etc/passwd", "ftp://example.com/a", "http://user:secret@example.com/a"}) {
            assertThrows(IllegalArgumentException.class, () -> TransferRequest.of(URI.create(uri), Path.of("file")));
        }
        for (Map<String, String> headers : java.util.List.of(Map.of("Range", "bytes=4-8"),
                Map.of("X-Test", "ok\r\nAuthorization: secret"))) {
            assertThrows(IllegalArgumentException.class, () -> new TransferRequest(UUID.randomUUID(),
                    URI.create("https://example.com/file"), Path.of("file"), headers, TransferOptions.defaults()));
        }
    }
}
