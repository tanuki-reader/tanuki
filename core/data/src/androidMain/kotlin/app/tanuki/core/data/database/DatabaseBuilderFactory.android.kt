package app.tanuki.core.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Android [DatabaseBuilderFactory]: stores the database in the app's private `databases/` directory,
 * resolved from the application [Context].
 */
actual class DatabaseBuilderFactory(
    private val context: Context,
) {
    actual fun create(): RoomDatabase.Builder<TanukiDatabase> {
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(DATABASE_FILE_NAME)
        return Room.databaseBuilder<TanukiDatabase>(
            context = appContext,
            name = dbFile.absolutePath,
        )
    }
}