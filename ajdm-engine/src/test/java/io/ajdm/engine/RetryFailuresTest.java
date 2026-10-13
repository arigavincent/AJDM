package io.ajdm.engine;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryFailuresTest {
    @Test void certificateAndPeerVerificationFailuresAreFatalEvenWhenWrapped() {
        assertTrue(RetryFailures.certificateFailure(new SSLHandshakeException("verification", new CertificateException("untrusted"))));
        assertTrue(RetryFailures.certificateFailure(new IOException("wrapped", new SSLPeerUnverifiedException("wrong host"))));
        assertTrue(RetryFailures.certificateFailure(new SSLHandshakeException("path", new CertPathValidatorException("expired"))));
    }
    @Test void handshakeDisconnectsAndOtherNetworkFailuresCanUseBoundedRetries() {
        assertFalse(RetryFailures.certificateFailure(new SSLHandshakeException("transient negotiation")));
        assertFalse(RetryFailures.certificateFailure(new SSLHandshakeException("reset", new java.net.SocketException("reset"))));
        assertFalse(RetryFailures.certificateFailure(new java.net.http.HttpConnectTimeoutException("connect")));
        assertFalse(RetryFailures.certificateFailure(new java.io.EOFException("short response")));
    }
    @Test void cyclicFailureCausesCannotHangClassification() {
        var first = new IOException("first"); var second = new IOException("second");
        first.initCause(second); second.initCause(first);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertFalse(RetryFailures.certificateFailure(first)));
    }
}
