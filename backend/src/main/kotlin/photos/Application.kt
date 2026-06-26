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
import java.security.SecureRandom
import java.util.Base64

/**
 * Backend entrypoint. Picks a free loopback port, prints the handshake lines
 *   PHOTONIC_PORT=<n>
 *   PHOTONIC_TOKEN=<secret>
 * on stdout (the Tauri shell reads these to know where to talk to and how to authenticate),
 * then serves the API.
 *
 * The port can be forced with PHOTONIC_PORT and the token with PHOTONIC_TOKEN for local
 * development (the browser dev client reads VITE_BACKEND_TOKEN, default "photonic-dev").
 */
fun main() {
    val dataDir = AppPaths.dataDir()
    Db.init(dataDir)

    val exif = ExifToolService()
    val thumbs = ThumbnailService(AppPaths.thumbnailsDir(dataDir), exif)
    val scanner = Scanner(exif, thumbs)
    val collect = CollectService()

    val port = System.getenv("PHOTONIC_PORT")?.toIntOrNull() ?: freePort()
    // A per-launch secret. Only processes that can read this backend's stdout (the Tauri shell)
    // learn it, so a malicious web page cannot forge authenticated requests to 127.0.0.1.
    val token = System.getenv("PHOTONIC_TOKEN")?.takeIf { it.isNotBlank() } ?: generateToken()

    // Handshake lines on stdout — keep these the only things written to stdout.
    println("PHOTONIC_PORT=$port")
    println("PHOTONIC_TOKEN=$token")
    System.out.flush()

    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        photonicModule(scanner, collect, token = token)
    }.start(wait = true)
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun generateToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
