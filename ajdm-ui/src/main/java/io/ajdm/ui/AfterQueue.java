package io.ajdm.ui;

import java.util.List;
import java.util.Locale;

/**
 * IDM's "when the downloads finish" choice. It is used once: after it runs (or is canceled) it goes back to Nothing.
 * Sleep and shut down use the operating system's own commands, always after a visible countdown that can be canceled.
 */
enum AfterQueue {
    NOTHING("Do nothing"), EXIT("Close AJDM"), SLEEP("Put the computer to sleep"), SHUTDOWN("Shut down the computer");

    final String label;
    AfterQueue(String label) { this.label = label; }

    /** The command for this action on {@code os} (System.getProperty("os.name")), or an empty list when none is needed. */
    static List<String> command(AfterQueue action, String os) {
        String name = os.toLowerCase(Locale.ROOT);
        boolean windows = name.contains("win"), mac = name.contains("mac");
        return switch (action) {
            case SHUTDOWN -> windows ? List.of("shutdown.exe", "/s", "/t", "0") : mac ? List.of("osascript", "-e", "tell application \"System Events\" to shut down")
                    : List.of("systemctl", "poweroff");
            case SLEEP -> windows ? List.of("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0") : mac ? List.of("pmset", "sleepnow")
                    : List.of("systemctl", "suspend");
            default -> List.of();
        };
    }
}
