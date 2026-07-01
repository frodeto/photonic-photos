package photos

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the per-user application data directory where the SQLite DB and the
 * thumbnail cache live. Honors PHOTONIC_DATA_DIR (used by tests / power users),
 * otherwise uses the OS-appropriate location.
 */
object AppPaths {
    fun dataDir(): Path {
        val override = System.getenv("PHOTONIC_DATA_DIR")
        val base: Path = if (!override.isNullOrBlank()) {
            Paths.get(override)
        } else {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> Paths.get(home, "Library", "Application Support", "PhotonicPhotos")
                os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: home, "PhotonicPhotos")
                else -> Paths.get(home, ".local", "share", "PhotonicPhotos")
            }
        }
        Files.createDirectories(base)
        Files.createDirectories(base.resolve("thumbnails"))
        Files.createDirectories(base.resolve("previews"))
        return base
    }

    fun thumbnailsDir(dataDir: Path): Path = dataDir.resolve("thumbnails")

    fun previewsDir(dataDir: Path): Path = dataDir.resolve("previews")
}
