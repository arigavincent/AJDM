package io.ajdm.ui;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether newly copied text is a link worth offering, like IDM's clipboard monitor: a single HTTP(S) link
 * to a downloadable file, or a page on a supported video site. Ordinary web pages are ignored, and each link is
 * offered once.
 */
final class ClipboardWatcher {
    enum Kind { FILE, MEDIA }
    record Offer(String url, Kind kind) {}

    static final Set<String> FILE_TYPES = Set.of(
            "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "zst", "iso", "img", "dmg", "exe", "msi", "msix", "apk", "deb", "rpm",
            "appimage", "mp4", "mkv", "webm", "avi", "mov", "m4v", "mp3", "m4a", "flac", "wav", "ogg", "aac", "pdf", "epub", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx", "jar", "bin");
    static final Set<String> MEDIA_HOSTS = Set.of("youtube.com", "youtu.be", "tiktok.com", "facebook.com", "fb.watch", "instagram.com",
            "x.com", "twitter.com", "vimeo.com", "dailymotion.com");

    private String last;

    /** The link to offer for newly copied {@code text}, or null when it is not one or was already offered. */
    Offer offer(String text) {
        if (text == null) return null;
        String candidate = text.strip();
        if (candidate.equals(last)) return null;
        last = candidate;
        return classify(candidate);
    }

    static Offer classify(String text) {
        if (text.length() > 4096 || text.chars().anyMatch(Character::isWhitespace)) return null;
        URI uri;
        try { uri = new URI(text); } catch (java.net.URISyntaxException e) { return null; }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getUserInfo() != null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String site : MEDIA_HOSTS) if (host.equals(site) || host.endsWith("." + site)) return new Offer(text, Kind.MEDIA);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.'), slash = path.lastIndexOf('/');
        if (dot > slash && FILE_TYPES.contains(path.substring(dot + 1))) return new Offer(text, Kind.FILE);
        return null;
    }
}
