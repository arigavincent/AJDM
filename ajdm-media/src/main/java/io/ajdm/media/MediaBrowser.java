package io.ajdm.media;

import io.ajdm.model.*;
import java.util.Locale;

/** Explicit browser names only; no profile paths, keyring options or arbitrary tool arguments. */
public enum MediaBrowser {
    FIREFOX, CHROME, CHROMIUM, BRAVE, EDGE, OPERA, VIVALDI, WHALE, SAFARI;
    public String toolName() { return name().toLowerCase(Locale.ROOT); }
    public static MediaBrowser parse(String name) throws DownloadException {
        for (var browser : values()) if (browser.toolName().equals(name)) return browser;
        throw new DownloadException(ErrorCode.INVALID_REQUEST, "Unsupported browser session");
    }
}
