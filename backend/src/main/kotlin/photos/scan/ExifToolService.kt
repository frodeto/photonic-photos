package photos.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    private fun probe(): Boolean = try {
        val p = ProcessBuilder(exiftoolPath, "-ver").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    } catch (e: Exception) {
        log.warn("exiftool not available ($exiftoolPath): ${e.message}. Falling back to filesystem metadata only.")
        false
    }

    private val tags = listOf(
        "-DateTimeOriginal", "-CreateDate", "-Make", "-Model", "-LensModel", "-LensID",
        "-ExposureTime", "-FNumber", "-FocalLength", "-FocalLengthIn35mmFormat",
        "-GPSLatitude", "-GPSLongitude", "-ImageWidth", "-ImageHeight", "-Orientation",
    )

    /** Reads EXIF for a batch of files in a single exiftool invocation. */
    fun readBatch(files: List<Path>): Map<Path, ExifData> {
        if (!available || files.isEmpty()) return emptyMap()
        return try {
            val cmd = buildList {
                add(exiftoolPath)
                add("-json")
                add("-n")               // numeric values (FNumber etc.)
                add("-fast2")
                add("-charset"); add("filename=UTF8")
                addAll(tags)
                files.forEach { add(it.toString()) }
            }
            val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = proc.inputStream.readBytes().decodeToString()
            proc.waitFor()
            if (out.isBlank()) return emptyMap()
            parse(out)
        } catch (e: Exception) {
            log.warn("exiftool batch read failed: ${e.message}")
            emptyMap()
        }
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

    private fun parseExifDate(raw: String): Long? = try {
        val cleaned = raw.trim().substringBefore('+').substringBefore('.').trim()
        LocalDateTime.parse(cleaned, exifDateFmt).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /** Extracts an embedded JPEG preview (used for RAW thumbnails). Returns null if none/unavailable. */
    fun extractPreviewJpeg(file: Path): ByteArray? {
        if (!available) return null
        for (tag in listOf("-PreviewImage", "-JpgFromRaw", "-ThumbnailImage")) {
            try {
                val proc = ProcessBuilder(exiftoolPath, "-b", tag, file.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val bytes = proc.inputStream.readBytes()
                proc.waitFor()
                if (bytes.size > 100) return bytes
            } catch (e: Exception) {
                log.debug("preview extraction $tag failed for $file: ${e.message}")
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" }

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}
