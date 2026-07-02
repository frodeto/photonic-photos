package photos

import photos.api.bucketEnd
import photos.api.bucketStart
import photos.api.fillBuckets
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

    @Test
    fun `fillBuckets emits zero buckets for gaps so the axis is temporally linear`() {
        val y2019 = bucketStart(ms("2019-06-01T12:00:00Z"), "year", zone)
        val y2023 = bucketStart(ms("2023-06-01T12:00:00Z"), "year", zone)
        val filled = fillBuckets(mapOf(y2019 to 3L, y2023 to 7L), "year", zone)
        assertEquals(5, filled.size, "2019..2023 inclusive")
        assertEquals(listOf(3L, 0L, 0L, 0L, 7L), filled.map { it.count })
        // Each bucket start must be the exact start of the next year (DST/leap safe).
        filled.zipWithNext().forEach { (a, b) ->
            assertEquals(bucketEnd(a.bucketStart, "year", zone), b.bucketStart)
        }
    }

    @Test
    fun `fillBuckets with a parent range covers the whole parent, not just the observed span`() {
        val yearStart = bucketStart(ms("2023-06-15T10:30:00Z"), "year", zone)
        val yearEnd = bucketEnd(yearStart, "year", zone)
        val march = bucketStart(ms("2023-03-10T00:00:00Z"), "month", zone)
        val filled = fillBuckets(mapOf(march to 4L), "month", zone, fromMs = yearStart, toMs = yearEnd)
        assertEquals(12, filled.size, "months of a year view always shows all 12 months")
        assertEquals(yearStart, filled.first().bucketStart)
        assertEquals(4L, filled.single { it.bucketStart == march }.count)
        assertEquals(11, filled.count { it.count == 0L })
    }

    @Test
    fun `fillBuckets is empty for no data and unbounded range`() {
        assertEquals(0, fillBuckets(emptyMap(), "month", zone).size)
    }
}
