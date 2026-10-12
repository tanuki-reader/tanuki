package app.tanuki.core.data.repository.sourcerepo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.tanuki.core.domain.source.repository.StoredSourceRepo

/** Room row for an added source repo, keyed by its normalized base URL. See [StoredSourceRepo]. */
@Entity(tableName = "source_repos")
data class SourceRepoEntity(
    @PrimaryKey @ColumnInfo(name = "base_url") val baseUrl: String,
    val name: String,
    @ColumnInfo(name = "public_key") val publicKey: String,
    @ColumnInfo(name = "index_json") val indexJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

fun SourceRepoEntity.toDomain() = StoredSourceRepo(baseUrl, name, publicKey, indexJson, fetchedAt)

fun StoredSourceRepo.toEntity() = SourceRepoEntity(baseUrl, name, publicKey, indexJson, fetchedAt)
