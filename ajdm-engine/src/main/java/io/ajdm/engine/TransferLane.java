package io.ajdm.engine;

import io.ajdm.net.CancellationScope;
import io.ajdm.net.HttpTransport;
import io.ajdm.net.JdkHttpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Map;

/** Lazy independent pool: recycling one slow lane cannot abort another worker's request. */
final class TransferLane implements HttpTransport {
    private final JdkHttpTransport template;
    private JdkHttpTransport current;
    private final ArrayList<JdkHttpTransport> retired = new ArrayList<>();
    private volatile int generation;
    TransferLane(JdkHttpTransport template) { this.template = template; }
    int generation() { return generation; }
    private synchronized JdkHttpTransport current() {
        if (current == null) current = template.fork();
        return current;
    }
    synchronized boolean recycle() {
        if (current == null || retired.size() >= 8) return false;
        var old = current; current = null; generation++; retired.add(old); old.abort(); return true;
    }
    @Override public HttpResponse<InputStream> send(URI uri, String method, Map<String, String> headers,
                                                   CancellationScope scope) throws IOException, InterruptedException {
        return current().send(uri, method, headers, scope);
    }
    @Override public synchronized void close() {
        if (current != null) { current.close(); current = null; }
        for (var old : retired) old.close();
        retired.clear();
    }
}
