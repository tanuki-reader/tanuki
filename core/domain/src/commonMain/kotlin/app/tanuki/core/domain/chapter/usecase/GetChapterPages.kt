package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Use case: fetch the ordered pages of a chapter for the reader.
 *
 * The reader is opened with a local chapter [Uuid] (what navigation and history speak), but the source
 * layer addresses content by *source-native* ids — a source id like `"mangadex"` plus the chapter's own
 * key. This use case is the translation: it resolves the local chapter to its owning manga and source,
 * then asks the [SourceLoader] for the pages. Keeping that resolution here (not in the ViewModel) is
 * what lets the reader stay unaware of source-native ids, mirroring how
 * [SyncSourceChapters] resolves a manga before fetching its chapter list.
 *
 * Returns the source's own page order. [Page] is a descriptor only (never image bytes) — the reader
 * fetches the images from each page's URL through the image pipeline.
 *
 * Failure is surfaced by throwing, not by returning an empty list: an empty result is a *valid* answer
 * (a chapter a source genuinely serves no pages for) and must be distinguishable from "we couldn't ask".
 * Resolution gaps throw [IllegalStateException]; whatever the source layer throws (no network, HTTP
 * error, guest-side `SourceAbiException`) propagates unchanged. The caller decides how to surface it.
 */
sealed interface GetChapterPages {
    /**
     * @param chapterId Local id of the chapter to read.
     * @throws IllegalStateException when the chapter, its source key, its manga, or its source cannot
     *   be resolved locally — a broken deep link or a row predating the source key being persisted.
     */
    suspend operator fun invoke(chapterId: Uuid): List<Page>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterRepository: ChapterRepository,
        private val mangaRepository: MangaRepository,
        private val sourceRepository: SourceRepository,
        private val sourceLoader: SourceLoader
    ) : GetChapterPages {
        override suspend fun invoke(chapterId: Uuid): List<Page> {
            val chapter =
                chapterRepository.getChapter(chapterId)
                    ?: error("No chapter with id $chapterId")
            // The source's own chapter key — how the loader addresses this chapter inside its bundle.
            val chapterKey =
                chapter.sourceChapterKey?.value
                    ?: error("Chapter $chapterId has no source key to fetch pages with")
            val manga =
                mangaRepository.observeManga(chapter.mangaId).first()
                    ?: error("No manga with id ${chapter.mangaId} for chapter $chapterId")
            // The source's native id ("mangadex") selects the bundle the chapter key lives in.
            val source =
                sourceRepository.getSource(manga.source)
                    ?: error("No source with id ${manga.source} for manga ${manga.id}")

            return sourceLoader.fetchChapterPages(source.sourceId, chapterKey)
        }
    }
}