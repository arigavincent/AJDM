package io.ajdm.model;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Untrusted names produce one portable leaf, never a path or a choice of folder. */
public final class SafeFilename {
    private SafeFilename() {}

    public static String sanitize(String input) {
        if (input == null) return "download";
        String leaf = input.substring(Math.max(input.lastIndexOf('/'), input.lastIndexOf('\\')) + 1);
        var clean = new StringBuilder();
        Normalizer.normalize(leaf, Normalizer.Form.NFC).codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || cp >= 0xD800 && cp <= 0xDFFF) return;
            clean.appendCodePoint("<>:\"|?*".indexOf(cp) >= 0 ? '_' : cp);
        });
        String name = Normalizer.normalize(clean.toString(), Normalizer.Form.NFC).strip();
        while (!name.isEmpty() && Character.isSpaceChar(name.codePointAt(0))) name = name.substring(Character.charCount(name.codePointAt(0)));
        while (!name.isEmpty() && (name.endsWith(".") || Character.isWhitespace(name.codePointBefore(name.length()))
                || Character.isSpaceChar(name.codePointBefore(name.length())))) name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "download";
        String bounded = fit(name, "");
        String stem = bounded.split("\\.", 2)[0].strip().toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$|CLOCK\\$")) bounded = fit('_' + bounded, "");
        return bounded;
    }

    public static String fromUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.endsWith("/")) return "download";
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        try { return sanitize(URLDecoder.decode(leaf.replace("+", "%2B"), StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException e) { return "download"; }
    }

    public static String numbered(String name, int number) {
        if (number < 1 || number > 10000) throw new IllegalArgumentException("Invalid filename suffix");
        return fit(sanitize(name), " (" + number + ")");
    }

    private static String fit(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 32 ? name.substring(dot) : "";
        String stem = extension.isEmpty() ? name : name.substring(0, dot);
        int units = 200 - extension.length() - suffix.length();
        int bytes = 240 - extension.getBytes(StandardCharsets.UTF_8).length - suffix.length();
        var result = new StringBuilder();
        for (int offset = 0; offset < stem.length();) {
            int cp = stem.codePointAt(offset), width = Character.charCount(cp);
            int encoded = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (width > units || encoded > bytes) break;
            result.appendCodePoint(cp); units -= width; bytes -= encoded; offset += width;
        }
        while (!result.isEmpty() && (result.charAt(result.length() - 1) == '.' || Character.isWhitespace(result.codePointBefore(result.length()))
                || Character.isSpaceChar(result.codePointBefore(result.length())))) result.setLength(result.offsetByCodePoints(result.length(), -1));
        if (result.isEmpty()) result.append("download");
        return result + suffix + extension;
    }
}
