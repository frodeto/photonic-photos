package photos.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import photos.db.Photos
import photos.db.ScanJobs
import photos.db.ScanRoots
import photos.thumbnail.ThumbnailService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import kotlin.io.path.extension

/**
 * Recursively scans a root folder for photos and indexes their metadata.
 *
 * Incremental: a file already in the DB with matching (size, mtime) is skipped, so re-scans
 * are cheap. New or changed files are (re)indexed. Reading is strictly read-only — originals
 * are never modified or moved.
 */
class Scanner(
    private val exif: ExifToolService,
    private val thumbs: ThumbnailService,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(Scanner::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extensions = setOf("jpg", "jpeg", "cr2", "dng")
    private val batchSize = 100

    /**
     * Registers/refreshes the root and returns the scan job id immediately.
     * When [blocking] is true (tests) the scan runs inline before returning.
     */
    fun startScan(rootPath: String, blocking: Boolean = false): Int {
        val root = Path.of(rootPath).toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Not a directory: $root" }

        val now = clock()
        val rootId = transaction {
            val existing = ScanRoots.selectAll().where { ScanRoots.path eq root.toString() }.firstOrNull()
            existing?.get(ScanRoots.id)?.value ?: ScanRoots.insertAndGetId {
                it[path] = root.toString()
                it[addedAt] = now
            }.value
        }
        val jobId = transaction {
            ScanJobs.insertAndGetId {
                it[ScanJobs.rootId] = rootId
                it[state] = "running"
                it[startedAt] = now
            }.value
        }

        if (blocking) {
            runScan(rootId, root, jobId)
        } else {
            scope.launch { runScan(rootId, root, jobId) }
        }
        return jobId
    }

    private fun runScan(rootId: Int, root: Path, jobId: Int) {
        try {
            val files = Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.extension.lowercase() in extensions }
                    .collect(Collectors.toList())
            }
            setSeen(jobId, files.size)

            val toIndex = files.filter { needsIndex(it) }

            var indexed = 0
            var errors = 0
            for (chunk in toIndex.chunked(batchSize)) {
                val exifMap = exif.readBatch(chunk)
                for (file in chunk) {
                    try {
                        indexOne(rootId, file, exifMap[file])
                        indexed++
                    } catch (e: Exception) {
                        log.warn("failed to index $file: ${e.message}")
                        errors++
                    }
                }
                setProgress(jobId, indexed, errors)
            }

            transaction {
                ScanRoots.update({ ScanRoots.id eq rootId }) { it[lastScanAt] = clock() }
                ScanJobs.update({ ScanJobs.id eq jobId }) {
                    it[state] = "done"
                    it[finishedAt] = clock()
                    it[filesIndexed] = indexed
                    it[ScanJobs.errors] = errors
                }
            }
            log.info("scan #$jobId done: ${files.size} seen, $indexed indexed, $errors errors")
        } catch (e: Exception) {
            log.error("scan #$jobId failed", e)
            transaction {
                ScanJobs.update({ ScanJobs.id eq jobId }) {
                    it[state] = "failed"
                    it[finishedAt] = clock()
                    it[errorSummary] = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    private fun needsIndex(file: Path): Boolean {
        val attrs = readAttrs(file) ?: return false
        return transaction {
            val existing = Photos.selectAll().where { Photos.filePath eq file.toString() }.firstOrNull()
            existing == null ||
                existing[Photos.fileSize] != attrs.size() ||
                existing[Photos.fileMtime] != attrs.lastModifiedTime().toMillis()
        }
    }

    private fun indexOne(rootId: Int, file: Path, data: ExifData?) {
        val attrs = readAttrs(file) ?: return
        val mtime = attrs.lastModifiedTime().toMillis()
        val created = data?.createdDateMs ?: mtime
        val status = when {
            data == null && !exif.available -> "no_exif"
            data?.hadExif == true -> "ok"
            else -> "no_exif"
        }

        val photoId = transaction {
            // upsert by path: replace any stale row, then insert fresh
            Photos.deleteWhere { Photos.filePath eq file.toString() }
            Photos.insertAndGetId {
                it[Photos.rootId] = rootId
                it[filePath] = file.toString()
                it[fileName] = file.fileName.toString()
                it[fileSize] = attrs.size()
                it[fileMtime] = mtime
                it[createdDate] = created
                it[cameraMake] = data?.cameraMake
                it[cameraModel] = data?.cameraModel
                it[lens] = data?.lens
                it[shutterSpeed] = data?.shutterSpeed
                it[aperture] = data?.aperture
                it[focalLength] = data?.focalLength
                it[focalLength35] = data?.focalLength35
                it[gpsLat] = data?.gpsLat
                it[gpsLon] = data?.gpsLon
                it[width] = data?.width
                it[height] = data?.height
                it[orientation] = data?.orientation
                it[indexedAt] = clock()
                it[exifStatus] = status
            }.value
        }

        val thumb = thumbs.generate(file, photoId, data?.orientation)
        if (thumb != null) {
            transaction {
                Photos.update({ Photos.id eq photoId }) { it[thumbPath] = thumb.toString() }
            }
        }
    }

    private fun readAttrs(file: Path): BasicFileAttributes? = try {
        Files.readAttributes(file, BasicFileAttributes::class.java)
    } catch (e: Exception) {
        null
    }

    private fun setSeen(jobId: Int, seen: Int) = transaction {
        ScanJobs.update({ ScanJobs.id eq jobId }) { it[filesSeen] = seen }
    }

    private fun setProgress(jobId: Int, indexed: Int, errors: Int) = transaction {
        ScanJobs.update({ ScanJobs.id eq jobId }) {
            it[filesIndexed] = indexed
            it[ScanJobs.errors] = errors
        }
    }
}
