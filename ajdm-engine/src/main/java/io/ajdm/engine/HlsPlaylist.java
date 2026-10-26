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
 * initialization section first. Complete video-on-demand playlists of HTTPS parts are accepted, clear or encrypted
 * with AES-128 whose key the site serves to any player (KEYFORMAT identity); master playlists, live or event
 * playlists, and DRM systems (SAMPLE-AES, Widevine, FairPlay, PlayReady or any other key format) are refused.
 */
public record HlsPlaylist(List<Part> parts, double durationSeconds) {
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_PARTS = 100_000;

    /**
     * One request: a whole resource, or {@code range} of it. {@code key} is the AES-128 key that encrypts it (null when
     * clear); {@code sequence} is its media sequence number, the default IV.
     */
    public record Part(URI uri, ByteRange range, boolean initialization, Key key, long sequence) {
        public Part(URI uri, ByteRange range, boolean initialization) { this(uri, range, initialization, null, -1); }
        @Override public String toString() {
            return "Part[range=" + range + ", initialization=" + initialization + ", encrypted=" + (key != null) + ", uri=redacted]";
        }
        /** The 16-byte CBC initialization vector: the key's explicit IV, else the sequence number big-endian. */
        public byte[] iv() {
            if (key.iv() != null) return java.util.HexFormat.of().parseHex(key.iv());
            return java.nio.ByteBuffer.allocate(16).putLong(8, sequence).array();
        }
    }
    /** An AES-128 key served at {@code uri}; {@code iv} is 32 hex digits or null to use the media sequence number. */
    public record Key(URI uri, String iv) {
        @Override public String toString() { return "Key[explicitIv=" + (iv != null) + ", uri=redacted]"; }
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
        Key key = null;
        long sequence = 0;
        for (int i = first + 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (!line.startsWith("#")) {
                if (pendingDuration < 0) throw invalid("Segment without duration");
                URI uri = resolve(base, line);
                ByteRange range = pendingRange == null ? null : place(pendingRange, uri, rangeEnds);
                parts.add(new Part(uri, range, false, key, sequence++));
                if (parts.size() > MAX_PARTS) throw invalid("Segment count limit");
                duration += pendingDuration; pendingDuration = -1; pendingRange = null; segmentSeen = true;
                continue;
            }
            int colon = line.indexOf(':');
            String tag = colon < 0 ? line : line.substring(0, colon), value = colon < 0 ? "" : line.substring(colon + 1);
            switch (tag) {
                case "#EXT-X-STREAM-INF", "#EXT-X-I-FRAME-STREAM-INF" ->
                        throw new DownloadException(ErrorCode.FEATURE_UNAVAILABLE, "Master playlist: select one variant stream");
                case "#EXT-X-KEY" -> key = key(attributes(value), base);
                case "#EXT-X-SESSION-KEY" -> key(attributes(value), base); // A preload hint; only its method is checked.
                case "#EXT-X-MEDIA-SEQUENCE" -> {
                    if (segmentSeen) throw invalid("Media sequence after the first segment");
                    try { sequence = Long.parseLong(value.strip()); }
                    catch (NumberFormatException e) { throw invalid("Invalid media sequence"); }
                    if (sequence < 0) throw invalid("Invalid media sequence");
                }
                case "#EXT-X-MAP" -> {
                    var attributes = attributes(value);
                    String uri = attributes.get("URI");
                    if (uri == null) throw invalid("Initialization section without URI");
                    URI resolved = resolve(base, uri);
                    ByteRange range = attributes.containsKey("BYTERANGE") ? place(byteRange(attributes.get("BYTERANGE"), true), resolved, rangeEnds) : null;
                    if (key != null && key.iv() == null) throw invalid("Encrypted initialization section without IV");
                    var map = new Part(resolved, range, true, key, -1);
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

    /** The key that applies from here on: null after METHOD=NONE; DRM methods and key formats are refused. */
    private static Key key(Map<String, String> attributes, URI base) throws DownloadException {
        String method = attributes.getOrDefault("METHOD", "").toUpperCase(Locale.ROOT);
        if (method.equals("NONE")) return null;
        String format = attributes.getOrDefault("KEYFORMAT", "identity");
        if (!method.equals("AES-128") || !format.equals("identity")) {
            throw new DownloadException(ErrorCode.DRM_PROTECTED, "Protected stream");
        }
        String uri = attributes.get("URI");
        if (uri == null) throw invalid("AES-128 key without URI");
        String iv = attributes.get("IV");
        if (iv != null) {
            if (!iv.matches("0[xX][0-9a-fA-F]{1,32}")) throw invalid("Invalid key IV");
            iv = "0".repeat(34 - iv.length()) + iv.substring(2).toLowerCase(Locale.ROOT);
        }
        return new Key(resolve(base, uri), iv);
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
