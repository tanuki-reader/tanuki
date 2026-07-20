package app.tanuki.core.data.database

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS [DatabaseBuilderFactory]: stores the database in the app sandbox's Documents directory, which
 * is backed up and persists across launches.
 */
actual class DatabaseBuilderFactory {
    actual fun create(): RoomDatabase.Builder<TanukiDatabase> {
        val dbFilePath = documentDirectory() + "/" + DATABASE_FILE_NAME
        return Room.databaseBuilder<TanukiDatabase>(name = dbFilePath)
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun documentDirectory(): String {
        val documentDirectory =
            NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null,
            )
        return requireNotNull(documentDirectory?.path)
    }
}
