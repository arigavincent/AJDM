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
}
