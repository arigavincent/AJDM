package io.ajdm.model;

import java.io.IOException;

public final class DownloadException extends IOException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    private final int httpStatus;

    public DownloadException(ErrorCode code, String message) { this(code, message, null); }
    public DownloadException(ErrorCode code, String message, Throwable cause) {
        this(code, message, cause, 0);
    }
    public DownloadException(ErrorCode code, String message, int httpStatus) { this(code, message, null, httpStatus); }
    private DownloadException(ErrorCode code, String message, Throwable cause, int httpStatus) {
        super(message, cause);
        this.code = code; this.httpStatus = httpStatus;
    }
    /** Zero when this failure does not originate from an HTTP rejection. */
    public int httpStatus() { return httpStatus; }
    public ErrorCode code() { return code; }
    /** Stable public presentation; intentionally excludes exception messages and causes. */
    public String userMessage() {
        return (code == ErrorCode.ACCESS_DENIED && (httpStatus == 401 || httpStatus == 407)
                ? ErrorCode.AUTH_REQUIRED : code).userMessage();
    }
}
