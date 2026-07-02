package photos

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import photos.db.Db
import photos.db.Photos
import photos.db.ScanJobs
import photos.db.ScanRoots
import photos.scan.ExifToolService
import photos.scan.Scanner
import photos.thumbnail.ThumbnailService
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end scanner test over a fixture folder of generated JPEGs. Runs without exiftool
 * (created_date falls back to file mtime; thumbnails come from ImageIO).
 */
class ScannerTest {
    private lateinit var photosDir: Path
    private lateinit var exif: ExifToolService
    private lateinit var scanner: Scanner

    private val thumbnailsDir get() = dataDir.resolve("thumbnails")
    private val previewsDir get() = dataDir.resolve("previews")

    @BeforeTest
    fun setup() {
        photosDir = Files.createTempDirectory("photonic-photos")

        // Db.init only ever connects once per JVM (to the shared dataDir below), so tests
        // share the DB file — wipe the tables to keep each test isolated.
        Db.init(dataDir)
        transaction {
            Photos.deleteAll()
            ScanJobs.deleteAll()
            ScanRoots.deleteAll()
        }

        exif = ExifToolService(exiftoolPath = "definitely-not-installed-exiftool")
        val thumbs = ThumbnailService(thumbnailsDir, exif, previewsDir = previewsDir)
        scanner = Scanner(exif, thumbs)
    }

    @AfterTest
    fun teardown() {
        photosDir.toFile().deleteRecursively()
    }

    companion object {
        // One data dir for the whole JVM: Db keeps connecting to the first URL it saw, so a
        // per-test temp dir would leave later tests pointing at a deleted path.
        private val dataDir: Path by lazy {
            Files.createTempDirectory("photonic-data").also {
                Files.createDirectories(it.resolve("thumbnails"))
                Files.createDirectories(it.resolve("previews"))
            }
        }
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

    @Test
    fun `HEIC and RAW extensions are indexed (metadata-only without exiftool)`() {
        writeJpeg("a.jpg", Color.RED)
        // Contents don't matter for the extension filter; without exiftool these index from
        // filesystem metadata and simply get no thumbnail.
        for (name in listOf("phone.HEIC", "canon.cr3", "nikon.nef", "sony.arw")) {
            Files.writeString(photosDir.resolve(name), "not really an image")
        }
        Files.writeString(photosDir.resolve("skip.txt"), "still ignored")

        scanner.startScan(photosDir.toString(), blocking = true)

        val rows = transaction { Photos.selectAll().map { it[Photos.fileName] to (it[Photos.thumbPath] != null) } }
        assertEquals(5, rows.size, "jpg + 4 raw/heic files should be indexed, not the .txt")
        assertTrue(rows.single { it.first == "a.jpg" }.second, "JPEG still gets an ImageIO thumbnail")
        assertTrue(rows.filter { it.first != "a.jpg" }.none { it.second }, "no thumbnails without exiftool")
    }

    @Test
    fun `re-indexing a changed file keeps its photo id and drops stale cached renders`() {
        writeJpeg("a.jpg", Color.RED)
        scanner.startScan(photosDir.toString(), blocking = true)
        val (id, mtimeBefore) = transaction {
            Photos.selectAll().single().let { it[Photos.id].value to it[Photos.fileMtime] }
        }
        // Simulate a lightbox preview rendered from the old content.
        val preview = previewsDir.resolve("$id.jpg")
        Files.writeString(preview, "stale")

        // Change the content and push mtime clearly past the original so the file re-indexes.
        writeJpeg("a.jpg", Color.GREEN)
        Files.setLastModifiedTime(photosDir.resolve("a.jpg"), FileTime.fromMillis(mtimeBefore + 5_000))

        scanner.startScan(photosDir.toString(), blocking = true)

        val row = transaction { Photos.selectAll().single() }
        assertEquals(id, row[Photos.id].value, "photo id must survive re-indexing a changed file")
        assertEquals(mtimeBefore + 5_000, row[Photos.fileMtime])
        assertTrue(Files.notExists(preview), "stale cached preview must be invalidated")
        val thumb = row[Photos.thumbPath]
        assertTrue(thumb != null && Files.exists(Path.of(thumb)), "fresh thumbnail should be rendered")
    }

    @Test
    fun `files deleted from disk are removed on rescan, along with their cached renders`() {
        writeJpeg("keep.jpg", Color.RED)
        writeJpeg("gone.jpg", Color.BLUE)
        scanner.startScan(photosDir.toString(), blocking = true)
        val goneThumb = transaction {
            Photos.selectAll().single { it[Photos.fileName] == "gone.jpg" }[Photos.thumbPath]
        }
        assertTrue(goneThumb != null && Files.exists(Path.of(goneThumb)))

        Files.delete(photosDir.resolve("gone.jpg"))
        scanner.startScan(photosDir.toString(), blocking = true)

        val names = transaction { Photos.selectAll().map { it[Photos.fileName] } }
        assertEquals(listOf("keep.jpg"), names, "row for the deleted file should be removed")
        assertTrue(Files.notExists(Path.of(goneThumb)), "orphaned thumbnail must be deleted")
    }

    @Test
    fun `a second scan request for a busy root returns the running job instead of racing it`() {
        writeJpeg("a.jpg", Color.RED)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Gate thumbnail generation so the first scan reliably sits "running" while we probe.
        val gatedThumbs = object : ThumbnailService(thumbnailsDir, exif, previewsDir = previewsDir) {
            override fun generate(file: Path, photoId: Int, orientation: Int?): Path? {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return super.generate(file, photoId, orientation)
            }
        }
        val gatedScanner = Scanner(exif, gatedThumbs)

        val first = gatedScanner.startScan(photosDir.toString())
        assertTrue(entered.await(10, TimeUnit.SECONDS), "first scan should be underway")
        val second = gatedScanner.startScan(photosDir.toString())
        assertEquals(first, second, "a busy root must return the running job id, not start a new scan")

        release.countDown()
        waitForJob(first)
        val third = gatedScanner.startScan(photosDir.toString(), blocking = true)
        assertNotEquals(first, third, "once the scan finished, a new one may start")
    }

    @Test
    fun `an unreadable subdirectory is skipped and counted, not fatal to the scan`() {
        writeJpeg("ok.jpg", Color.RED)
        writeJpeg("hidden.jpg", Color.BLUE, subdir = "locked")
        val locked = photosDir.resolve("locked")
        try {
            try {
                Files.setPosixFilePermissions(locked, emptySet())
            } catch (e: UnsupportedOperationException) {
                return // non-POSIX filesystem — nothing to exercise
            }
            // Running as root the chmod doesn't actually block reads; then this test can't
            // exercise the failure path, so bail out rather than assert the wrong thing.
            val stillReadable = runCatching { Files.newDirectoryStream(locked).use { } }.isSuccess
            if (stillReadable) return

            val jobId = scanner.startScan(photosDir.toString(), blocking = true)
            val job = transaction { ScanJobs.selectAll().where { ScanJobs.id eq jobId }.single() }
            assertEquals("done", job[ScanJobs.state], "scan must survive an unreadable subfolder")
            assertTrue(job[ScanJobs.errors] > 0, "the skipped subfolder should be counted as an error")
            val names = transaction { Photos.selectAll().map { it[Photos.fileName] } }
            assertEquals(listOf("ok.jpg"), names)
        } finally {
            runCatching {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    private fun waitForJob(jobId: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = transaction {
                ScanJobs.selectAll().where { ScanJobs.id eq jobId }.single()[ScanJobs.state]
            }
            if (state != "running") return
            Thread.sleep(20)
        }
        error("job $jobId did not finish in time")
    }
}
