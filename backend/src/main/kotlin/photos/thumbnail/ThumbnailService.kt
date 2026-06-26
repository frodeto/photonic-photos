package photos.thumbnail

import photos.scan.ExifToolService
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension

/**
 * Generates ~256px JPEG thumbnails into the on-disk cache.
 *  - JPEG sources are read directly via ImageIO.
 *  - RAW sources (CR2/DNG) use the embedded preview extracted by exiftool, then downscaled.
 * Returns the thumbnail path, or null if no thumbnail could be produced.
 */
class ThumbnailService(
    private val thumbnailsDir: Path,
    private val exif: ExifToolService,
    private val maxEdge: Int = 256,
) {
    fun generate(file: Path, photoId: Int): Path? {
        val source: BufferedImage = readSource(file) ?: return null
        val scaled = scale(source, maxEdge)
        val out = thumbnailsDir.resolve("$photoId.jpg")
        return try {
            ImageIO.write(scaled, "jpg", out.toFile())
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun readSource(file: Path): BufferedImage? {
        val ext = file.extension.lowercase()
        return try {
            if (ext == "jpg" || ext == "jpeg") {
                ImageIO.read(file.toFile())
            } else {
                exif.extractPreviewJpeg(file)?.let { ImageIO.read(ByteArrayInputStream(it)) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun scale(src: BufferedImage, maxEdge: Int): BufferedImage {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val ratio = maxEdge.toDouble() / maxOf(w, h)
        if (ratio >= 1.0) return src
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        val dst = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, nw, nh, null)
        g.dispose()
        return dst
    }
}
