package io.ajdm.model;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A direct payload URL and its complete caller-supplied header set. Treat both as credentials. */
public record DownloadSource(URI uri, Map<String, String> headers) {
    private static final Set<String> RESERVED = Set.of("range", "if-range", "accept-encoding",
            "host", "connection", "content-length", "expect", "upgrade", "transfer-encoding");
    public DownloadSource {
        Objects.requireNonNull(uri);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("An HTTP(S) URL without embedded credentials or fragment is required");
        }
        headers = Map.copyOf(headers);
        var names = new HashSet<String>();
        for (var header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!header.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || RESERVED.contains(name)
                    || !names.add(name) || header.getValue().chars().anyMatch(c -> c < 32 || c == 127 || c > 255)) {
                throw new IllegalArgumentException("Invalid, duplicate, or engine-owned HTTP header");
            }
        }
    }
    @Override public String toString() { return "DownloadSource[url=redacted, headers=redacted]"; }
}
