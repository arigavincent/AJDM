package io.ajdm.net;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.ProbeResult;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class ResponseChecks {
    private static final Pattern RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private ResponseChecks() {}

    public static void status(HttpResponse<?> response) throws IOException {
        int status = response.statusCode();
        if (status == 200 || status == 206) return;
        if (status == 429 || status >= 500 && status <= 599) {
            throw new RetryableResponseException(status, retryAfter(response));
        }
        ErrorCode code = switch (status) {
            case 401, 403, 407, 410 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 416 -> ErrorCode.SOURCE_CHANGED;
            default -> ErrorCode.PROTOCOL_VIOLATION;
        };
        throw new DownloadException(code, "Server rejected the request (HTTP " + status + ")", status);
    }
    public static void identity(HttpResponse<?> response) throws DownloadException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (!"identity".equalsIgnoreCase(encoding)) {
            throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Server did not provide an identity-encoded file");
        }
    }
    public static long contentLength(HttpResponse<?> response) throws DownloadException {
        String value = response.headers().firstValue("Content-Length").orElse(null);
        if (value == null) return -1;
        try {
            long length = Long.parseLong(value);
            if (length >= 0) return length;
        } catch (NumberFormatException ignored) { /* Reject below. */ }
        throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "Invalid Content-Length");
    }
    public static long range(HttpResponse<?> response, long from, long to, long expectedTotal) throws DownloadException {
        var match = RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
        if (!match.matches()) throw protocol("Missing or invalid Content-Range");
        try {
            long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2)), total = Long.parseLong(match.group(3));
            if (start != from || end != to - 1 || total <= end || (expectedTotal >= 0 && total != expectedTotal)) {
                throw protocol("Response range does not match the requested representation");
            }
            long length = contentLength(response);
            if (length >= 0 && length != to - from) throw protocol("Response length does not match the range");
            return total;
        } catch (NumberFormatException e) { throw protocol("Content-Range overflow"); }
    }
    public static void unchanged(HttpResponse<?> response, ProbeResult probe) throws DownloadException {
        String etag = response.headers().firstValue("ETag").orElse(null);
        String modified = response.headers().firstValue("Last-Modified").orElse(null);
        if (probe.etag() != null && etag != null && !probe.etag().equals(etag)
                || probe.lastModified() != null && modified != null && !probe.lastModified().equals(modified)) {
            throw new DownloadException(ErrorCode.SOURCE_CHANGED, "The source changed during the download");
        }
    }
    private static DownloadException protocol(String message) { return new DownloadException(ErrorCode.PROTOCOL_VIOLATION, message); }
    private static Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) return Duration.ZERO;
        try {
            if (value.matches("[0-9]+")) return Duration.ofSeconds(Math.min(Long.parseLong(value), 86400));
            Duration duration = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
            return duration.isNegative() ? Duration.ZERO : duration.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : duration;
        } catch (NumberFormatException | DateTimeParseException e) { return Duration.ZERO; }
    }
}
