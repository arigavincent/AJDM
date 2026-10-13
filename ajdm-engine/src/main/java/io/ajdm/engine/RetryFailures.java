package io.ajdm.engine;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Cause types, rather than exception text, distinguish certificate rejection from transient TLS failures. */
final class RetryFailures {
    private RetryFailures() {}
    static boolean certificateFailure(IOException failure) {
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof CertificateException || cause instanceof CertPathValidatorException
                    || cause instanceof CertPathBuilderException || cause instanceof SSLPeerUnverifiedException) return true;
        }
        return false;
    }
}
