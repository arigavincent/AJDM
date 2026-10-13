package io.ajdm.model;

/** Strong cross-URL identity proof, stricter than resuming the original endpoint. */
public final class SourceIdentity {
    private SourceIdentity() {}
    public static void requireSame(ProbeResult previous, ProbeResult replacement, long retainedBytes) throws DownloadException {
        if (previous != null) {
            if (previous.contentLength() >= 0 && replacement.contentLength() >= 0
                    && previous.contentLength() != replacement.contentLength()) {
                throw new DownloadException(ErrorCode.SOURCE_CHANGED, "Replacement source has a different file size");
            }
            if (strong(previous.etag()) && strong(replacement.etag()) && !previous.etag().equals(replacement.etag())) {
                throw new DownloadException(ErrorCode.SOURCE_CHANGED, "Replacement source has a different strong validator");
            }
        }
        if (retainedBytes == 0) return;
        if (previous == null || !previous.acceptRanges() || !replacement.acceptRanges()) {
            throw new DownloadException(ErrorCode.REFRESH_UNSUPPORTED, "Replacement cannot continue saved byte ranges");
        }
        if (previous.contentLength() < 0 || replacement.contentLength() != previous.contentLength()
                || !strong(previous.etag()) || !strong(replacement.etag()) || !previous.etag().equals(replacement.etag())) {
            throw new DownloadException(ErrorCode.REFRESH_IDENTITY_UNVERIFIED, "Replacement identity cannot be proven for saved byte ranges");
        }
    }
    private static boolean strong(String etag) {
        return etag != null && etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")
                && etag.substring(1, etag.length() - 1).chars().allMatch(c -> c == 33 || c >= 35 && c <= 126 || c >= 128 && c <= 255);
    }
}
