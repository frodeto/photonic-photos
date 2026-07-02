package photos.model

import kotlinx.serialization.Serializable

// No property defaults: the Json config doesn't encode defaults, and both fields must always
// be on the wire ("status" for probes/scripts, "api" for the UI's stale-backend check).
@Serializable
data class HealthResponse(val status: String, val api: Int)

@Serializable
data class ScanRequest(val path: String)

@Serializable
data class ScanJobDto(
    val id: Int,
    val rootId: Int,
    val state: String,
    val filesSeen: Int,
    val filesIndexed: Int,
    val errors: Int,
    val startedAt: Long,
    val finishedAt: Long?,
)

@Serializable
data class RootDto(
    val id: Int,
    val path: String,
    val photoCount: Long,
    val lastScanAt: Long?,
)

@Serializable
data class TimelineBucket(val bucketStart: Long, val count: Long)

@Serializable
data class PhotoDto(
    val id: Int,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    // Used by the client as a cache-busting `v=` param on thumbnail/preview URLs.
    val fileMtime: Long = 0,
    val createdDate: Long,
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
    val hasThumbnail: Boolean = false,
)

@Serializable
data class CollectRequest(val photoIds: List<Int>, val targetFolder: String)

@Serializable
data class CollectResult(val copied: Int, val skipped: Int, val errors: List<String> = emptyList())

@Serializable
data class ErrorResponse(val error: String)
