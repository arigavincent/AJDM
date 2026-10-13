package io.ajdm.model;

public enum ErrorCode {
    UNSUPPORTED_SCHEME("AJDM-1001", "Only HTTP and HTTPS links are supported.", "Check the link."),
    INVALID_URL("AJDM-1002", "The link is not valid.", "Check the link."),
    INVALID_REQUEST("AJDM-1003", "The command is not valid.", "Check ajdm --help."),
    INVALID_STATE("AJDM-1004", "This download cannot perform that action.", "Check its state with ajdm list."),
    FEATURE_UNAVAILABLE("AJDM-5003", "This feature is not available in this build.", "Use the supported commands in ajdm --help."),
    ACCESS_DENIED("AJDM-2002", "Access to this file was denied.", "Sign in or provide credentials."),
    AUTH_REQUIRED("AJDM-2010", "This download needs credentials.", "Sign in or provide credentials."),
    TLS_VERIFICATION_FAILED("AJDM-2011", "The server's secure identity could not be verified.", "Verify the link and contact the site administrator."),
    NOT_FOUND("AJDM-2001", "The server could not find this file.", "Verify the link."),
    PROTOCOL_VIOLATION("AJDM-3004", "The server sent inconsistent data.", "Retry or report the host."),
    REFRESH_FAILED("AJDM-4002", "The expired link could not be renewed.", "Retry from the page link."),
    REFRESH_TIMEOUT("AJDM-4002", "Renewing the expired link timed out.", "Retry from the page link."),
    REFRESH_EXHAUSTED("AJDM-4002", "The link renewal limit was reached.", "Retry from the page link."),
    REFRESH_IDENTITY_UNVERIFIED("AJDM-4002", "The renewed link could not be verified as the same file.", "Keep the partial file or start a new download."),
    REFRESH_UNSUPPORTED("AJDM-4002", "The renewed link cannot safely resume this file.", "Keep the partial file or start a new download."),
    SOURCE_CHANGED("AJDM-3001", "The file changed on the server.", "Restart or keep the partial file."),
    RETRIES_EXHAUSTED("AJDM-3002", "The download retry limit was reached.", "Check the connection and retry."),
    INSUFFICIENT_DISK_SPACE("AJDM-2003", "Not enough free disk space.", "Free space or choose another folder."),
    DISK_WRITE_ERROR("AJDM-2004", "Could not write to disk.", "Choose another folder or check the disk."),
    TARGET_EXISTS("AJDM-2007", "The destination already exists.", "Choose another filename."),
    CHECKSUM_MISMATCH("AJDM-3005", "The file failed checksum verification.", "Download the file again."),
    CANCELED("AJDM-9002", "The download was canceled.", "Start a new download if needed."),
    PAUSED("AJDM-9001", "The download is paused.", "Resume when ready."),
    STATE_STORE_ERROR("AJDM-6001", "Could not access the download state.", "Check the state folder and retry."),
    STATE_IN_USE("AJDM-6002", "Another AJDM instance is using this state.", "Use the running instance or close it first."),
    PART_FILE_MISSING("AJDM-2005", "The partial file is missing.", "Start a new download."),
    PART_FILE_TRUNCATED("AJDM-2006", "The partial file lost saved bytes.", "Start a new download."),
    RANGE_UNSUPPORTED("AJDM-3003", "This server does not support resume.", "An interrupted download will restart."),
    EXTRACTION_FAILED("AJDM-4001", "Could not read the media formats.", "Update the tools and retry."),
    MEDIA_ACCESS_BLOCKED("AJDM-4006", "The site did not expose this post's media formats.", "Check that the post opens in your browser, select that browser or a cookie file in Settings > Media site access, then analyse again."),
    MEDIA_AUTH_REQUIRED("AJDM-4007", "This media needs a signed-in session.", "Select your signed-in browser or a cookie file in Settings > Media site access, then analyse again."),
    MEDIA_SESSION_FAILED("AJDM-4008", "Could not read the selected browser session.", "Check that browser is installed and accessible, or choose an exported cookie file in Settings > Media site access."),
    PLAYLIST_REQUIRED("AJDM-4005", "This link contains multiple videos.", "Open it in the playlist picker."),
    MUX_FAILED("AJDM-4003", "Could not combine the downloaded streams.", "Open the log; the streams are kept."),
    DRM_PROTECTED("AJDM-4004", "This media is DRM-protected.", "Downloading protected media is not supported."),
    IPC_UNAVAILABLE("AJDM-5001", "AJDM is not running.", "Start AJDM.");

    private final String publicCode, message, action;

    ErrorCode(String publicCode, String message, String action) {
        this.publicCode = publicCode; this.message = message; this.action = action;
    }
    public String publicCode() { return publicCode; }
    public String message() { return message; }
    public String action() { return action; }
    /** Catalog-owned text only: exception details may contain credentials or signed URLs. */
    public String userMessage() { return publicCode + ": " + message + " " + action; }
}
