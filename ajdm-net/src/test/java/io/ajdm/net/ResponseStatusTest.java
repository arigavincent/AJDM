package io.ajdm.net;

import io.ajdm.model.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResponseStatusTest {
    private record Reply(int statusCode, HttpHeaders headers) implements HttpResponse<Void> {
        @Override public HttpRequest request() { return HttpRequest.newBuilder(uri()).build(); }
        @Override public Optional<HttpResponse<Void>> previousResponse() { return Optional.empty(); }
        @Override public Void body() { return null; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://example.test/file"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
    private Reply reply(int status, String delay) {
        return new Reply(status, HttpHeaders.of(delay == null ? Map.of() : Map.of("Retry-After", List.of(delay)), (name, value) -> true));
    }
    @ParameterizedTest @ValueSource(ints = {429, 500, 502, 503, 599})
    void retryableResponsesPreserveStatusForThrottleClassification(int status) {
        var error = assertThrows(RetryableResponseException.class, () -> ResponseChecks.status(reply(status, "1")));
        assertEquals(status, error.httpStatus()); assertEquals(Duration.ofSeconds(1), error.retryAfter());
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 407, 410, 416})
    void fatalOrDecisionResponsesPreserveTheirStatus(int status) {
        var error = assertThrows(DownloadException.class, () -> ResponseChecks.status(reply(status, null)));
        assertEquals(status, error.httpStatus());
        assertEquals(status == 404 ? ErrorCode.NOT_FOUND : status == 416 ? ErrorCode.SOURCE_CHANGED
                : status == 400 ? ErrorCode.PROTOCOL_VIOLATION : ErrorCode.ACCESS_DENIED, error.code());
    }
    @Test void retryAfterDateAndSecondsAreBoundedAndMalformedValuesAreIgnored() {
        for (String value : new String[]{"invalid", "-1", "999999999999999999999999999999999999"}) {
            assertEquals(Duration.ZERO, assertThrows(RetryableResponseException.class, () -> ResponseChecks.status(reply(503, value))).retryAfter());
        }
        assertEquals(Duration.ofDays(1), assertThrows(RetryableResponseException.class,
                () -> ResponseChecks.status(reply(503, "999999"))).retryAfter());
        String past = ZonedDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(Duration.ZERO, assertThrows(RetryableResponseException.class, () -> ResponseChecks.status(reply(503, past))).retryAfter());
        String future = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(10).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        var delay = assertThrows(RetryableResponseException.class, () -> ResponseChecks.status(reply(429, future))).retryAfter();
        assertTrue(delay.compareTo(Duration.ofSeconds(8)) >= 0 && delay.compareTo(Duration.ofSeconds(10)) <= 0);
    }
}
