package io.ajdm.net;

import io.ajdm.model.ProbeResult;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;

public final class Probe {
    private final HttpTransport transport;
    public Probe(HttpTransport transport) { this.transport = transport; }

    public ProbeResult inspect(URI uri, CancellationScope cancellation) throws IOException, InterruptedException {
        long headLength = -1;
        String etag = null, modified = null;
        String filename = null, contentType = null;
        URI finalUri = uri;
        HttpResponse<InputStream> head = null;
        try { head = transport.send(uri, "HEAD", Map.of(), cancellation); }
        catch (java.net.http.HttpTimeoutException e) {
            if(e instanceof java.net.http.HttpConnectTimeoutException)throw e;
            cancellation.check(); // A response timeout can be specific to HEAD; test GET once.
        }
        if(head!=null) try (InputStream _ = head.body()) {
            int status = head.statusCode();
            // Some media CDNs reject HEAD while serving ranges. Explicit retry delays remain authoritative.
            boolean unavailableHead = status >= 500 && status <= 599 && head.headers().firstValue("Retry-After").isEmpty();
            if (status != 403 && status != 405 && status != 501 && !unavailableHead) {
                ResponseChecks.status(head); ResponseChecks.identity(head);
                headLength = ResponseChecks.contentLength(head);
                etag = head.headers().firstValue("ETag").orElse(null);
                modified = head.headers().firstValue("Last-Modified").orElse(null);
                finalUri = head.uri();
                filename = ContentDisposition.suggested(head.headers().firstValue("Content-Disposition").orElse(null));
                contentType = head.headers().firstValue("Content-Type").orElse(null);
            }
        } finally { cancellation.unregister(head.body()); }
        if (headLength == 0) return new ProbeResult(finalUri, 0, false, etag, modified, filename, contentType);

        // Verify actual range behavior instead of trusting Accept-Ranges alone.
        HttpResponse<InputStream> response = transport.send(finalUri, "GET", Map.of("Range", "bytes=0-0"), cancellation);
        try (InputStream _ = response.body()) {
            ResponseChecks.status(response); ResponseChecks.identity(response);
            long size;
            if (response.statusCode() == 206) {
                try { size = ResponseChecks.range(response, 0, 1, -1); }
                catch (DownloadException invalidRange) {
                    throw new DownloadException(ErrorCode.PROTOCOL_VIOLATION, "The metadata range is invalid", 206);
                }
            } else size = ResponseChecks.contentLength(response);
            return new ProbeResult(response.uri(), size, response.statusCode() == 206,
                    response.headers().firstValue("ETag").orElse(etag),
                    response.headers().firstValue("Last-Modified").orElse(modified),
                    java.util.Optional.ofNullable(ContentDisposition.suggested(response.headers().firstValue("Content-Disposition").orElse(null))).orElse(filename),
                    response.headers().firstValue("Content-Type").orElse(contentType));
        } finally { cancellation.unregister(response.body()); }
    }
}
