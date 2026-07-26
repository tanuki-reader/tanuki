package app.tanuki.core.data.repository.chapter

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Room-backed [ChapterRepository]. A chapter and its local-only [ReadingProgressEntity] live in
 * separate tables; [upsertChapter] commits both together via [ChapterDao.upsertWithProgress], and a
 * chapter row with no progress row maps to [Chapter.progress] == `null`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterRepositoryImp(
    private val chapterDao: ChapterDao,
    private val dispatcherProvider: DispatcherProvider
) : ChapterRepository {
    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> =
        chapterDao
            .observeChapters(mangaId)
            .map(List<ChapterWithProgress>::toDomain)
            .flowOn(dispatcherProvider.io)

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> =
        chapterDao
            .observeBookmarkedChapters(mangaId)
            .map(List<ChapterWithProgress>::toDomain)
            .flowOn(dispatcherProvider.io)

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> =
        withContext(dispatcherProvider.io) { chapterDao.getChapters(mangaId).toDomain() }

    override suspend fun getChapter(chapterId: Uuid): Chapter? =
        withContext(dispatcherProvider.io) { chapterDao.getChapter(chapterId)?.toDomain() }

    override suspend fun updateProgress(
        chapterId: Uuid,
        progress: ReadingProgress
    ) = withContext(dispatcherProvider.io) { chapterDao.upsertProgress(progress.toEntity(chapterId)) }

    override suspend fun upsertChapter(chapter: Chapter) =
        chapterDao.upsertWithProgress(chapter.toEntity(), chapter.toProgressEntity())

    /**
     * Writes chapter rows only, leaving progress alone — unlike [upsertChapter], whose single-chapter
     * path reconciles the progress row the caller supplies. A sync builds its chapters from source
     * data, where [Chapter.progress] is always `null`; routing it through the reconciling path would
     * read that `null` as "clear the progress" and wipe the user's reading state on every refresh.
     */
    override suspend fun upsertChapters(chapters: List<Chapter>) =
        chapterDao.upsertChapters(chapters.map(Chapter::toEntity))

    override suspend fun deleteChapter(chapterId: Uuid) = chapterDao.deleteById(chapterId)

    override suspend fun deleteChapters(chapterIds: List<Uuid>) = chapterDao.deleteByIds(chapterIds)
}

private fun List<ChapterWithProgress>.toDomain() = map(ChapterWithProgress::toDomain)