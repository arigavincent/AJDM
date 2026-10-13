package io.ajdm.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicErrorTest {
    @Test void certificateFailuresHaveTheirOwnSafeAction() {
        var error = new DownloadException(ErrorCode.TLS_VERIFICATION_FAILED, "https://example.test/?signed=secret");
        assertTrue(error.userMessage().startsWith("AJDM-2011: "));
        assertTrue(error.userMessage().contains("site administrator"));
        assertFalse(error.userMessage().contains("secret")); assertFalse(error.userMessage().contains("credentials"));
    }
    @Test void completePrdCatalogIsDefinedWithoutImplyingAdaptersExist() {
        var entries = java.util.Map.ofEntries(
                java.util.Map.entry(ErrorCode.UNSUPPORTED_SCHEME, "AJDM-1001"),
                java.util.Map.entry(ErrorCode.INVALID_URL, "AJDM-1002"),
                java.util.Map.entry(ErrorCode.NOT_FOUND, "AJDM-2001"),
                java.util.Map.entry(ErrorCode.ACCESS_DENIED, "AJDM-2002"),
                java.util.Map.entry(ErrorCode.AUTH_REQUIRED, "AJDM-2010"),
                java.util.Map.entry(ErrorCode.INSUFFICIENT_DISK_SPACE, "AJDM-2003"),
                java.util.Map.entry(ErrorCode.DISK_WRITE_ERROR, "AJDM-2004"),
                java.util.Map.entry(ErrorCode.SOURCE_CHANGED, "AJDM-3001"),
                java.util.Map.entry(ErrorCode.RETRIES_EXHAUSTED, "AJDM-3002"),
                java.util.Map.entry(ErrorCode.RANGE_UNSUPPORTED, "AJDM-3003"),
                java.util.Map.entry(ErrorCode.PROTOCOL_VIOLATION, "AJDM-3004"),
                java.util.Map.entry(ErrorCode.CHECKSUM_MISMATCH, "AJDM-3005"),
                java.util.Map.entry(ErrorCode.EXTRACTION_FAILED, "AJDM-4001"),
                java.util.Map.entry(ErrorCode.REFRESH_FAILED, "AJDM-4002"),
                java.util.Map.entry(ErrorCode.MUX_FAILED, "AJDM-4003"),
                java.util.Map.entry(ErrorCode.DRM_PROTECTED, "AJDM-4004"),
                java.util.Map.entry(ErrorCode.IPC_UNAVAILABLE, "AJDM-5001"));
        entries.forEach((code, expected) -> assertEquals(expected, code.publicCode()));
    }
    @Test void existingFailuresHaveStableCatalogCodesAndActions() {
        assertEquals("AJDM-2003", ErrorCode.INSUFFICIENT_DISK_SPACE.publicCode());
        assertEquals("AJDM-3005", ErrorCode.CHECKSUM_MISMATCH.publicCode());
        assertEquals("AJDM-3001", ErrorCode.SOURCE_CHANGED.publicCode());
        assertEquals("AJDM-4002", ErrorCode.REFRESH_IDENTITY_UNVERIFIED.publicCode());
        for (var code : ErrorCode.values()) {
            assertTrue(code.publicCode().matches("AJDM-[0-9]{4}"));
            assertFalse(code.message().isBlank());
            assertFalse(code.action().isBlank());
        }
    }

    @Test void publicPresentationNeverIncludesUntrustedMessageOrCause() {
        var error = new DownloadException(ErrorCode.DISK_WRITE_ERROR,
                "https://example.com/file?token=secret", new java.io.IOException("Authorization: secret"));
        assertEquals("AJDM-2004: Could not write to disk. Choose another folder or check the disk.", error.userMessage());
        assertFalse(error.userMessage().contains("secret"));
        assertFalse(error.userMessage().contains("example.com"));
    }

    @Test void credentialStatusIsDistinguishedFromDeniedAccess() {
        for (int status : new int[] {401, 407}) {
            var error = new DownloadException(ErrorCode.ACCESS_DENIED, "untrusted", status);
            assertTrue(error.userMessage().startsWith("AJDM-2010: "));
            assertEquals(ErrorCode.ACCESS_DENIED, error.code(), "Keep internal persistence compatible");
        }
        assertTrue(new DownloadException(ErrorCode.ACCESS_DENIED, "untrusted", 403).userMessage().startsWith("AJDM-2002: "));
    }
}
