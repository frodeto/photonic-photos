package photos

import io.ktor.server.application.ServerReady
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
import kotlin.system.exitProcess

/**
 * Backend entrypoint. Picks a free loopback port, then serves the API and — only once the
 * socket is actually bound and ready (the ServerReady event) — prints the handshake lines
 *   PHOTONIC_PORT=<n>
 *   PHOTONIC_TOKEN=<secret>
 * on stdout. The Tauri shell reads these to know where to talk to and how to authenticate;
 * printing after bind guarantees the first client request can't race ahead of the open socket.
 *
 * The port can be forced with PHOTONIC_PORT and the token with PHOTONIC_TOKEN for local
 * development (the browser dev client reads VITE_BACKEND_TOKEN, default "photonic-dev").
 */
fun main() {
    // When launched as a Tauri sidecar (the shell sets PHOTONIC_WATCH_STDIN=1), exit as soon
    // as stdin reaches EOF: the pipe closes when the GUI quits or crashes, so the backend can
    // never outlive it — even if the shell's kill-on-exit never fired. Opt-in via env so
    // `mvn exec:java` / nohup dev runs (where stdin may be closed from the start) are unaffected.
    if (System.getenv("PHOTONIC_WATCH_STDIN") == "1") {
        Thread {
            try {
                while (System.`in`.read() != -1) {
                    // discard; only EOF matters
                }
            } catch (_: Exception) {
                // treat a broken pipe like EOF
            }
            exitProcess(0)
        }.apply {
            isDaemon = true
            name = "stdin-watchdog"
            start()
        }
    }

    val dataDir = AppPaths.dataDir()
    Db.init(dataDir)

    val exif = ExifToolService()
    val thumbs = ThumbnailService(
        AppPaths.thumbnailsDir(dataDir),
        exif,
        previewsDir = AppPaths.previewsDir(dataDir),
    )
    val scanner = Scanner(exif, thumbs)
    val collect = CollectService()

    val port = System.getenv("PHOTONIC_PORT")?.toIntOrNull() ?: freePort()
    // A per-launch secret. Only processes that can read this backend's stdout (the Tauri shell)
    // learn it, so a malicious web page cannot forge authenticated requests to 127.0.0.1.
    val token = System.getenv("PHOTONIC_TOKEN")?.takeIf { it.isNotBlank() } ?: generateToken()

    val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
        photonicModule(scanner, collect, thumbs, token = token)
    }
    // Emit the handshake only once the engine is bound and accepting connections, so the GUI
    // never fires its first request at a socket that isn't listening yet. Keep these the only
    // things written to stdout.
    server.monitor.subscribe(ServerReady) {
        println("PHOTONIC_PORT=$port")
        println("PHOTONIC_TOKEN=$token")
        System.out.flush()
    }
    server.start(wait = true)
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun generateToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
