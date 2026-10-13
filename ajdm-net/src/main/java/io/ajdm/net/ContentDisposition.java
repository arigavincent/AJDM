package io.ajdm.net;

import io.ajdm.model.SafeFilename;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** RFC 6266 advisory filename, with RFC 8187 UTF-8 and legacy Latin-1 decoding. */
final class ContentDisposition {
    private ContentDisposition() {}
    static String filename(String header, URI finalUri) {
        String candidate = suggested(header);
        return candidate == null ? SafeFilename.fromUri(finalUri) : SafeFilename.sanitize(candidate);
    }
    static String suggested(String header) {
        if (header == null || header.length() > 16384) return null;
        Map<String, String> parameters = parameters(header);
        if (parameters == null) return null;
        String extended = decode(parameters.get("filename*"));
        if (extended != null && !extended.isBlank()) return extended;
        String plain = parameters.get("filename");
        return plain == null || plain.isBlank() ? null : plain;
    }
    private static Map<String, String> parameters(String header) {
        var parts = new java.util.ArrayList<String>();
        var part = new StringBuilder(); boolean quoted = false, escaped = false;
        for (int i = 0; i < header.length(); i++) {
            char ch = header.charAt(i);
            if (ch < 32 && ch != '\t' || ch == 127) return null;
            if (escaped) { part.append(ch); escaped = false; continue; }
            if (quoted && ch == '\\') { part.append(ch); escaped = true; continue; }
            if (ch == '"') quoted = !quoted;
            if (ch == ';' && !quoted) { parts.add(part.toString()); part.setLength(0); }
            else part.append(ch);
        }
        if (quoted || escaped) return null;
        parts.add(part.toString());
        if (!parts.getFirst().strip().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) return null;
        var values = new HashMap<String, String>();
        for (int i = 1; i < parts.size(); i++) {
            String field = parts.get(i).strip(); int equals = field.indexOf('=');
            if (equals <= 0) return null;
            String name = field.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || values.containsKey(name)) return null;
            String value = field.substring(equals + 1).strip();
            if (value.startsWith("\"")) {
                if (!value.endsWith("\"") || value.length() < 2) return null;
                var plain = new StringBuilder();
                for (int at = 1; at < value.length() - 1; at++) {
                    char ch = value.charAt(at);
                    if (ch == '\\') { if (++at >= value.length() - 1) return null; ch = value.charAt(at); }
                    else if (ch == '"') return null;
                    plain.append(ch);
                }
                value = plain.toString();
            } else if (value.contains("\"") || value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) return null;
            values.put(name, value);
        }
        return values;
    }
    private static String decode(String value) {
        if (value == null) return null;
        int first = value.indexOf('\''), second = value.indexOf('\'', first + 1);
        if (first <= 0 || second < 0) return null;
        Charset charset;
        String name = value.substring(0, first);
        if (name.equalsIgnoreCase("UTF-8")) charset = StandardCharsets.UTF_8;
        else if (name.equalsIgnoreCase("ISO-8859-1")) charset = StandardCharsets.ISO_8859_1;
        else return null;
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = second + 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%') {
                if (i + 2 >= value.length()) return null;
                int high = Character.digit(value.charAt(++i), 16), low = Character.digit(value.charAt(++i), 16);
                if (high < 0 || low < 0) return null;
                bytes.write(high * 16 + low);
            } else {
                if (ch > 127 || !(Character.isLetterOrDigit(ch) || "!#$&+-.^_`|~".indexOf(ch) >= 0)) return null;
                bytes.write(ch);
            }
        }
        try { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { return null; }
    }
}
