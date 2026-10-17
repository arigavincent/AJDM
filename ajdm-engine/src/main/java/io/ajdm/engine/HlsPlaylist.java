package io.ajdm.engine;

import io.ajdm.model.ByteRange;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict reader for one HLS media playlist (RFC 8216): the ordered parts AJDM downloads and joins, an optional
 * initialization section first. Only complete video-on-demand playlists of clear HTTPS parts are accepted; master
 * playlists, live or event playlists, and every encrypted or DRM-keyed stream are refused, never worked around.
 */
public record HlsPlaylist(List<Part> parts, double durationSeconds) {
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_PARTS = 100_000;

    /** One request: a whole resource, or {@code range} of it. */
    public record Part(URI uri, ByteRange range, boolean initialization) {
        @Override public String toString() { return "Part[range=" + range + ", initialization=" + initialization + ", uri=redacted]"; }
    }

    public HlsPlaylist { parts = List.copyOf(parts); }

    public static HlsPlaylist parse(String text, URI base) throws DownloadException {
        if (text.length() > MAX_BYTES) throw invalid("Playlist size limit");
        String[] lines = text.split("\r?\n|\r", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) first++;
        if (first == lines.length || !lines[first].strip().replace("﻿", "").equals("#EXTM3U")) throw invalid("Not an HLS playlist");
        var parts = new ArrayList<Part>();
        Part initialization = null;
        boolean ended = false, segmentSeen = false;
        double duration = 0, pendingDuration = -1;
        Pending pendingRange = null;
        Map<URI, Long> rangeEnds = new HashMap<>();
        for (int i = first + 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (!line.startsWith("#")) {
                if (pendingDuration < 0) throw invalid("Segment without duration");
                URI uri = resolve(base, line);
                ByteRange range = pendingRange == null ? null : place(pendingRange, uri, rangeEnds);
                parts.add(new Part(uri, range, false));
                if (parts.size() > MAX_PARTS) throw invalid("Segment count limit");
                duration += pendingDuration; pendingDuration = -1; pendingRange = null; segmentSeen = true;
                continue;
            }
            int colon = line.indexOf(':');
            String tag = colon < 0 ? line : line.substring(0, colon), value = colon < 0 ? "" : line.substring(colon + 1);
            switch (tag) {
                case "#EXT-X-STREAM-INF", "#EXT-X-I-FRAME-STREAM-INF" ->
                        throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Master playlist: select one variant stream");
                case "#EXT-X-KEY", "#EXT-X-SESSION-KEY" -> key(attributes(value));
                case "#EXT-X-MAP" -> {
                    var attributes = attributes(value);
                    String uri = attributes.get("URI");
                    if (uri == null) throw invalid("Initialization section without URI");
                    URI resolved = resolve(base, uri);
                    ByteRange range = attributes.containsKey("BYTERANGE") ? place(byteRange(attributes.get("BYTERANGE"), true), resolved, rangeEnds) : null;
                    var map = new Part(resolved, range, true);
                    if (initialization != null && !initialization.equals(map) || initialization == null && segmentSeen) {
                        throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Streams that change initialization section");
                    }
                    initialization = map;
                }
                case "#EXTINF" -> {
                    String seconds = value.split(",", 2)[0].strip();
                    try { pendingDuration = Double.parseDouble(seconds); }
                    catch (NumberFormatException e) { throw invalid("Invalid segment duration"); }
                    if (!Double.isFinite(pendingDuration) || pendingDuration < 0) throw invalid("Invalid segment duration");
                }
                case "#EXT-X-BYTERANGE" -> pendingRange = byteRange(value, false);
                case "#EXT-X-ENDLIST" -> ended = true;
                default -> { /* Informational tags (version, target duration, discontinuity, ...) do not change the bytes. */ }
            }
        }
        if (!ended) throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Live streams are not supported");
        if (parts.isEmpty()) throw invalid("Playlist has no segments");
        if (initialization != null) parts.addFirst(initialization);
        return new HlsPlaylist(parts, duration);
    }

    private static void key(Map<String, String> attributes) throws DownloadException {
        String method = attributes.getOrDefault("METHOD", "").toUpperCase(Locale.ROOT);
        if (method.equals("NONE")) return;
        String format = attributes.getOrDefault("KEYFORMAT", "identity");
        if (!method.equals("AES-128") || !format.equals("identity")) {
            throw new DownloadException(ErrorCode.DRM_PROTECTED, "Protected stream");
        }
        throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Encrypted HLS streams are not supported yet");
    }

    /** A {@code n[@o]} range before placement: offset {@code -1} when omitted. */
    private record Pending(long offset, long length) {}

    /** A range with an omitted offset continues where the previous range of the same resource ended. */
    private static ByteRange place(Pending range, URI uri, Map<URI, Long> ends) throws DownloadException {
        long start = range.offset();
        if (start < 0) {
            Long previous = ends.get(uri);
            if (previous == null) throw invalid("Byte range without offset or predecessor");
            start = previous;
        }
        long length = range.length();
        if (length <= 0 || start > Long.MAX_VALUE - length) throw invalid("Invalid byte range");
        ends.put(uri, start + length);
        return new ByteRange(start, start + length);
    }

    private static Pending byteRange(String value, boolean offsetRequired) throws DownloadException {
        String[] pieces = value.strip().split("@", -1);
        try {
            long length = Long.parseLong(pieces[0]);
            if (pieces.length > 2 || length <= 0) throw invalid("Invalid byte range");
            if (pieces.length == 1) {
                if (offsetRequired) throw invalid("Initialization byte range without offset");
                return new Pending(-1, length);
            }
            long offset = Long.parseLong(pieces[1]);
            if (offset < 0) throw invalid("Invalid byte range");
            return new Pending(offset, length);
        } catch (NumberFormatException e) { throw invalid("Invalid byte range"); }
    }
    private static URI resolve(URI base, String reference) throws DownloadException {
        URI uri;
        try { uri = base.resolve(reference.strip()); }
        catch (IllegalArgumentException e) { throw invalid("Invalid segment link"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw invalid("Segments must use HTTPS");
        }
        if (uri.getFragment() != null) uri = URI.create(uri.toString().split("#", 2)[0]);
        return uri;
    }

    /** RFC 8216 attribute list: comma-separated NAME=value, values either quoted strings or bare tokens. */
    static Map<String, String> attributes(String text) throws DownloadException {
        var result = new HashMap<String, String>();
        int i = 0, n = text.length();
        while (i < n) {
            int equals = text.indexOf('=', i);
            if (equals < 0) throw invalid("Invalid attribute list");
            String name = text.substring(i, equals).strip();
            if (!name.matches("[A-Z0-9-]+")) throw invalid("Invalid attribute name");
            String value;
            int next;
            if (equals + 1 < n && text.charAt(equals + 1) == '"') {
                int close = text.indexOf('"', equals + 2);
                if (close < 0) throw invalid("Unterminated attribute");
                value = text.substring(equals + 2, close);
                next = close + 1;
                if (next < n && text.charAt(next) != ',') throw invalid("Invalid attribute list");
            } else {
                int comma = text.indexOf(',', equals + 1);
                next = comma < 0 ? n : comma;
                value = text.substring(equals + 1, next).strip();
            }
            if (result.put(name, value) != null) throw invalid("Duplicate attribute");
            i = next + 1;
        }
        return result;
    }

    private static DownloadException invalid(String detail) { return new DownloadException(ErrorCode.PROTOCOL_VIOLATION, detail); }
}
