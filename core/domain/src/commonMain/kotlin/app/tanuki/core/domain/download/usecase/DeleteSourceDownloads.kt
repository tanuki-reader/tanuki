package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: delete everything downloaded from one source — the accepted half of the offer made when a
 * source is uninstalled.
 *
 * **Never called on the user's behalf.** Uninstalling a source deliberately leaves the library intact
 * (see [app.tanuki.core.domain.source.usecase.UninstallSource]), and downloaded chapters keep reading
 * offline with the source gone — so removing them is a separate thing the user asks for, not a
 * consequence of removing the bundle. The dialog offers it, defaulted off, and this runs only if it is
 * switched on.
 *
 * Delegates per series to [DeleteMangaDownloads], which already aborts anything in flight, drops the
 * records and removes the directory. Doing it a series at a time rather than a chapter at a time also
 * catches the drift the sweep exists for: a series whose records are gone but whose directory is not.
 */
sealed interface DeleteSourceDownloads {
    /**
     * @param sourceId The source's **native** id.
     * @return How many chapter records were removed; zero for an unknown source or one with nothing
     *   downloaded.
     */
    suspend operator fun invoke(sourceId: String): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceRepository: SourceRepository,
        private val mangaRepository: MangaRepository,
        private val deleteMangaDownloads: DeleteMangaDownloads,
        private val logger: Logger,
    ) : DeleteSourceDownloads {
        override suspend fun invoke(sourceId: String): Int {
            val mangaIds = mangaIdsOf(sourceId, sourceRepository, mangaRepository)
            if (mangaIds.isEmpty()) return 0

            val deleted = mangaIds.sumOf { deleteMangaDownloads(it) }
            if (deleted > 0) logger.info(TAG, "Deleted $deleted download(s) belonging to $sourceId")
            return deleted
        }

        private companion object {
            const val TAG = "DeleteSourceDownloads"
        }
    }
}
