package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url

/**
 * A source as the *management* surface sees it: identity, how to label it, and whether its bundle is
 * currently on disk. This is the one shape that spans both sides of that screen — an installed source
 * and an installable one differ only in [installed].
 *
 * An installed source is described by its bundle's own metadata ([SourceMeta]); an installable one by
 * the verified index of the repo offering it ([RemoteSource]).
 *
 * @property sourceId source-native identifier the runtime addresses the source by.
 * @property name display name.
 * @property lang language tag the source serves, or `null` when it isn't known.
 * @property iconUrl remote icon URL, or `null` for the lettered fallback avatar.
 * @property installed whether this source's bundle is on disk right now.
 * @property nsfw whether the source is adult-only.
 * @property version the installed bundle's version, or the version on offer when not installed.
 * @property repoName the repo offering it, or `null` for an installed source no added repo offers.
 * @property compatibility whether this app can run the version on offer; always compatible once
 *   installed (an incompatible bundle never loads).
 */
data class CatalogSource(
    val sourceId: String,
    val name: Text,
    val lang: Text? = null,
    val iconUrl: Url? = null,
    val installed: Boolean = false,
    val nsfw: Boolean = false,
    val version: Int? = null,
    val repoName: String? = null,
    val compatibility: SourceCompatibility = SourceCompatibility.Compatible
)
