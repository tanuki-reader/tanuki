package app.tanuki.core.data.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.tanuki.core.domain.common.service.DispatcherProvider
import kotlinx.coroutines.Dispatchers

/** File name of the on-disk SQLite database, shared by every platform. */
internal const val DATABASE_FILE_NAME = "tanuki.db"

/**
 * Platform factory that produces a partially-configured [RoomDatabase.Builder] pointing at the
 * correct on-disk location for the current OS. The `actual`s live in `androidMain` / `iosMain`.
 *
 * Callers finish configuration and open the database with [buildDatabase]; the split keeps the
 * platform-specific bit (where the file lives) minimal while the driver and threading policy stay
 * shared in common code.
 */
expect class DatabaseBuilderFactory {
    fun create(): RoomDatabase.Builder<TanukiDatabase>
}

/**
 * Applies the shared driver and threading configuration and opens the database.
 *
 * Uses the bundled SQLite driver (the same native SQLite on both platforms) and runs queries on
 * [Dispatchers.IO] so suspending DAO calls never block the caller's thread.
 */
fun RoomDatabase.Builder<TanukiDatabase>.buildDatabase(dispatcherProvider: DispatcherProvider): TanukiDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(dispatcherProvider.io)
        .build()