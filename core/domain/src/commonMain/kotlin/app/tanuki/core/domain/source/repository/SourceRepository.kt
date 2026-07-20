package app.tanuki.core.domain.source.repository

import app.tanuki.core.domain.source.model.Source
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface SourceRepository {
    fun observeSources(): Flow<List<Source>>

    suspend fun getSource(id: Uuid): Source?

    suspend fun getSourceByNativeId(sourceId: String): Source?

    suspend fun addSource(source: Source)

    suspend fun deleteSource(source: Source)
}