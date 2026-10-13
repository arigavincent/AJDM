package io.ajdm.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SourceIdentityTest {
    private ProbeResult probe(String etag, long size, boolean ranges) {
        return new ProbeResult(URI.create("https://example.test/new"), size, ranges, etag, "same timestamp");
    }
    @Test void identicalStrongValidatorAndSizeAllowRetainedRanges() throws Exception {
        SourceIdentity.requireSame(probe("\"v1\"", 100, true), probe("\"v1\"", 100, true), 20);
    }
    @ParameterizedTest @ValueSource(strings = {"W/\"v1\"", "unquoted", "\"broken\"tag\"", "\"bad\nvalue\"", ""})
    void weakOrMalformedValidatorsNeverProveCrossUrlIdentity(String etag) {
        var failure = assertThrows(DownloadException.class, () -> SourceIdentity.requireSame(probe(etag, 100, true), probe(etag, 100, true), 20));
        assertEquals(ErrorCode.REFRESH_IDENTITY_UNVERIFIED, failure.code());
    }
    @Test void missingAndUnknownMetadataCannotProveIdentity() {
        assertThrows(DownloadException.class, () -> SourceIdentity.requireSame(probe(null, 100, true), probe(null, 100, true), 20));
        assertThrows(DownloadException.class, () -> SourceIdentity.requireSame(probe("\"v1\"", -1, true), probe("\"v1\"", -1, true), 20));
    }
    @Test void changedSizeOrValidatorIsRejectedEvenBeforeAnyWrites() {
        assertEquals(ErrorCode.SOURCE_CHANGED, assertThrows(DownloadException.class,
                () -> SourceIdentity.requireSame(probe("\"v1\"", 100, true), probe("\"v2\"", 100, true), 0)).code());
        assertEquals(ErrorCode.SOURCE_CHANGED, assertThrows(DownloadException.class,
                () -> SourceIdentity.requireSame(probe("\"v1\"", 100, true), probe("\"v1\"", 99, true), 0)).code());
    }
    @Test void singleResponsePrefixIsNotSplicedIntoReplacement() {
        assertEquals(ErrorCode.REFRESH_UNSUPPORTED, assertThrows(DownloadException.class,
                () -> SourceIdentity.requireSame(probe("\"v1\"", 100, false), probe("\"v1\"", 100, true), 20)).code());
    }
    @Test void withoutRetainedBytesFreshUnknownSourceIsPermitted() throws Exception {
        SourceIdentity.requireSame(null, probe(null, -1, false), 0);
    }
    @ParameterizedTest @ValueSource(strings = {"file:///tmp/payload", "https://user:pass@example.test/file", "https://example.test/file#fragment"})
    void replacementUsesTheSameUrlValidationAsInitialRequests(String uri) {
        assertThrows(IllegalArgumentException.class, () -> new DownloadSource(URI.create(uri), Map.of()));
    }
    @Test void completeHeadersAreImmutableValidatedAndDiagnosticsAreRedacted() {
        URI uri = URI.create("https://example.test/file?token=secret");
        var source = new DownloadSource(uri, Map.of("Authorization", "Bearer secret"));
        assertThrows(UnsupportedOperationException.class, () -> source.headers().put("X-Key", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new DownloadSource(uri, Map.of("Range", "bytes=0-1")));
        assertThrows(IllegalArgumentException.class, () -> new DownloadSource(uri, Map.of("Authorization", "a", "authorization", "b")));
        assertThrows(IllegalArgumentException.class, () -> new DownloadSource(uri, Map.of("X-Key", "bad\r\nheader")));
        assertFalse(source.toString().contains("secret"));
        assertFalse(new TransferRequest(UUID.randomUUID(), uri, Path.of("target"), source.headers(), TransferOptions.defaults()).toString().contains("secret"));
    }
}
