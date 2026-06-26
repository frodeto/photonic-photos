package photos

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import photos.api.photonicModule
import photos.collect.CollectService
import photos.db.Db
import photos.scan.ExifToolService
import photos.scan.Scanner
import photos.thumbnail.ThumbnailService
import java.net.ServerSocket

/**
 * Backend entrypoint. Picks a free loopback port, prints the handshake line
 *   PHOTONIC_PORT=<n>
 * on stdout (the Tauri shell reads this to know where to talk to), then serves the API.
 *
 * A fixed port can be forced with PHOTONIC_PORT for local development.
 */
fun main() {
    val dataDir = AppPaths.dataDir()
    Db.init(dataDir)

    val exif = ExifToolService()
    val thumbs = ThumbnailService(AppPaths.thumbnailsDir(dataDir), exif)
    val scanner = Scanner(exif, thumbs)
    val collect = CollectService()

    val port = System.getenv("PHOTONIC_PORT")?.toIntOrNull() ?: freePort()

    // Handshake line on stdout — keep this the only thing written to stdout.
    println("PHOTONIC_PORT=$port")
    System.out.flush()

    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        photonicModule(scanner, collect)
    }.start(wait = true)
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
