package photos

import photos.api.bucketEnd
import photos.api.bucketStart
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bucket boundaries must be half-open [start, end): a photo's createdDate belongs to exactly one
 * bucket. Regression guard for the duplicate-at-boundary bug (inclusive end vs. exclusive
 * next-bucket start) and for cross-timezone boundary drift (all math runs in one server zone).
 */
class BucketBoundaryTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `bucketEnd of one bucket equals bucketStart of the next, for every granularity`() {
        // 2023-06-15 10:30 UTC
        val t = ms("2023-06-15T10:30:00Z")
        for (g in listOf("year", "month", "day")) {
            val end = bucketEnd(t, g, zone)
            assertEquals(
                bucketStart(end, g, zone), end,
                "$g: bucketEnd must be a bucket boundary (start of the next bucket)",
            )
            // The exclusive end belongs to the NEXT bucket, never the current one.
            assertTrue(bucketStart(end, g, zone) > bucketStart(t, g, zone), "$g: end is in the next bucket")
        }
    }

    @Test
    fun `the millisecond before bucketEnd is still in the same bucket`() {
        val t = ms("2023-06-15T10:30:00Z")
        for (g in listOf("year", "month", "day")) {
            val end = bucketEnd(t, g, zone)
            assertEquals(
                bucketStart(t, g, zone), bucketStart(end - 1, g, zone),
                "$g: last ms before end shares the bucket with t",
            )
        }
    }
}
