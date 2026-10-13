package io.ajdm.net;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cancels both header waits and a currently open response body. */
public final class CancellationScope {
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicReference<InputStream> body = new AtomicReference<>();
    private volatile Thread worker;
    private final java.util.Set<CancellationScope> children = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private CancellationScope parent;

    public CancellationScope child() {
        var child = new CancellationScope(); child.parent = this; children.add(child);
        if (isCanceled()) child.cancel();
        return child;
    }
    /** Detach only after the child's workers and bodies have settled. */
    public void detach() { if (parent != null) parent.children.remove(this); }

    public void worker(Thread thread) { worker = thread; }
    public boolean isCanceled() { return canceled.get(); }
    public void check() throws DownloadException {
        if (isCanceled()) throw new DownloadException(ErrorCode.CANCELED, "Download canceled");
    }
    public void register(InputStream stream) throws IOException {
        body.set(stream);
        if (isCanceled()) {
            closeBody();
            check();
        }
    }
    public void unregister(InputStream stream) { body.compareAndSet(stream, null); }
    public void cancel() {
        canceled.set(true);
        for (CancellationScope child : children) child.cancel();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        closeBody();
    }
    private void closeBody() {
        InputStream stream = body.getAndSet(null);
        if (stream != null) {
            try { stream.close(); } catch (IOException ignored) { /* Cancellation is best-effort. */ }
        }
    }
}
