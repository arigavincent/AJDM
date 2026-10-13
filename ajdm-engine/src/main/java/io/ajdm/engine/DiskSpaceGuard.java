package io.ajdm.engine;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;

final class DiskSpaceGuard {
    private static final long RESERVE = 64L * 1024 * 1024;
    private DiskSpaceGuard() {}

    static void require(long usable, long size) throws DownloadException {
        if (size < -1) throw new IllegalArgumentException("Invalid source length");
        if (usable < 0) throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Could not determine usable disk space");
        // Subtraction after the reserve check avoids overflowing size + RESERVE.
        if (usable < RESERVE || size >= 0 && usable - RESERVE < size) {
            throw new DownloadException(ErrorCode.INSUFFICIENT_DISK_SPACE, "Destination has insufficient usable space");
        }
    }
}
