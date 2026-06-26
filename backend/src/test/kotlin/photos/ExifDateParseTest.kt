package photos

import photos.scan.ExifToolService
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks in the EXIF date parser: timezone offsets of either sign (and fractional seconds) must
 * parse rather than throwing and falling back to file mtime. The offset is stripped and the
 * timestamp reinterpreted in the configured zone, so all forms below map to the same instant.
 */
class ExifDateParseTest {
    private val svc = ExifToolService(exiftoolPath = "unused", zone = ZoneId.of("UTC"))

    private val expected: Long =
        java.time.LocalDateTime.of(2023, 1, 1, 12, 0, 0)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun `parses plain, positive offset, negative offset, Z, and fractional seconds`() {
        assertEquals(expected, svc.parseExifDate("2023:01:01 12:00:00"), "plain")
        assertEquals(expected, svc.parseExifDate("2023:01:01 12:00:00+02:00"), "positive offset")
        assertEquals(expected, svc.parseExifDate("2023:01:01 12:00:00-05:00"), "negative offset")
        assertEquals(expected, svc.parseExifDate("2023:01:01 12:00:00Z"), "Z")
        assertEquals(expected, svc.parseExifDate("2023:01:01 12:00:00-0500"), "offset without colon")
        assertEquals(expected, svc.parseExifDate("2023:01:01 12:00:00.500-05:00"), "fractional + offset")
    }

    @Test
    fun `returns null for unparseable input`() {
        assertNull(svc.parseExifDate(""))
        assertNull(svc.parseExifDate("not a date"))
    }
}
