package ai.core.server.blob;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Read SAS windows are quantized so a polled media URL stays byte-identical between refreshes (a video src that
 * changes every 5 seconds reloads forever), while still being valid for at least the requested minutes.
 *
 * @author stephen
 */
class AzureBlobSasReadWindowTest {
    private static OffsetDateTime at(int minute, int second) {
        return OffsetDateTime.of(2026, 9, 5, 3, minute, second, 0, ZoneOffset.UTC);
    }

    @Test
    void callsInsideTheSameBucketShareTheWindow() {
        var first = AzureBlobSasService.readWindow(at(34, 40), 60);
        var second = AzureBlobSasService.readWindow(at(34, 49), 60);
        var third = AzureBlobSasService.readWindow(at(39, 59), 60);
        assertEquals(first[0], second[0]);
        assertEquals(first[1], second[1]);
        assertEquals(first[0], third[0]);
        assertEquals(at(25, 0), first[0], "bucket start 03:30 minus the 5-minute skew allowance");
        assertEquals(at(40, 0).plusMinutes(60), first[1], "next bucket boundary plus the requested validity");
    }

    @Test
    void theNextBucketRotatesTheUrlButValidityNeverDropsBelowTheRequest() {
        var before = AzureBlobSasService.readWindow(at(39, 59), 5);
        var after = AzureBlobSasService.readWindow(at(40, 0), 5);
        assertNotEquals(before[0], after[0]);
        // worst case: asked at the very end of a bucket, still >= 5 minutes left
        assertFalse(before[1].isBefore(at(39, 59).plusMinutes(5)), "validity must not drop below the request");
        assertFalse(after[1].isBefore(at(40, 0).plusMinutes(5)), "validity must not drop below the request");
    }
}
