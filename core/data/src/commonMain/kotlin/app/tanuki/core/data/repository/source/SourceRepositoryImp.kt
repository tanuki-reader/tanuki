package app.tanuki.core.data.repository.source

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Room-backed [SourceRepository]. Maps between [Source] domain models and [SourceEntity] rows,
 * delegating persistence to [SourceDao].
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceRepositoryImp(
    private val sourceDao: SourceDao,
    private val dispatcherProvider: DispatcherProvider
) : SourceRepository {
    override fun observeSources(): Flow<List<Source>> =
        sourceDao
            .observeSources()
            .map(List<SourceEntity>::toDomain)
            .flowOn(dispatcherProvider.io)

    override suspend fun getSource(id: Uuid): Source? = sourceDao.getById(id)?.toDomain()

    override suspend fun getSourceByNativeId(sourceId: String): Source? = sourceDao.getByNativeId(sourceId)?.toDomain()

    override suspend fun addSource(source: Source) = sourceDao.upsert(source.toEntity())

    override suspend fun deleteSource(source: Source) = sourceDao.deleteById(source.id)
}

private fun List<SourceEntity>.toDomain() = map(SourceEntity::toDomain)