package photos.thumbnail

import photos.scan.ExifToolService
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.extension

/**
 * Generates downscaled JPEGs into the on-disk cache.
 *  - JPEG sources are read directly via ImageIO.
 *  - RAW sources (CR2/DNG) use the embedded preview extracted by exiftool, then downscaled.
 *
 * Two sizes are produced from the same pipeline: a small [maxEdge] thumbnail for the strip
 * (generated eagerly during the scan) and a larger [previewMaxEdge] preview for the lightbox
 * (generated lazily on first request). Each returns its cache path, or null if nothing could
 * be rendered.
 */
class ThumbnailService(
    private val thumbnailsDir: Path,
    private val exif: ExifToolService,
    private val maxEdge: Int = 256,
    private val previewsDir: Path = thumbnailsDir,
    private val previewMaxEdge: Int = 1024,
) {
    fun generate(file: Path, photoId: Int, orientation: Int? = null): Path? =
        render(file, orientation, maxEdge, thumbnailsDir.resolve("$photoId.jpg"))

    /**
     * Returns the cached lightbox preview for [photoId], rendering it from [file] on the first
     * request. Unlike thumbnails — written once by the single scan thread — previews are produced
     * on demand from concurrent HTTP requests, so the write goes through a temp file + atomic move
     * to avoid two requests corrupting one another's output for the same id. Returns null if no
     * preview could be produced.
     */
    fun preview(file: Path, photoId: Int, orientation: Int? = null): Path? {
        val out = previewsDir.resolve("$photoId.jpg")
        if (Files.exists(out)) return out
        return render(file, orientation, previewMaxEdge, out, atomic = true)
    }

    private fun render(file: Path, orientation: Int?, maxEdge: Int, out: Path, atomic: Boolean = false): Path? {
        val source: BufferedImage = readSource(file) ?: return null
        // ImageIO.read does not apply the EXIF Orientation tag, so rotate/flip here before scaling
        // — otherwise photos shot in portrait (or any rotated camera) render sideways.
        val oriented = orient(source, orientation)
        val scaled = scale(oriented, maxEdge)
        return try {
            if (atomic) {
                val tmp = Files.createTempFile(out.parent, ".${out.fileName}", ".tmp")
                ImageIO.write(scaled, "jpg", tmp.toFile())
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } else {
                ImageIO.write(scaled, "jpg", out.toFile())
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Applies an EXIF Orientation (1..8) to [src], returning an upright image. Orientation 1
     * (and null/unknown) is a no-op. 90°/270° rotations and transpose/transverse swap width and
     * height. The transform maps pixels exactly (axis-aligned), so no quality is lost here.
     */
    internal fun orient(src: BufferedImage, orientation: Int?): BufferedImage {
        if (orientation == null || orientation in intArrayOf(1) || orientation !in 1..8) return src
        val w = src.width
        val h = src.height
        val swap = orientation in intArrayOf(5, 6, 7, 8)
        val dst = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_RGB)
        // Standard EXIF orientation transforms (m00, m10, m01, m11, m02, m12).
        val tx = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w.toDouble(), 0.0)        // flip horizontal
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w.toDouble(), h.toDouble()) // rotate 180
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble())        // flip vertical
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)                  // transpose
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h.toDouble(), 0.0)        // rotate 90 CW
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h.toDouble(), w.toDouble()) // transverse
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w.toDouble())        // rotate 270 CW
            else -> AffineTransform()
        }
        val g = dst.createGraphics()
        g.drawImage(src, tx, null)
        g.dispose()
        return dst
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
