package app.tanuki.core.data.repository.bookmark

import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PageBookmarkRepositoryImp(
    private val dao: PageBookmarkDao,
    private val dispatcherProvider: DispatcherProvider,
) : PageBookmarkRepository {
    override fun observeForManga(mangaId: Uuid): Flow<List<PageBookmark>> =
        dao
            .observeForManga(mangaId)
            .map { rows -> rows.map(PageBookmarkEntity::toDomain) }
            .flowOn(dispatcherProvider.io)

    override fun observeAll(): Flow<List<PageBookmarkEntry>> =
        dao
            .observeAll()
            .map { rows -> rows.map(PageBookmarkRow::toDomain) }
            .flowOn(dispatcherProvider.io)

    override suspend fun bookmarkedChapterIds(mangaId: Uuid): Set<Uuid> = dao.bookmarkedChapterIds(mangaId).toSet()

    override suspend fun add(bookmark: PageBookmark) = dao.upsert(bookmark.toEntity())

    override suspend fun removeAt(
        chapterId: Uuid,
        page: Int,
    ): Boolean = dao.deleteAt(chapterId, page) > 0

    override suspend fun remove(id: Uuid) = dao.delete(id)
}
