package photos.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import photos.collect.CollectService
import photos.db.Photos
import photos.db.ScanJobs
import photos.db.ScanRoots
import photos.model.CollectRequest
import photos.model.ErrorResponse
import photos.model.PhotoDto
import photos.model.RootDto
import photos.model.ScanJobDto
import photos.model.ScanRequest
import photos.model.TimelineBucket
import photos.scan.Scanner
import photos.thumbnail.ThumbnailService
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

const val TOKEN_HEADER = "X-Photonic-Token"
const val TOKEN_PARAM = "token"

fun Application.photonicModule(
    scanner: Scanner,
    collect: CollectService,
    thumbnails: ThumbnailService,
    token: String,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CORS) {
        anyHost() // backend binds 127.0.0.1; the X-Photonic-Token check below is the real gate
        allowHeader("Content-Type")
        allowHeader(TOKEN_HEADER)
    }

    // Authenticate every request against the per-launch token (constant-time compare).
    // The token comes from the X-Photonic-Token header, or a `token` query param as a fallback
    // for <img> thumbnail loads that can't set headers. /health is exempt so the shell/curl can
    // probe readiness without the secret.
    val tokenBytes = token.toByteArray()
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() == "/health") return@intercept
        val provided = (call.request.headers[TOKEN_HEADER]
            ?: call.request.queryParameters[TOKEN_PARAM])?.toByteArray()
        if (provided == null || !MessageDigest.isEqual(provided, tokenBytes)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid or missing $TOKEN_HEADER"))
            return@intercept finish()
        }
    }

    val log = this.log
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<Throwable> { call, cause ->
            log.error("unhandled error on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "internal error"))
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        get("/roots") {
            val roots = transaction {
                ScanRoots.selectAll().map { r ->
                    val id = r[ScanRoots.id].value
                    val count = Photos.selectAll().where { Photos.rootId eq id }.count()
                    RootDto(id = id, path = r[ScanRoots.path], photoCount = count, lastScanAt = r[ScanRoots.lastScanAt])
                }
            }
            call.respond(roots)
        }

        post("/roots/scan") {
            val req = call.receive<ScanRequest>()
            val jobId = scanner.startScan(req.path)
            call.respond(HttpStatusCode.Accepted, mapOf("jobId" to jobId))
        }

        get("/scans/{id}") {
            val id = call.intParam("id")
            val dto = transaction {
                ScanJobs.selectAll().where { ScanJobs.id eq id }.firstOrNull()?.let {
                    ScanJobDto(
                        id = it[ScanJobs.id].value,
                        rootId = it[ScanJobs.rootId],
                        state = it[ScanJobs.state],
                        filesSeen = it[ScanJobs.filesSeen],
                        filesIndexed = it[ScanJobs.filesIndexed],
                        errors = it[ScanJobs.errors],
                        startedAt = it[ScanJobs.startedAt],
                        finishedAt = it[ScanJobs.finishedAt],
                    )
                }
            }
            if (dto == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("no such scan"))
            else call.respond(dto)
        }

        get("/timeline") {
            val from = call.longParamOrNull("from")
            val to = call.longParamOrNull("to")
            val bucket = call.request.queryParameters["bucket"] ?: "month"
            val dates = transaction {
                Photos.select(Photos.createdDate).where {
                    var c: Op<Boolean> = Op.TRUE
                    if (from != null) c = c and (Photos.createdDate greaterEq from)
                    if (to != null) c = c and (Photos.createdDate less to) // exclusive end
                    c
                }.map { it[Photos.createdDate] }
            }
            val buckets = dates.groupingBy { bucketStart(it, bucket, zone) }.eachCount()
                .map { (k, v) -> TimelineBucket(k, v.toLong()) }
                .sortedBy { it.bucketStart }
            call.respond(buckets)
        }

        get("/photos") {
            val from = call.longParamOrNull("from")
            val bucket = call.request.queryParameters["bucket"]
            // When a bucket granularity is given, derive the exclusive end from `from` with the
            // SAME server-side bucketing used by /timeline, so a drill-in returns exactly the
            // photos counted in that bar (no browser-timezone recompute, no boundary drift).
            val to = if (bucket != null && from != null) bucketEnd(from, bucket, zone)
            else call.longParamOrNull("to")
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 2000)
            val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val photos = transaction {
                Photos.selectAll().where {
                    var c: Op<Boolean> = Op.TRUE
                    if (from != null) c = c and (Photos.createdDate greaterEq from)
                    if (to != null) c = c and (Photos.createdDate less to) // exclusive end
                    c
                }.orderBy(Photos.createdDate to SortOrder.ASC)
                    .limit(limit).offset(offset)
                    .map { it.toPhotoDto() }
            }
            call.respond(photos)
        }

        get("/photos/{id}") {
            val id = call.intParam("id")
            val dto = transaction {
                Photos.selectAll().where { Photos.id eq id }.firstOrNull()?.toPhotoDto()
            }
            if (dto == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("no such photo"))
            else call.respond(dto)
        }

        get("/photos/{id}/thumbnail") {
            val id = call.intParam("id")
            val thumbPath = transaction {
                Photos.selectAll().where { Photos.id eq id }.firstOrNull()?.get(Photos.thumbPath)
            }
            val path = thumbPath?.let { Path.of(it) }
            if (path == null || !Files.exists(path)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no thumbnail"))
            } else {
                call.respondCachedImage(path)
            }
        }

        // Larger lightbox preview (~1024px). Unlike thumbnails it is not produced during the scan;
        // it's rendered from the original on first request and cached to disk, so most clicks after
        // the first are a plain file read.
        get("/photos/{id}/preview") {
            val id = call.intParam("id")
            val row = transaction {
                Photos.selectAll().where { Photos.id eq id }.firstOrNull()?.let {
                    it[Photos.filePath] to it[Photos.orientation]
                }
            }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no such photo"))
                return@get
            }
            val (filePath, orientation) = row
            val preview = thumbnails.preview(Path.of(filePath), id, orientation)
            if (preview == null || !Files.exists(preview)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no preview"))
            } else {
                call.respondCachedImage(preview)
            }
        }

        post("/collect") {
            val req = call.receive<CollectRequest>()
            require(req.photoIds.isNotEmpty()) { "photoIds must not be empty" }
            require(req.targetFolder.isNotBlank()) { "targetFolder must not be blank" }
            call.respond(collect.collect(req.photoIds, req.targetFolder))
        }
    }
}

internal fun bucketStart(epochMs: Long, bucket: String, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val start = when (bucket) {
        "year" -> date.withDayOfYear(1)
        "day" -> date
        else -> date.withDayOfMonth(1) // month
    }
    return start.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Exclusive end of the bucket containing [epochMs] — i.e. the start of the next bucket. */
internal fun bucketEnd(epochMs: Long, bucket: String, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val nextStart = when (bucket) {
        "year" -> date.withDayOfYear(1).plusYears(1)
        "day" -> date.plusDays(1)
        else -> date.withDayOfMonth(1).plusMonths(1) // month
    }
    return nextStart.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Serves a cached render (thumbnail/preview) with aggressive client caching. The client embeds
 * the photo's fileMtime in the URL (`v=` param), so the URL changes whenever the source file is
 * re-indexed — which makes an immutable max-age safe and spares the WebView a request per image
 * on every strip render.
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondCachedImage(path: Path) {
    response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
    respondFile(path.toFile())
}

private fun org.jetbrains.exposed.sql.ResultRow.toPhotoDto(): PhotoDto = PhotoDto(
    id = this[Photos.id].value,
    filePath = this[Photos.filePath],
    fileName = this[Photos.fileName],
    fileSize = this[Photos.fileSize],
    fileMtime = this[Photos.fileMtime],
    createdDate = this[Photos.createdDate],
    cameraMake = this[Photos.cameraMake],
    cameraModel = this[Photos.cameraModel],
    lens = this[Photos.lens],
    shutterSpeed = this[Photos.shutterSpeed],
    aperture = this[Photos.aperture],
    focalLength = this[Photos.focalLength],
    focalLength35 = this[Photos.focalLength35],
    gpsLat = this[Photos.gpsLat],
    gpsLon = this[Photos.gpsLon],
    width = this[Photos.width],
    height = this[Photos.height],
    hasThumbnail = this[Photos.thumbPath] != null,
)

private fun io.ktor.server.application.ApplicationCall.intParam(name: String): Int =
    parameters[name]?.toIntOrNull() ?: throw IllegalArgumentException("invalid $name")

private fun io.ktor.server.application.ApplicationCall.longParamOrNull(name: String): Long? =
    request.queryParameters[name]?.toLongOrNull()
