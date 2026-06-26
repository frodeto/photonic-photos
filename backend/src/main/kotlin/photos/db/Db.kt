package photos.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.sql.Connection

/** Owns the SQLite connection and schema creation. */
object Db {
    @Volatile
    private var connected = false

    fun init(dataDir: Path) {
        if (connected) return
        val dbFile = dataDir.resolve("photonic.db").toAbsolutePath()
        // PRAGMAs go in the URL — journal_mode=WAL cannot be changed from inside a transaction.
        // busy_timeout makes a writer wait (up to 5s) for a lock instead of failing immediately
        // with SQLITE_BUSY when a background scan and an API/collect write overlap.
        Database.connect(
            "jdbc:sqlite:$dbFile?journal_mode=WAL&foreign_keys=ON&busy_timeout=5000",
            driver = "org.sqlite.JDBC",
        )
        // SQLite allows a single writer; serializable keeps Exposed happy.
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        transaction {
            SchemaUtils.create(ScanRoots, Photos, ScanJobs)
        }
        connected = true
    }
}
