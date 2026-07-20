package app.tanuki.core.data.repository.chapter

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    override suspend fun upsertChapter(chapter: Chapter) =
        chapterDao.upsertWithProgress(chapter.toEntity(), chapter.toProgressEntity())

    override suspend fun deleteChapter(chapterId: Uuid) = chapterDao.deleteById(chapterId)
}

private fun List<ChapterWithProgress>.toDomain() = map(ChapterWithProgress::toDomain)