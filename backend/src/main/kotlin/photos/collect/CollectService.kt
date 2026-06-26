package photos.collect

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import photos.db.Photos
import photos.model.CollectResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.extension

/**
 * Copies (never moves) selected originals into the user's "look closer at" folder.
 * Collisions are de-duplicated as "name (2).ext". Originals are left untouched.
 */
class CollectService {
    fun collect(photoIds: List<Int>, targetFolder: String): CollectResult {
        val target = Path.of(targetFolder)
        Files.createDirectories(target)

        val paths: List<String> = transaction {
            Photos.selectAll()
                .where { Photos.id inList photoIds }
                .map { it[Photos.filePath] }
        }

        var copied = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (p in paths) {
            val src = Path.of(p)
            if (!src.exists()) {
                skipped++
                errors.add("missing source: $p")
                continue
            }
            try {
                val dest = uniqueDestination(target, src.fileName.toString())
                Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES)
                copied++
            } catch (e: Exception) {
                skipped++
                errors.add("${src.fileName}: ${e.message}")
            }
        }
        return CollectResult(copied = copied, skipped = skipped, errors = errors)
    }

    private fun uniqueDestination(dir: Path, fileName: String): Path {
        var candidate = dir.resolve(fileName)
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension
        var n = 2
        while (candidate.exists()) {
            val name = if (ext.isBlank()) "$base ($n)" else "$base ($n).$ext"
            candidate = dir.resolve(name)
            n++
        }
        return candidate
    }
}
