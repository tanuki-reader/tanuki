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
internal val MIGRATIONS: Array<Migration> =
    arrayOf(
        Migration1To2,
        Migration2To3,
        Migration3To4,
        Migration4To5,
        Migration5To6,
        Migration6To7,
        Migration7To8,
        Migration8To9,
        Migration9To10,
        Migration10To11,
        Migration11To12,
        Migration12To13,
    )

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

/**
 * Adds `chapters.source_chapter_key`, moves chapter uniqueness onto it, and makes `chapters.name`
 * nullable — the chapter-shaped repeat of [Migration2To3], needed before a source sync can write
 * chapters at all.
 *
 * The old unique `(manga_id, url)` index assumed every chapter has its own permalink. Sources like
 * MangaDex address chapters by id and publish no per-chapter URL, so those chapters fall back to the
 * series URL and the *second* one to be inserted would collide. Identity moves to the source-native
 * key; `url` stays as a display permalink. `name` becomes nullable because most chapters are untitled,
 * and the localized "Chapter N" fallback belongs in the UI rather than frozen into a row in whatever
 * language happened to be active when it synced.
 *
 * SQLite can't drop a `NOT NULL`, so the table is rebuilt (the standard 12-step ALTER). Two details
 * make that safe for user data:
 *  - `reading_progress` cascades on `chapters`, so it is copied aside and restored around the rebuild.
 *    Room disables foreign keys during a migration, which alone would be enough — but the cost of
 *    being wrong is the user's reading history, and three extra statements is a cheap insurance
 *    premium. `INSERT OR IGNORE` no-ops when (as expected) nothing was cascaded away.
 *  - Chapter `id`s are carried over unchanged, so progress rows still point at their chapter and no
 *    navigation/history reference breaks.
 */
internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TEMP TABLE reading_progress_backup AS SELECT * FROM reading_progress")

        // Must match the schema Room expects for v4 (schemas/…/4.json) exactly, or the identity check
        // on the next open fails.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapters_new` (
                `id` TEXT NOT NULL,
                `manga_id` TEXT NOT NULL,
                `chapter_number` REAL,
                `name` TEXT,
                `source_chapter_key` TEXT,
                `url` TEXT NOT NULL,
                `date_upload` INTEGER,
                `date_fetch` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`manga_id`) REFERENCES `manga`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        // No key to backfill: rows written before this column existed were never matched by one. They
        // stay NULL — distinct under the unique index, and re-synced into place on the next refresh.
        connection.execSQL(
            """
            INSERT INTO chapters_new (id, manga_id, chapter_number, name, source_chapter_key, url, date_upload, date_fetch)
            SELECT id, manga_id, chapter_number, name, NULL, url, date_upload, date_fetch FROM chapters
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE chapters")
        connection.execSQL("ALTER TABLE chapters_new RENAME TO chapters")

        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `chapter_manga_source_key` " +
                "ON `chapters` (`manga_id`, `source_chapter_key`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapters_manga_id` ON `chapters` (`manga_id`)",
        )

        connection.execSQL("INSERT OR IGNORE INTO reading_progress SELECT * FROM reading_progress_backup")
        connection.execSQL("DROP TABLE reading_progress_backup")
    }
}

/**
 * Adds `chapters.scanlator` — the translation group the source credits for a chapter.
 *
 * On an aggregator like MangaDex several groups publish the same chapter number as genuinely distinct
 * chapters, and without the group credited the detail screen shows them as identical-looking rows. The
 * rows are kept (each is a real chapter with its own pages, and the user's progress hangs off one of
 * them) and the column is what lets the UI tell them apart.
 *
 * Nullable, and existing rows are left `NULL` rather than backfilled: the credit lives only in the
 * source's chapter feed, so there is nothing local to derive it from — the next refresh fills it in.
 */
internal object Migration4To5 : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chapters ADD COLUMN scanlator TEXT")
    }
}

/**
 * Adds `manga.filtered_scanlators` — the scanlation groups the user has hidden from a series' chapter
 * list, newline-joined (see `MangaEntity`).
 *
 * Nullable with no backfill: `NULL` is exactly the default the column means, "nothing hidden". The
 * filter excludes rather than includes, so a series gaining a new group later still shows it.
 */
internal object Migration5To6 : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE manga ADD COLUMN filtered_scanlators TEXT")
    }
}

/**
 * Adds `sources.icon_url` — the source's icon/logo, snapshotted at install time so a screen can show an
 * installed source's badge without instantiating its `.wasm`.
 *
 * Nullable with no backfill: the URL only exists in the bundle's metadata, and rows installed before
 * this column keep `NULL` (the lettered-avatar fallback) until the source is reinstalled. The same
 * one-way snapshot [Migration1To2] made of `sources.version`.
 */
internal object Migration6To7 : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE sources ADD COLUMN icon_url TEXT")
    }
}

/**
 * Adds `chapter_updates` — the local-only note that a chapter arrived *after* the user was already
 * following its series, which Home's "New Chapters" list and the update notification are built from.
 *
 * A new table rather than columns on `chapters`, for the reason `reading_progress` is also its own
 * table: the chapter row is source-owned and rewritten on every sync, so app-owned state beside it
 * would be clobbered. Nothing is backfilled — no existing chapter can be known to have arrived after
 * the user added the series (that is exactly the fact this table starts recording), and inventing a
 * backfill would announce a whole library's worth of chapters as "new" on first launch.
 */
internal object Migration7To8 : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Must match the schema Room expects for v8 (schemas/…/8.json) exactly, or the identity check
        // on the next open fails.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_updates` (
                `chapter_id` TEXT NOT NULL,
                `manga_id` TEXT NOT NULL,
                `discovered_at` INTEGER NOT NULL,
                `notified` INTEGER NOT NULL,
                PRIMARY KEY(`chapter_id`),
                FOREIGN KEY(`chapter_id`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_updates_manga_id` ON `chapter_updates` (`manga_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_updates_discovered_at` " +
                "ON `chapter_updates` (`discovered_at`)",
        )
    }
}

/**
 * Adds `manga.cover_headers` — the headers a cover image fetch has to carry, `name: value` per line.
 *
 * Almost every source needs none, but a source whose image CDN checks the `Referer` (Hipercool) answers
 * a bare cover request with a `403`, and a cover is rendered from this table long after the browse call
 * that produced it, with no source in scope to re-ask. Nullable and not backfilled: existing rows were
 * cached by sources that need no headers, and the correct value for any row that does will arrive on the
 * next sync of that series.
 */
internal object Migration8To9 : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE manga ADD COLUMN cover_headers TEXT")
    }
}

/**
 * Adds `chapter_downloads` — the index of which chapters are queued for, or already kept on, the
 * device, backing offline reading.
 *
 * A new table rather than columns on `chapters`, for the reason `chapter_updates` and
 * `reading_progress` are also their own tables: the chapter row is source-owned and rewritten on every
 * sync, so app-owned state beside it would be clobbered. Nothing to backfill — before this table there
 * was no download subsystem, so no chapter can be on disk.
 *
 * The three indexes each pay for a query the UI runs constantly: `manga_id` for a chapter list's
 * per-row states, `state` for the per-series downloaded counts behind the library badge, and
 * `queued_at` for the queue's order.
 */
internal object Migration9To10 : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        // Must match the schema Room expects for v10 (schemas/…/10.json) exactly, or the identity
        // check on the next open fails.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_downloads` (
                `chapter_id` TEXT NOT NULL,
                `manga_id` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `page_count` INTEGER,
                `downloaded_pages` INTEGER NOT NULL,
                `bytes` INTEGER NOT NULL,
                `error` TEXT,
                `queued_at` INTEGER NOT NULL,
                `completed_at` INTEGER,
                PRIMARY KEY(`chapter_id`),
                FOREIGN KEY(`chapter_id`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_downloads_manga_id` ON `chapter_downloads` (`manga_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_downloads_state` ON `chapter_downloads` (`state`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_downloads_queued_at` " +
                "ON `chapter_downloads` (`queued_at`)",
        )
    }
}

/**
 * Adds the four sync-bookkeeping columns to `manga` — `chapters_fetched_at`, `details_fetched_at`,
 * `next_update_at` and `fetch_interval`.
 *
 * Before these there was no record anywhere of *when* a series was last polled, so opening a series
 * re-asked its source every time. (The only other time-ish column on the table then was
 * `cover_last_modified`, which was not that record and was dropped outright by [Migration11To12].) The
 * first two feed the staleness gate in the sync use cases; the last two are the schedule a per-series
 * library update will keep, and are added now so that lands without a second migration.
 *
 * All nullable with nothing to backfill: no existing row has ever been stamped, and `NULL` is exactly
 * the value that means "never polled" — which every gate reads as "poll now". So the first open of any
 * series after upgrading behaves as it did before, and the schedule builds itself from there.
 *
 * `fetch_interval` is a day count that carries Mihon's sign convention: negative means the user pinned
 * the interval by hand and it must not be recomputed.
 */
internal object Migration10To11 : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE manga ADD COLUMN chapters_fetched_at INTEGER")
        connection.execSQL("ALTER TABLE manga ADD COLUMN details_fetched_at INTEGER")
        connection.execSQL("ALTER TABLE manga ADD COLUMN next_update_at INTEGER")
        connection.execSQL("ALTER TABLE manga ADD COLUMN fetch_interval INTEGER")
    }
}

/**
 * Drops `manga.cover_last_modified` — a cache-busting version for a cover that nothing ever keyed on.
 *
 * The column was documented from v1 as "the image loader's cache key", and two use cases reasoned
 * carefully about when *not* to bump it. But it never reached an image request: covers are fetched
 * through `coverImageRequest`, which passes a URL and headers and sets neither `memoryCacheKey` nor
 * `diskCacheKey`, so Coil keyed a cover on its URL alone. The one write that re-stamped it fired only
 * when the cover URL had *already* changed — so even wired up it could not have invalidated anything
 * the URL did not invalidate first.
 *
 * Deleting rather than wiring it is the decision recorded on `MangaCover`: the browse grid renders
 * covers straight from a source listing, which has no local row and so no version to key on, so a
 * version-keyed cover would be cached once for browse and again for the library.
 *
 * A plain `DROP COLUMN` rather than the 12-step rebuild [Migration3To4] needed: the column is in no
 * index, constraint, view or trigger, which is all SQLite asks. Both platforms run the bundled
 * `androidx.sqlite` driver (see `DatabaseBuilder`), so the statement is available and identical on each.
 * Nothing is lost that any query read.
 */
internal object Migration11To12 : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE manga DROP COLUMN cover_last_modified")
    }
}

/**
 * Widens `chapter_downloads`' `manga_id` index to `(manga_id, state)`.
 *
 * The library grid derives `downloaded_count` from a correlated sub-query filtering
 * `manga_id = ? AND state = 'Downloaded'`. With only single-column indexes to choose from, SQLite
 * picked `index_chapter_downloads_state` and scanned every downloaded row in the database **once per
 * library title** — on a query Room invalidates from six tables, including `chapter_downloads`, whose
 * `setProgress` fires once per downloaded page. Measured against a fixture built from the checked-in
 * `12.json` at 300 library titles and 1500 downloaded chapters: 26.4 ms → 4.8 ms, the plan going from
 * `SEARCH … USING INDEX index_chapter_downloads_state (state=?)` to a covering
 * `(manga_id=? AND state=?)` probe. At 2000 titles and 4000 downloaded rows, 402 ms → 17 ms.
 *
 * A widening, not an addition: `manga_id` is no FK child column here (the only foreign key is
 * `chapter_id`, which is also the primary key), so the narrow index carried no constraint duty, and
 * every `WHERE manga_id = ?` read still matches on the leftmost prefix — verified by query plan for
 * `observeForManga` and `deleteForManga`. `index_chapter_downloads_state` stays: the queue's
 * state-only reads use it and are optimal on it.
 *
 * Index-only, so nothing is rewritten and no data can be lost; the statements are identical on both
 * platforms under the bundled `androidx.sqlite` driver.
 */
internal object Migration12To13 : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP INDEX IF EXISTS index_chapter_downloads_manga_id")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_chapter_downloads_manga_id_state " +
                "ON chapter_downloads (manga_id, state)",
        )
    }
}
