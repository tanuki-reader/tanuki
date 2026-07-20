package app.tanuki.core.data.repository.source

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Room DAO for [SourceEntity]. */
@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Uuid): SourceEntity?

    @Query("SELECT * FROM sources WHERE source_id = :sourceId")
    suspend fun getByNativeId(sourceId: String): SourceEntity?

    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: Uuid)
}
