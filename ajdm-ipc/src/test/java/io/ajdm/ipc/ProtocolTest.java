package io.ajdm.ipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void roundTripUnicodeAndEscapes() throws Exception {
        var json = Protocol.parse("{\"v\":1,\"id\":\"abc\",\"type\":\"ping\",\"payload\":{\"text\":\"Swahili: habari 🌍\\n\"}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(json, Protocol.parse(Protocol.encode(json)));
    }
    @ParameterizedTest @ValueSource(strings = {
            "{\"x\":1,\"x\":2}", "{x:1}", "{\"x\":NaN}", "{}{}", "[1]", "{\"x\":1,}", "null"})
    void rejectsAmbiguousJson(String input) { assertThrows(IOException.class, () -> Protocol.parse(input.getBytes(StandardCharsets.UTF_8))); }
    @Test void rejectsMalformedUtf8AndDeepNesting() {
        assertThrows(IOException.class, () -> Protocol.parse(new byte[]{'{', '"', (byte) 0xff, '"', ':', '1', '}'}));
        String deep = "{\"x\":".repeat(40) + "0" + "}".repeat(40);
        assertThrows(IOException.class, () -> Protocol.parse(deep.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void rejectsOversizeBeforeParsing() {
        assertThrows(IOException.class, () -> Protocol.parse(new byte[Protocol.MAX_BYTES + 1]));
    }
}
