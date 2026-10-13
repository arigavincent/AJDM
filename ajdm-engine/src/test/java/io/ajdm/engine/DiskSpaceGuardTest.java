package io.ajdm.engine;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiskSpaceGuardTest {
    private static final long RESERVE = 64L * 1024 * 1024;

    @Test void admitsExactlyTheRequiredSpaceAndRejectsOneByteLess() throws Exception {
        DiskSpaceGuard.require(12345 + RESERVE, 12345);
        var error = assertThrows(DownloadException.class, () -> DiskSpaceGuard.require(12344 + RESERVE, 12345));
        assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE, error.code());
    }

    @Test void unknownSizeAndEmptyFilesStillRequireTheReserve() throws Exception {
        DiskSpaceGuard.require(RESERVE, -1);
        DiskSpaceGuard.require(RESERVE, 0);
        assertThrows(DownloadException.class, () -> DiskSpaceGuard.require(RESERVE - 1, -1));
        assertThrows(DownloadException.class, () -> DiskSpaceGuard.require(RESERVE - 1, 0));
    }

    @Test void impossibleHugeFileCannotOverflowIntoAnAdmission() throws Exception {
        assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE,
                assertThrows(DownloadException.class, () -> DiskSpaceGuard.require(Long.MAX_VALUE, Long.MAX_VALUE)).code());
        DiskSpaceGuard.require(Long.MAX_VALUE, Long.MAX_VALUE - RESERVE);
        assertThrows(DownloadException.class, () -> DiskSpaceGuard.require(Long.MAX_VALUE, Long.MAX_VALUE - RESERVE + 1));
    }

    @Test void invalidCapacityFailsClosedAndInvalidLengthIsRejected() {
        assertEquals(ErrorCode.DISK_WRITE_ERROR,
                assertThrows(DownloadException.class, () -> DiskSpaceGuard.require(-1, 10)).code());
        assertThrows(IllegalArgumentException.class, () -> DiskSpaceGuard.require(RESERVE, -2));
    }
}
