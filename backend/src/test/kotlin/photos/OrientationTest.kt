package photos

import photos.scan.ExifToolService
import photos.thumbnail.ThumbnailService
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the EXIF Orientation handling that keeps thumbnails upright. Each source pixel gets a
 * unique color so transforms can be checked exactly (90°/flip maps pixels 1:1, so this is lossless).
 */
class OrientationTest {
    private val dir = Files.createTempDirectory("orient-test")
    private val svc = ThumbnailService(dir, ExifToolService(exiftoolPath = "unused"))

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    // 3 wide x 2 tall; color encodes (x,y) uniquely.
    private fun sample(): BufferedImage {
        val img = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 3) for (y in 0 until 2) img.setRGB(x, y, (10 + x * 10) shl 16 or ((10 + y * 10) shl 8))
        return img
    }

    private fun assertSamePixels(a: BufferedImage, b: BufferedImage, msg: String) {
        assertEquals(a.width, b.width, "$msg width")
        assertEquals(a.height, b.height, "$msg height")
        for (x in 0 until a.width) for (y in 0 until a.height) {
            assertEquals(a.getRGB(x, y), b.getRGB(x, y), "$msg pixel ($x,$y)")
        }
    }

    @Test
    fun `orientation 1 and null are no-ops`() {
        val src = sample()
        assertSamePixels(src, svc.orient(src, 1), "orientation 1")
        assertSamePixels(src, svc.orient(src, null), "null orientation")
    }

    @Test
    fun `orientation 3 rotates 180`() {
        val src = sample()
        val out = svc.orient(src, 3)
        assertEquals(3, out.width); assertEquals(2, out.height)
        for (x in 0 until 3) for (y in 0 until 2) {
            assertEquals(src.getRGB(x, y), out.getRGB(2 - x, 1 - y), "180 ($x,$y)")
        }
    }

    @Test
    fun `orientation 6 rotates 90 clockwise and swaps dimensions`() {
        val src = sample()
        val out = svc.orient(src, 6)
        assertEquals(2, out.width, "swapped width"); assertEquals(3, out.height, "swapped height")
        // 90° CW: source (x,y) -> dest (h-1-y, x)
        for (x in 0 until 3) for (y in 0 until 2) {
            assertEquals(src.getRGB(x, y), out.getRGB(1 - y, x), "90CW ($x,$y)")
        }
    }

    @Test
    fun `orientation 8 rotates 90 counter-clockwise and swaps dimensions`() {
        val src = sample()
        val out = svc.orient(src, 8)
        assertEquals(2, out.width); assertEquals(3, out.height)
        // 90° CCW: source (x,y) -> dest (y, w-1-x)
        for (x in 0 until 3) for (y in 0 until 2) {
            assertEquals(src.getRGB(x, y), out.getRGB(y, 2 - x), "90CCW ($x,$y)")
        }
    }
}
