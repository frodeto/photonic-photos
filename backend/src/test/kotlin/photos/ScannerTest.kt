package photos

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import photos.db.Db
import photos.db.Photos
import photos.db.ScanRoots
import photos.scan.ExifToolService
import photos.scan.Scanner
import photos.thumbnail.ThumbnailService
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end scanner test over a fixture folder of generated JPEGs. Runs without exiftool
 * (created_date falls back to file mtime; thumbnails come from ImageIO).
 */
class ScannerTest {
    private lateinit var dataDir: Path
    private lateinit var photosDir: Path
    private lateinit var scanner: Scanner

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("photonic-data")
        photosDir = Files.createTempDirectory("photonic-photos")
        Files.createDirectories(dataDir.resolve("thumbnails"))

        // Init the schema against an isolated temp SQLite DB.
        Db.init(dataDir)

        val exif = ExifToolService(exiftoolPath = "definitely-not-installed-exiftool")
        val thumbs = ThumbnailService(dataDir.resolve("thumbnails"), exif)
        scanner = Scanner(exif, thumbs)
    }

    @AfterTest
    fun teardown() {
        photosDir.toFile().deleteRecursively()
        dataDir.toFile().deleteRecursively()
    }

    private fun writeJpeg(name: String, color: Color, subdir: String? = null) {
        val dir = if (subdir != null) photosDir.resolve(subdir).also { Files.createDirectories(it) } else photosDir
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, 640, 480)
        g.dispose()
        ImageIO.write(img, "jpg", dir.resolve(name).toFile())
    }

    @Test
    fun `scans recursively, indexes metadata, generates thumbnails, and is incremental`() {
        writeJpeg("a.jpg", Color.RED)
        writeJpeg("b.JPEG", Color.GREEN)
        writeJpeg("nested.jpg", Color.BLUE, subdir = "2021/trip")
        // Non-photo file should be ignored.
        Files.writeString(photosDir.resolve("notes.txt"), "ignore me")

        val jobId = scanner.startScan(photosDir.toString(), blocking = true)
        assertTrue(jobId > 0)

        val rows = transaction { Photos.selectAll().map { it[Photos.fileName] } }
        assertEquals(3, rows.size, "should index 3 photos (jpg/JPEG, recursive), not the .txt")

        // Thumbnails generated via ImageIO for JPEGs.
        val withThumbs = transaction {
            Photos.selectAll().count { it[Photos.thumbPath] != null }
        }
        assertEquals(3, withThumbs, "each JPEG should get a thumbnail")
        transaction {
            Photos.selectAll().forEach {
                val tp = it[Photos.thumbPath]
                assertTrue(tp != null && Files.exists(Path.of(tp)), "thumbnail file should exist")
            }
        }

        // createdDate falls back to a real timestamp (mtime) when no EXIF.
        transaction {
            Photos.selectAll().forEach { assertTrue(it[Photos.createdDate] > 0) }
        }

        // One scan root registered.
        val rootCount = transaction { ScanRoots.selectAll().count() }
        assertEquals(1, rootCount)

        // Incremental: a second scan with no changes indexes 0 new files.
        scanner.startScan(photosDir.toString(), blocking = true)
        val countAfter = transaction { Photos.selectAll().count() }
        assertEquals(3, countAfter, "re-scan should not duplicate rows")
    }
}
