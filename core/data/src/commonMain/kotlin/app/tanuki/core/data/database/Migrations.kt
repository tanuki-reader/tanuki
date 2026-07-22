package app.tanuki.core.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every schema migration, in order, applied by [buildDatabase].
 *
 * Migrations are written by hand rather than falling back to a destructive recreate: the database
 * holds the user's library, reading progress, and history — none of it recoverable from a source —
 * so a schema change must never be allowed to drop it, even pre-release.
 */
internal val MIGRATIONS: Array<Migration> = arrayOf(Migration1To2, Migration2To3)

/**
 * Adds `sources.version` — the monotonic version the installed `.wasm` bundle reported at install
 * time. Nullable, because a source is not required to report one, and because existing rows were
 * installed before the column existed and have no version to backfill; `null` reads as "unknown",
 * which an update check treats as "can't compare, offer the update".
 */
internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE sources ADD COLUMN version INTEGER")
    }
}

/**
 * Adds `manga.source_manga_key` — the source-native manga id — and moves the manga uniqueness onto it.
 *
 * A manga's true identity within a source is its source key, not its [url]: url-less sources fall back
 * to the source home, so many series collapse to one url and the old unique `(source, url)` index would
 * wrongly dedupe them into a single row. The column is nullable because rows written before it existed
 * have no key to backfill; SQLite treats those `NULL`s as distinct, so they don't collide under the new
 * unique index (and they simply won't dedupe against a freshly browsed key — acceptable pre-release).
 */
internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE manga ADD COLUMN source_manga_key TEXT")
        connection.execSQL("DROP INDEX IF EXISTS index_manga_source_url")
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_manga_source_source_manga_key " +
                "ON manga (source, source_manga_key)",
        )
    }
}
