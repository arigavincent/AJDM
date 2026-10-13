package io.ajdm.net;

import java.io.IOException;
import java.time.Duration;

public final class RetryableResponseException extends IOException {
    private static final long serialVersionUID = 1L;
    private final Duration retryAfter;
    private final int httpStatus;
    public RetryableResponseException(int status, Duration retryAfter) {
        super("Server temporarily unavailable (HTTP " + status + ")");
        this.retryAfter = retryAfter;
        this.httpStatus = status;
    }
    public Duration retryAfter() { return retryAfter; }
    public int httpStatus() { return httpStatus; }
}
