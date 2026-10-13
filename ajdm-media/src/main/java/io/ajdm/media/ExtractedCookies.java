package io.ajdm.media;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** yt-dlp emits scoped Set-Cookie-style metadata separately from http_headers. */
final class ExtractedCookies {
    private static final Set<String> ATTRIBUTES = Set.of("domain", "path", "expires", "secure", "version", "httponly", "samesite", "max-age");
    private ExtractedCookies() { }
    static String header(String serialized, URI source) {
        if (serialized == null || serialized.isBlank()) return "";
        var result = new ArrayList<String>(); Cookie cookie = null;
        for (String token : tokens(serialized)) {
            String value = token.strip(); int equals = value.indexOf('=');
            String name = (equals < 0 ? value : value.substring(0, equals)).strip();
            String key = name.toLowerCase(Locale.ROOT);
            String content = equals < 0 ? "" : value.substring(equals + 1).strip();
            if (!ATTRIBUTES.contains(key)) {
                if (cookie != null && cookie.applies(source)) result.add(cookie.pair);
                if (equals < 1 || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IllegalArgumentException("Invalid cookie metadata");
                cookie = new Cookie(name + "=" + content);
            } else {
                if (cookie == null) throw new IllegalArgumentException("Missing cookie");
                switch (key) {
                    case "domain" -> cookie.domain = content.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                    case "path" -> cookie.path = content;
                    case "secure" -> cookie.secure = true;
                    case "expires" -> cookie.expires = Long.parseLong(content);
                    default -> { /* Non-routing attributes do not enter a Cookie request header. */ }
                }
            }
        }
        if (cookie != null && cookie.applies(source)) result.add(cookie.pair);
        return String.join("; ", result);
    }
    private static List<String> tokens(String value) {
        var tokens = new ArrayList<String>(); int start = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || c > 255) throw new IllegalArgumentException("Invalid cookie text");
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            else if (c == ';' && !quoted) { tokens.add(value.substring(start, i)); start = i + 1; }
        }
        if (quoted || escaped) throw new IllegalArgumentException("Invalid cookie quotes");
        if (start < value.length()) tokens.add(value.substring(start));
        return tokens;
    }
    private static final class Cookie {
        final String pair;
        String domain = "", path = "/";
        boolean secure;
        long expires = Long.MAX_VALUE;
        Cookie(String pair) { this.pair = pair; }
        boolean applies(URI source) {
            String host = source.getHost(); if (host == null || domain.isEmpty()) return false;
            host = host.toLowerCase(Locale.ROOT);
            String requestPath = source.getRawPath(); if (requestPath == null || requestPath.isEmpty()) requestPath = "/";
            return (host.equals(domain) || host.endsWith("." + domain))
                    && path.startsWith("/") && requestPath.startsWith(path)
                    && (path.endsWith("/") || requestPath.length() == path.length() || requestPath.charAt(path.length()) == '/')
                    && (!secure || "https".equalsIgnoreCase(source.getScheme())) && expires > Instant.now().getEpochSecond();
        }
    }
}
