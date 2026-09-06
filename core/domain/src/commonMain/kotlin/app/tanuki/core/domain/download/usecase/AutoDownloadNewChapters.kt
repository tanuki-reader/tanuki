package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Use case: queue the chapters a sync just found, when the user has asked for that.
 *
 * Called by [app.tanuki.core.domain.chapter.usecase.SyncSourceChapters] for every series it refreshes,
 * so it covers both halves of "new chapter arrives" — the scheduled library update and a manual pull on
 * a series page — without either caller knowing the rule.
 *
 * ### What it refuses to do
 *
 * Nothing is queued for a series outside the library. A sync also runs when the user is merely
 * *browsing* a source, and downloading a series they have not saved would spend their storage on
 * something they were only looking at. `SyncSourceChapters` already applies the same guard to chapter
 * updates, for the same reason.
 *
 * The chapters are queued exactly as `EnqueueChapterDownloads` would from a button, so a chapter that is
 * already downloaded or already queued is left alone and the queue's ordering is undisturbed.
 */
sealed interface AutoDownloadNewChapters {
    /**
     * @param manga The series the chapters belong to.
     * @param chapters The chapters this sync added. Empty is a no-op.
     * @return How many chapters were queued.
     */
    suspend operator fun invoke(
        manga: Manga,
        chapters: List<Chapter>,
    ): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val observeDownloadSettings: ObserveDownloadSettings,
        private val categoryRepository: CategoryRepository,
        private val enqueueChapterDownloads: EnqueueChapterDownloads,
        private val logger: Logger,
    ) : AutoDownloadNewChapters {
        override suspend fun invoke(
            manga: Manga,
            chapters: List<Chapter>,
        ): Int {
            // Checked before the settings read, because this is the overwhelmingly common case: most
            // syncs find nothing, and none of them should touch the preference store to learn that.
            if (chapters.isEmpty()) return 0
            if (manga.dateAdded == null) return 0

            val settings = observeDownloadSettings().first()
            // The membership map is read only for the one mode that needs it: a sync of fifty series
            // should not read the whole library's categories fifty times to be told "all" or "off".
            val applies =
                when (settings.autoDownloadMode) {
                    AutoDownloadMode.Off -> false
                    AutoDownloadMode.AllLibrary -> true
                    AutoDownloadMode.SelectedCategories ->
                        settings.downloadsCategory(memberships(manga.id))
                }
            if (!applies) return 0

            val queued = enqueueChapterDownloads(chapters.map(Chapter::id))
            if (queued > 0) {
                logger.info(TAG, "Auto-downloading $queued new chapter(s) of ${manga.title.value}")
            }
            return queued
        }

        private suspend fun memberships(mangaId: Uuid): Set<Uuid> =
            categoryRepository.observeLibraryCategories().first()[mangaId].orEmpty()

        private companion object {
            const val TAG = "AutoDownloadNewChapters"
        }
    }
}
