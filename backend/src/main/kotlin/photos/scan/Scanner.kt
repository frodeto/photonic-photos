package photos.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import photos.db.Photos
import photos.db.ScanJobs
import photos.db.ScanRoots
import photos.thumbnail.ThumbnailService
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

/**
 * Recursively scans a root folder for photos and indexes their metadata.
 *
 * Incremental: a file already in the DB with matching (size, mtime) is skipped, so re-scans
 * are cheap. New or changed files are (re)indexed, keeping their photo id stable; rows whose
 * files have disappeared from disk are removed (with their cached thumbnails/previews).
 * Reading is strictly read-only — originals are never modified or moved.
 *
 * Only one scan runs per root at a time: a scan request for a root that is already being
 * scanned returns the running job's id instead of racing a second scan over the same rows.
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
    private val seenUpdateEvery = 500

    // Thumbnail rendering dominates scan time and is independent per file; bound the fan-out
    // so RAW files don't spawn unbounded exiftool processes.
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    )

    /** rootId → jobId of the scan currently running for that root. */
    private val runningRoots = ConcurrentHashMap<Int, Int>()

    /** What we already know about an indexed file, enough to decide if it needs re-indexing. */
    private data class KnownRow(val id: Int, val size: Long, val mtime: Long)

    /** True while a scan job is running for [rootId] (e.g. to refuse deleting the root mid-scan). */
    fun isScanning(rootId: Int): Boolean = runningRoots.containsKey(rootId)

    /**
     * Registers/refreshes the root and returns the scan job id immediately. If a scan is
     * already running for this root, returns that job's id instead of starting another.
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

        var claimed = false
        val jobId = runningRoots.computeIfAbsent(rootId) {
            claimed = true
            transaction {
                ScanJobs.insertAndGetId {
                    it[ScanJobs.rootId] = rootId
                    it[state] = "running"
                    it[startedAt] = now
                }.value
            }
        }
        if (!claimed) return jobId

        if (blocking) {
            runBlocking { runScan(rootId, root, jobId) }
        } else {
            scope.launch { runScan(rootId, root, jobId) }
        }
        return jobId
    }

    private suspend fun runScan(rootId: Int, root: Path, jobId: Int) {
        try {
            val (files, walkErrors) = walk(root, jobId)
            setSeen(jobId, files.size)

            // Everything already indexed under this root in one query — drives both the
            // changed-file check (instead of a per-file lookup) and missing-file cleanup.
            val known: Map<String, KnownRow> = transaction {
                Photos.select(Photos.id, Photos.filePath, Photos.fileSize, Photos.fileMtime)
                    .where { Photos.rootId eq rootId }
                    .associate {
                        it[Photos.filePath] to
                            KnownRow(it[Photos.id].value, it[Photos.fileSize], it[Photos.fileMtime])
                    }
            }

            val toIndex = files.mapNotNull { file ->
                val attrs = readAttrs(file) ?: return@mapNotNull null
                val existing = known[file.toString()]
                val changed = existing == null ||
                    existing.size != attrs.size() ||
                    existing.mtime != attrs.lastModifiedTime().toMillis()
                if (changed) file to existing?.id else null
            }

            var indexed = 0
            var errors = walkErrors
            for (chunk in toIndex.chunked(batchSize)) {
                val exifMap = exif.readBatch(chunk.map { it.first })
                // Index the chunk concurrently — rendering is CPU-bound and per-file independent.
                // The short SQLite writes serialize fine across threads (WAL + busy_timeout).
                val failed = coroutineScope {
                    chunk.map { (file, existingId) ->
                        async(renderDispatcher) {
                            try {
                                indexOne(rootId, file, exifMap[file], existingId)
                                false
                            } catch (e: Exception) {
                                log.warn("failed to index $file: ${e.message}")
                                true
                            }
                        }
                    }.awaitAll().count { it }
                }
                indexed += chunk.size - failed
                errors += failed
                setProgress(jobId, indexed, errors)
            }

            val removed = removeMissing(known, files)

            transaction {
                ScanRoots.update({ ScanRoots.id eq rootId }) { it[lastScanAt] = clock() }
                ScanJobs.update({ ScanJobs.id eq jobId }) {
                    it[state] = "done"
                    it[finishedAt] = clock()
                    it[filesIndexed] = indexed
                    it[ScanJobs.errors] = errors
                }
            }
            log.info("scan #$jobId done: ${files.size} seen, $indexed indexed, $removed removed, $errors errors")
        } catch (e: Exception) {
            log.error("scan #$jobId failed", e)
            transaction {
                ScanJobs.update({ ScanJobs.id eq jobId }) {
                    it[state] = "failed"
                    it[finishedAt] = clock()
                    it[errorSummary] = e.message ?: e.javaClass.simpleName
                }
            }
        } finally {
            runningRoots.remove(rootId)
        }
    }

    /**
     * Collects matching files under [root]. Unreadable files/directories are skipped and
     * counted instead of aborting: Files.walk would throw mid-stream on the first
     * permission-denied subfolder and fail the whole scan.
     */
    private fun walk(root: Path, jobId: Int): Pair<List<Path>, Int> {
        val files = ArrayList<Path>()
        var errors = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.extension.lowercase() in extensions) {
                    files.add(file)
                    // Publish progress during discovery so a long walk doesn't sit at "0 seen".
                    if (files.size % seenUpdateEvery == 0) setSeen(jobId, files.size)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                log.warn("cannot access $file: ${exc.message}")
                errors++
                return FileVisitResult.CONTINUE
            }
        })
        return files to errors
    }

    private fun indexOne(rootId: Int, file: Path, data: ExifData?, existingId: Int?) {
        val attrs = readAttrs(file) ?: return
        val mtime = attrs.lastModifiedTime().toMillis()
        val created = data?.createdDateMs ?: mtime
        val status = when {
            data == null && !exif.available -> "no_exif"
            data?.hadExif == true -> "ok"
            else -> "no_exif"
        }

        fun fill(s: UpdateBuilder<*>) {
            s[Photos.rootId] = rootId
            s[Photos.filePath] = file.toString()
            s[Photos.fileName] = file.fileName.toString()
            s[Photos.fileSize] = attrs.size()
            s[Photos.fileMtime] = mtime
            s[Photos.createdDate] = created
            s[Photos.cameraMake] = data?.cameraMake
            s[Photos.cameraModel] = data?.cameraModel
            s[Photos.lens] = data?.lens
            s[Photos.shutterSpeed] = data?.shutterSpeed
            s[Photos.aperture] = data?.aperture
            s[Photos.focalLength] = data?.focalLength
            s[Photos.focalLength35] = data?.focalLength35
            s[Photos.gpsLat] = data?.gpsLat
            s[Photos.gpsLon] = data?.gpsLon
            s[Photos.width] = data?.width
            s[Photos.height] = data?.height
            s[Photos.orientation] = data?.orientation
            s[Photos.indexedAt] = clock()
            s[Photos.exifStatus] = status
        }

        val photoId = transaction {
            if (existingId != null) {
                // Update in place: the photo id — which UI selections, collect requests and
                // the thumbnail/preview cache all key on — survives re-indexing a changed file.
                Photos.update({ Photos.id eq existingId }) {
                    fill(it)
                    it[thumbPath] = null
                }
                existingId
            } else {
                Photos.insertAndGetId { fill(it) }.value
            }
        }
        // The file's content changed, so any cached renders for this id are stale.
        if (existingId != null) thumbs.invalidate(photoId)

        val thumb = thumbs.generate(file, photoId, data?.orientation)
        if (thumb != null) {
            transaction {
                Photos.update({ Photos.id eq photoId }) { it[thumbPath] = thumb.toString() }
            }
        }
    }

    /**
     * Drops rows (and cached renders) for files under this root that no longer exist on disk.
     * Conservative: Files.notExists is true only when absence is *confirmed*, so a file that
     * merely couldn't be read this pass (e.g. permissions) is kept.
     */
    private fun removeMissing(known: Map<String, KnownRow>, seen: List<Path>): Int {
        val seenPaths = seen.mapTo(HashSet()) { it.toString() }
        var removed = 0
        for ((path, row) in known) {
            if (path in seenPaths || !Files.notExists(Path.of(path))) continue
            transaction { Photos.deleteWhere { Photos.id eq row.id } }
            thumbs.invalidate(row.id)
            removed++
        }
        return removed
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
