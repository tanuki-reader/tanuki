package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import kotlin.uuid.Uuid

/**
 * A source that has been loaded and persisted locally.
 *
 * Carries two distinct identities, and both are needed:
 * - [id]: local persistence identity. This is what [app.tanuki.core.domain.manga.model.Manga.source]
 *   foreign-keys to.
 * - [sourceId]: source-native identity (e.g. `"mangadex"`), assigned by the source's WASM
 *   metadata/ABI. This is what [app.tanuki.core.domain.source.SourceLoader] addresses the source by
 *   (`fetchMangaList`, `fetchChapterPages`, …), so it bridges a local [Uuid] back to a runtime call.
 *
 * Contrast with [SourceMeta], which describes a source that has only been *discovered* (not yet
 * loaded/persisted) and therefore has no local [Uuid].
 *
 * @property id local persistence identity referenced by stored manga.
 * @property sourceId source-native identifier the WASM runtime addresses this source by.
 * @property lang language tag the source serves.
 * @property name human-readable source name.
 * @property url the source's base URL.
 * @property version the installed bundle's monotonic version, as its metadata reported it at install
 *   time, or `null` for a source that reports none. Persisted rather than re-read from the `.wasm` so
 *   an update check can compare every installed source against a remote repo index without
 *   instantiating a single module. Monotonic `Int` (not semver) so the comparison is a plain `>`.
 */
data class Source(
    val id: Uuid,
    val sourceId: String,
    val lang: Text,
    val name: Text,
    val url: Url,
    val version: Int? = null
)