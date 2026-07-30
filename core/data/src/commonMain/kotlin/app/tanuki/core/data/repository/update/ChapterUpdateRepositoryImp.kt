package app.tanuki.core.data.repository.update

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Room-backed [ChapterUpdateRepository]. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterUpdateRepositoryImp(
    private val chapterUpdateDao: ChapterUpdateDao,
    private val dispatcherProvider: DispatcherProvider
) : ChapterUpdateRepository {
    override fun observeUpdates(): Flow<List<ChapterUpdate>> =
        chapterUpdateDao
            .observeUpdates()
            .map { updates -> updates.map(ChapterUpdateEntity::toDomain) }
            .flowOn(dispatcherProvider.io)

    override suspend fun getUnnotified(): List<ChapterUpdate> =
        withContext(dispatcherProvider.io) {
            chapterUpdateDao.getUnnotified().map(ChapterUpdateEntity::toDomain)
        }

    override suspend fun recordUpdates(updates: List<ChapterUpdate>) {
        if (updates.isEmpty()) return
        withContext(dispatcherProvider.io) {
            chapterUpdateDao.insertIgnoring(updates.map(ChapterUpdate::toEntity))
        }
    }

    override suspend fun markNotified(chapterIds: List<Uuid>) {
        if (chapterIds.isEmpty()) return
        withContext(dispatcherProvider.io) { chapterUpdateDao.markNotified(chapterIds) }
    }

    override suspend fun deleteForManga(mangaId: Uuid) =
        withContext(dispatcherProvider.io) { chapterUpdateDao.deleteForManga(mangaId) }

    override suspend fun deleteChaptersForManga(
        mangaId: Uuid,
        chapterIds: List<Uuid>
    ) {
        if (chapterIds.isEmpty()) return
        withContext(dispatcherProvider.io) { chapterUpdateDao.deleteForMangaAndChapters(mangaId, chapterIds) }
    }
}