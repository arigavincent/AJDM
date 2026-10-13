package io.ajdm.model;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeFilenameTest {
    @Test void stripsBothPathConventionsAndUnsafeCharacters() {
        assertEquals("report.pdf", SafeFilename.sanitize("../../report.pdf"));
        assertEquals("report.pdf", SafeFilename.sanitize("C:\\folder\\report.pdf"));
        assertEquals("ab_.txt", SafeFilename.sanitize("a\u0000b?.txt. "));
        assertEquals("download", SafeFilename.sanitize(".."));
        assertEquals("download", SafeFilename.sanitize("/"));
    }
    @Test void reservedDeviceNamesAreSafeOnEveryPlatform() {
        for (String name : new String[] {"CON", "con.txt", "NUL", "PRN", "AUX.pdf", "COM1.zip", "LPT9", "COM¹.txt", "CONIN$"}) {
            assertTrue(SafeFilename.sanitize(name).startsWith("_"), name);
        }
        assertEquals("COM10.txt", SafeFilename.sanitize("COM10.txt"));
        assertEquals("_CON.txt", SafeFilename.sanitize("CON\u00a0.txt"));
    }
    @Test void truncationPreservesExtensionAndWholeUnicodeCharacters() {
        for (String name : new String[] {"a".repeat(250) + ".zip", "😀".repeat(130) + ".mp4", "é".repeat(250) + ".pdf"}) {
            String result = SafeFilename.sanitize(name);
            assertTrue(result.length() <= 200);
            assertTrue(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240);
            assertEquals(name.substring(name.lastIndexOf('.')), result.substring(result.lastIndexOf('.')));
            assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 5)));
            assertEquals(result, SafeFilename.sanitize(result));
        }
    }
    @Test void finalUrlFallbackDecodesPathWithoutTurningPlusIntoSpace() {
        assertEquals("lecture+notes.pdf", SafeFilename.fromUri(URI.create("https://example.com/a/lecture+notes.pdf?token=secret")));
        assertEquals("café.zip", SafeFilename.fromUri(URI.create("https://example.com/caf%C3%A9.zip")));
        assertEquals("download", SafeFilename.fromUri(URI.create("https://example.com/")));
    }
    @Test void randomHostileNamesAlwaysRemainPortableLeaves() {
        var random = new java.util.Random(218913);
        String[] pieces = {"a", ".", " ", "/", "\\", "?", ":", "\u0000", "\u202e", "\u2028", "\u0301", "\u00a0", "é", "😀", "\ud800", "\udfff", "CON"};
        for (int run = 0; run < 10000; run++) {
            var input = new StringBuilder();
            for (int at = 0, count = random.nextInt(250); at < count; at++) input.append(pieces[random.nextInt(pieces.length)]);
            String name = SafeFilename.sanitize(input.toString());
            assertFalse(name.isBlank()); assertFalse(name.equals(".")); assertFalse(name.equals(".."));
            assertFalse(name.contains("/")); assertFalse(name.contains("\\")); assertFalse(name.contains(":"));
            assertFalse(name.endsWith(".")); assertFalse(name.endsWith(" "));
            assertTrue(name.length() <= 200); assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240);
            assertEquals(name, SafeFilename.sanitize(name));
            assertTrue(name.codePoints().noneMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.LINE_SEPARATOR || cp >= 0xd800 && cp <= 0xdfff));
            String numbered = SafeFilename.numbered(name, 10000);
            assertEquals(numbered, SafeFilename.sanitize(numbered));
        }
    }
    @Test void directoryFactoryAndPolicyDefaultsRemainCompatible() {
        URI uri = URI.create("https://example.com/file");
        var probe = new ProbeResult(uri, 123, true, "\"v1\"", null, "../../CON.txt", "application/octet-stream");
        Path folder = Path.of("downloads").toAbsolutePath();
        var product = TransferRequest.inDirectory(uri, folder, probe);
        assertEquals(folder.resolve("_CON.txt"), product.target());
        assertEquals(ConflictPolicy.RENAME, product.conflictPolicy());
        var legacy = new TransferRequest(product.id(), uri, product.target(), product.headers(), product.options());
        assertEquals(ConflictPolicy.ASK, legacy.conflictPolicy());
        assertEquals(ConflictPolicy.RENAME, TransferRequest.of(uri, folder.resolve("file.bin")).conflictPolicy());
        assertThrows(IllegalArgumentException.class, () -> TransferRequest.of(uri, folder.resolve("CON.txt")));
    }
}
