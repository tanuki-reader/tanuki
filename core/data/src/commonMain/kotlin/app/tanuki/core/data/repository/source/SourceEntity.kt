package app.tanuki.core.data.repository.source

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.source.model.Source
import kotlin.uuid.Uuid

/**
 * Room row for a persisted [Source]. Carries two identities: [id] (local surrogate key that
 * `manga.source` references) and [sourceId] (the source-native string the WASM runtime addresses it
 * by, e.g. `"mangadex"`), which is globally unique.
 */
@Entity(
    tableName = "sources",
    indices = [Index(value = ["source_id"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val lang: String,
    val name: String,
    val url: String,
    /**
     * Remote URL of the source's icon/logo, or `null` when the source reports none. Snapshotted at
     * install time so a screen can render an installed source's badge without loading its `.wasm`.
     */
    @ColumnInfo(name = "icon_url") val iconUrl: String? = null,
    /**
     * Monotonic version of the installed bundle, or `null` for a source that reports none. Stored so
     * an update check can compare every installed source against a remote repo index without loading
     * any `.wasm`.
     */
    val version: Int? = null,
)

fun SourceEntity.toDomain(): Source =
    Source(
        id = id,
        sourceId = sourceId,
        lang = Text(lang),
        name = Text(name),
        url = Url(url),
        iconUrl = iconUrl?.let(::Url),
        version = version,
    )

fun Source.toEntity(): SourceEntity =
    SourceEntity(
        id = id,
        sourceId = sourceId,
        lang = lang.value,
        name = name.value,
        url = url.value,
        iconUrl = iconUrl?.value,
        version = version,
    )