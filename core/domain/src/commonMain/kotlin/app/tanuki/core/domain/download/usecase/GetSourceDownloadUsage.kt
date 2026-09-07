package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.SourceDownloadUsage
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Use case: what a source's downloads cost — the number the uninstall dialog puts in front of the user
 * before asking whether to delete them.
 *
 * ### Why this is a join and not a column
 *
 * A [ChapterDownload] records a chapter and its series, never a source, and it should stay that way: the
 * source is a property of the *series*, so storing it on every chapter record would be a denormalization
 * that a series moving between sources could silently falsify. [MangaRepository.observeMangaBySource] is
 * the join that already exists, and this is the only screen that asks the question.
 *
 * Note the two kinds of source id in play: [SourceRepository.getSourceByNativeId] turns the string a
 * source calls itself (`"mangadex"`, what an install/uninstall is addressed by) into the local row
 * [Uuid] that a manga actually carries.
 *
 * Counts only chapters that are **downloaded** — a queued or failed one costs a partial `.part` at most,
 * and offering to delete work that has not happened yet would read as cancelling a queue.
 */
sealed interface GetSourceDownloadUsage {
    /**
     * @param sourceId The source's **native** id, as [app.tanuki.core.domain.source.usecase.UninstallSource]
     *   takes it.
     * @return The usage, or an empty one for a source that isn't known locally or has nothing downloaded.
     */
    suspend operator fun invoke(sourceId: String): SourceDownloadUsage

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceRepository: SourceRepository,
        private val mangaRepository: MangaRepository,
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
    ) : GetSourceDownloadUsage {
        override suspend fun invoke(sourceId: String): SourceDownloadUsage {
            val mangaIds = mangaIdsOf(sourceId, sourceRepository, mangaRepository)
            if (mangaIds.isEmpty()) return SourceDownloadUsage()

            val downloaded =
                downloadRepository
                    .getAll()
                    .filter { it.isComplete && it.mangaId in mangaIds }
            if (downloaded.isEmpty()) return SourceDownloadUsage()

            // Measured from the files, like the storage figure in settings: the records' own byte counts
            // drift from what the OS would report, and this number exists to be compared against exactly
            // that. Only series that actually hold something are walked.
            val bytes = downloaded.mapTo(mutableSetOf(), ChapterDownload::mangaId).sumOf { store.usedBytes(it) }
            return SourceDownloadUsage(chapters = downloaded.size, bytes = bytes)
        }
    }
}

/**
 * Local ids of every cached series belonging to the source natively called [sourceId], empty when the
 * source has no local row.
 *
 * Shared by [GetSourceDownloadUsage] and [DeleteSourceDownloads] so the two cannot disagree about which
 * series an uninstall is talking about — the count shown and the files deleted have to come from one
 * rule, or the dialog promises something other than what the button does.
 */
internal suspend fun mangaIdsOf(
    sourceId: String,
    sourceRepository: SourceRepository,
    mangaRepository: MangaRepository,
): Set<Uuid> {
    val source = sourceRepository.getSourceByNativeId(sourceId) ?: return emptySet()
    return mangaRepository.observeMangaBySource(source.id).first().mapTo(mutableSetOf(), Manga::id)
}
