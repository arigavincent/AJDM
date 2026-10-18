package io.ajdm.ui;

import java.util.Locale;

final class Display {
    private Display() { }
    static String mib(long bytes) { return bytes < 0 ? "Unknown size" : String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0); }
    static String speed(double bytes) { return String.format(Locale.ROOT, "%.2f MiB/s", bytes / 1048576.0); }
    static String eta(long seconds) {
        if (seconds < 0) return "—";
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    static String state(String state) { return state.charAt(0) + state.substring(1).toLowerCase(Locale.ROOT); }

    // IDM's wording -------------------------------------------------------------------------------------------------
    /** "734.00 MB", "512.0 KB", "900 Bytes", like IDM's list (binary units, IDM's labels). */
    static String size(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " Bytes";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
    /** "1.250 MB/sec", "84.3 KB/sec"; blank when nothing is moving. */
    static String rate(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        if (bytesPerSecond < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB/sec", bytesPerSecond / 1024.0);
        return String.format(Locale.ROOT, "%.3f MB/sec", bytesPerSecond / 1048576.0);
    }
    /** "45 sec", "5 min 23 sec", "2 hour(s) 3 min"; blank when unknown. */
    static String timeLeft(long seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + " sec";
        if (seconds < 3600) return seconds / 60 + " min " + seconds % 60 + " sec";
        if (seconds < 86_400) return seconds / 3600 + " hour(s) " + seconds / 60 % 60 + " min";
        return seconds / 86_400 + " day(s) " + seconds / 3600 % 24 + " hour(s)";
    }
    /** "45.32%" of a known size; blank when the size is unknown. */
    static String percent(double fraction) { return fraction < 0 ? "" : String.format(Locale.ROOT, "%.2f%%", fraction * 100); }
    /** The Status column: IDM shows the percentage while transferring and a word otherwise. */
    static String status(DownloadRow row) {
        return switch (row.state()) {
            case "COMPLETED" -> "Complete";
            case "DOWNLOADING" -> row.reading().fraction() < 0 ? "Receiving data" : percent(row.reading().fraction());
            case "PROBING" -> "Connecting";
            case "EXTRACTING" -> "Getting video info";
            case "MERGING" -> "Merging";
            case "VERIFYING" -> "Verifying";
            case "QUEUED" -> "Queued";
            case "PAUSED" -> row.written() > 0 && row.total() > 0 ? "Stopped " + percent(Math.min(1, (double) row.written() / row.total())) : "Stopped";
            case "FAILED" -> "Error";
            case "CANCELED" -> "Canceled";
            default -> state(row.state());
        };
    }
}
