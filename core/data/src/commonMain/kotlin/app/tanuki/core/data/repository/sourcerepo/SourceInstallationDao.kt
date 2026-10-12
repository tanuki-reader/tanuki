package app.tanuki.core.data.repository.sourcerepo

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room DAO for [SourceInstallationEntity]. */
@Dao
interface SourceInstallationDao {
    @Query("SELECT * FROM source_installations")
    fun observeAll(): Flow<List<SourceInstallationEntity>>

    @Query("SELECT repo_url FROM source_installations WHERE source_id = :sourceId")
    suspend fun getRepoUrl(sourceId: String): String?

    @Upsert
    suspend fun upsert(installation: SourceInstallationEntity)
}
