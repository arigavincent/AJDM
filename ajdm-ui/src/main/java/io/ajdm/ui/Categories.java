package io.ajdm.ui;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** IDM-style categories by file type, used for the sidebar filter and optional per-category save folders. */
enum Categories {
    VIDEO("Video"), MUSIC("Music"), DOCUMENTS("Documents"), COMPRESSED("Compressed"), PROGRAMS("Programs"), OTHER("Other");

    private static final Map<Categories, Set<String>> TYPES = Map.of(
            VIDEO, Set.of("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "3gp", "mpg", "mpeg"),
            MUSIC, Set.of("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "wma"),
            DOCUMENTS, Set.of("pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "csv", "rtf"),
            COMPRESSED, Set.of("zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "zst", "iso", "img"),
            PROGRAMS, Set.of("exe", "msi", "msix", "apk", "deb", "rpm", "appimage", "dmg", "pkg", "jar", "bin"));

    final String label;
    Categories(String label) { this.label = label; }

    static Categories of(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tar.xz")) return COMPRESSED;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return OTHER;
        String ext = name.substring(dot + 1);
        for (var entry : TYPES.entrySet()) if (entry.getValue().contains(ext)) return entry.getKey();
        return OTHER;
    }

    /** Where a file of this name is saved when sorting by category is on; other files stay in the main folder. */
    static Path folder(Path base, String filename, boolean sort) {
        var category = of(filename);
        return sort && category != OTHER ? base.resolve(category.label) : base;
    }
}
