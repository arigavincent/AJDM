package io.ajdm.net;

import java.io.IOException;
import java.net.URI;

/** A lease covers header wait and response-body lifetime, including each redirect hop. */
@FunctionalInterface
public interface RequestBudget {
    Lease acquire(URI uri, CancellationScope cancellation) throws IOException, InterruptedException;
    interface Lease extends AutoCloseable { @Override void close(); }
    static RequestBudget unlimited() { return (uri, cancellation) -> () -> {}; }
}
