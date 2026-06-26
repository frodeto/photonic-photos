package photos.db

import org.jetbrains.exposed.dao.id.IntIdTable

/** A folder the user has pointed the indexer at. */
object ScanRoots : IntIdTable("scan_root") {
    val path = text("path").uniqueIndex()
    val addedAt = long("added_at")
    val lastScanAt = long("last_scan_at").nullable()
}

/** One indexed photo. EXIF columns are nullable — they may be absent or exiftool may be unavailable. */
object Photos : IntIdTable("photo") {
    val rootId = integer("root_id").index()
    val filePath = text("file_path").uniqueIndex()
    val fileName = text("file_name")
    val fileSize = long("file_size")
    val fileMtime = long("file_mtime")
    val contentHash = text("content_hash").nullable()

    // createdDate is epoch millis: EXIF DateTimeOriginal when present, else file mtime.
    val createdDate = long("created_date").index()

    val cameraMake = text("camera_make").nullable()
    val cameraModel = text("camera_model").nullable()
    val lens = text("lens").nullable()
    val shutterSpeed = text("shutter_speed").nullable()
    val aperture = double("aperture").nullable()
    val focalLength = double("focal_length").nullable()
    val focalLength35 = double("focal_length_35mm").nullable()
    val gpsLat = double("gps_lat").nullable()
    val gpsLon = double("gps_lon").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val orientation = integer("orientation").nullable()

    val thumbPath = text("thumb_path").nullable()
    val indexedAt = long("indexed_at")
    // "ok" | "no_exif" | "error"
    val exifStatus = varchar("exif_status", 16)
}

/** Progress record for a (possibly long-running) scan of a root. */
object ScanJobs : IntIdTable("scan_job") {
    val rootId = integer("root_id")
    // "running" | "done" | "failed"
    val state = varchar("state", 16)
    val filesSeen = integer("files_seen").default(0)
    val filesIndexed = integer("files_indexed").default(0)
    val errors = integer("errors").default(0)
    val startedAt = long("started_at")
    val finishedAt = long("finished_at").nullable()
    val errorSummary = text("error_summary").nullable()
}
