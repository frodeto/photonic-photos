package photos.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Parsed EXIF fields we care about. All optional. */
data class ExifData(
    val createdDateMs: Long? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lens: String? = null,
    val shutterSpeed: String? = null,
    val aperture: Double? = null,
    val focalLength: Double? = null,
    val focalLength35: Double? = null,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int? = null,
    val hadExif: Boolean = false,
)

/**
 * Thin wrapper over the exiftool CLI. Drives it in batches (one process per chunk of files)
 * rather than once per photo. If exiftool is not installed the service degrades gracefully:
 * batch reads return empty data and the scanner falls back to filesystem metadata.
 *
 * The binary is resolved from PHOTONIC_EXIFTOOL, else "exiftool" on PATH. In the packaged
 * Mac app this points at the exiftool bundled as a Tauri resource.
 */
class ExifToolService(
    private val exiftoolPath: String = System.getenv("PHOTONIC_EXIFTOOL") ?: "exiftool",
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val log = LoggerFactory.getLogger(ExifToolService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val exifDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    val available: Boolean by lazy { probe() }

    private fun probe(): Boolean {
        val out = runWithTimeout(listOf(exiftoolPath, "-ver"), PROBE_TIMEOUT_MS)
        if (out == null || out.isEmpty()) {
            log.warn("exiftool not available ($exiftoolPath). Falling back to filesystem metadata only.")
            return false
        }
        return true
    }

    /**
     * Runs an exiftool command and returns its stdout bytes, or null if it failed to start,
     * produced no output, or exceeded [timeoutMs] — in which case the process is force-killed
     * so a hung exiftool can never block the scan thread indefinitely.
     */
    private fun runWithTimeout(cmd: List<String>, timeoutMs: Long): ByteArray? {
        var proc: Process? = null
        return try {
            val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            proc = p
            // Drain stdout on a daemon thread; reading on the caller would itself block forever
            // if exiftool hangs without closing the stream.
            val holder = arrayOfNulls<ByteArray>(1)
            val reader = Thread { holder[0] = runCatching { p.inputStream.readBytes() }.getOrNull() }
                .apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("exiftool timed out after ${timeoutMs}ms (${cmd.firstOrNull()}); killing process")
                p.destroyForcibly()
                return null
            }
            reader.join(STREAM_DRAIN_GRACE_MS)
            holder[0]
        } catch (e: Exception) {
            log.warn("exiftool invocation failed: ${e.message}")
            null
        } finally {
            proc?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    private val tags = listOf(
        "-DateTimeOriginal", "-CreateDate", "-Make", "-Model", "-LensModel", "-LensID",
        "-ExposureTime", "-FNumber", "-FocalLength", "-FocalLengthIn35mmFormat",
        "-GPSLatitude", "-GPSLongitude", "-ImageWidth", "-ImageHeight", "-Orientation",
    )

    /** Reads EXIF for a batch of files in a single exiftool invocation. */
    fun readBatch(files: List<Path>): Map<Path, ExifData> {
        if (!available || files.isEmpty()) return emptyMap()
        // Pass the file list via a UTF-8 argfile (-@) rather than on the command line. This
        // sidesteps two Windows hazards in one move: the ~32K CreateProcess command-line ceiling
        // (a 100-file batch of long NAS paths blows past it) and code-page mangling of non-ASCII
        // names (-charset filename=UTF8 tells exiftool the argfile bytes are UTF-8; JDK 21 writes
        // UTF-8 by default per JEP 400). Harmless on unix, which has neither limit.
        val argfile = try {
            writeArgFile(files)
        } catch (e: Exception) {
            log.warn("exiftool argfile write failed: ${e.message}")
            return emptyMap()
        }
        val out = try {
            val cmd = buildList {
                add(exiftoolPath)
                add("-json")
                add("-n")               // numeric values (FNumber etc.)
                add("-fast2")
                add("-charset"); add("filename=UTF8")
                addAll(tags)
                add("-@"); add(argfile.toString())
            }
            runWithTimeout(cmd, BATCH_TIMEOUT_MS)?.decodeToString()
        } finally {
            runCatching { Files.deleteIfExists(argfile) }
        }
        if (out.isNullOrBlank()) return emptyMap()
        return try {
            parse(out)
        } catch (e: Exception) {
            log.warn("exiftool batch parse failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Writes [files] to a temp file, one absolute path per line in UTF-8, for exiftool's `-@`
     * argfile mechanism. Caller is responsible for deleting it.
     */
    private fun writeArgFile(files: List<Path>): Path {
        val argfile = Files.createTempFile("photonic-exif", ".args")
        Files.write(argfile, files.map { it.toString() }, StandardCharsets.UTF_8)
        return argfile
    }

    private fun parse(jsonText: String): Map<Path, ExifData> {
        val arr = json.parseToJsonElement(jsonText) as? JsonArray ?: return emptyMap()
        val result = HashMap<Path, ExifData>()
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val source = obj.str("SourceFile") ?: continue
            val createdMs = (obj.str("DateTimeOriginal") ?: obj.str("CreateDate"))?.let { parseExifDate(it) }
            val data = ExifData(
                createdDateMs = createdMs,
                cameraMake = obj.str("Make"),
                cameraModel = obj.str("Model"),
                lens = obj.str("LensModel") ?: obj.str("LensID"),
                shutterSpeed = obj.str("ExposureTime"),
                aperture = obj.dbl("FNumber"),
                focalLength = obj.dbl("FocalLength"),
                focalLength35 = obj.dbl("FocalLengthIn35mmFormat"),
                gpsLat = obj.dbl("GPSLatitude"),
                gpsLon = obj.dbl("GPSLongitude"),
                width = obj.dbl("ImageWidth")?.toInt(),
                height = obj.dbl("ImageHeight")?.toInt(),
                orientation = obj.dbl("Orientation")?.toInt(),
                hadExif = true,
            )
            result[Path.of(source)] = data
        }
        return result
    }

    internal fun parseExifDate(raw: String): Long? = try {
        // exiftool emits "yyyy:MM:dd HH:mm:ss" optionally followed by fractional seconds and a
        // timezone (+HH:MM, -HH:MM or Z). The date part uses ':' separators, so the only '-' that
        // can appear is a negative tz offset — strip the offset (either sign) and reinterpret in
        // the configured zone, matching the no-offset case.
        val cleaned = raw.trim()
            .substringBefore('.')          // drop fractional seconds (and anything after)
            .let { TZ_SUFFIX.replace(it, "") }
            .trim()
        LocalDateTime.parse(cleaned, exifDateFmt).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /** Extracts an embedded JPEG preview (used for RAW thumbnails). Returns null if none/unavailable. */
    fun extractPreviewJpeg(file: Path): ByteArray? {
        if (!available) return null
        // Route the path through the same UTF-8 argfile as readBatch so non-ASCII names survive
        // the Windows code page (-charset filename=UTF8). One argfile serves all three tag probes.
        val argfile = try {
            writeArgFile(listOf(file))
        } catch (e: Exception) {
            log.warn("exiftool argfile write failed: ${e.message}")
            return null
        }
        return try {
            for (tag in listOf("-PreviewImage", "-JpgFromRaw", "-ThumbnailImage")) {
                val bytes = runWithTimeout(
                    listOf(exiftoolPath, "-b", tag, "-charset", "filename=UTF8", "-@", argfile.toString()),
                    PREVIEW_TIMEOUT_MS,
                )
                if (bytes != null && bytes.size > 100) return bytes
            }
            null
        } finally {
            runCatching { Files.deleteIfExists(argfile) }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" }

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val BATCH_TIMEOUT_MS = 120_000L   // a batch is up to ~100 files; generous to avoid false kills
        const val PREVIEW_TIMEOUT_MS = 30_000L
        const val STREAM_DRAIN_GRACE_MS = 5_000L

        // Trailing timezone offset: +HH:MM, -HH:MM, +HHMM, -HHMM, or Z.
        val TZ_SUFFIX = Regex("""([+-]\d{2}:?\d{2}|Z)$""")
    }
}
