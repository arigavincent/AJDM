package io.ajdm.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;

public interface HttpTransport extends AutoCloseable {
    HttpResponse<InputStream> send(URI uri, String method, Map<String, String> engineHeaders,
                                    CancellationScope cancellation) throws IOException, InterruptedException;
    @Override void close();
}
