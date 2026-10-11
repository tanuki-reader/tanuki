package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url

/**
 * Lightweight catalog entry for a source — enough to list/browse sources without loading one.
 *
 * Identified by a source-native string [id] (e.g. `"mangadex"`), NOT the local persistence `Uuid`
 * carried by an installed [Source]. A source's identity comes from its WASM metadata/ABI, not from
 * our database (see "Source ecosystem & naming" in CLAUDE.md); a `Uuid` is assigned only once the
 * source is persisted locally.
 *
 * Kept deliberately minimal. Expected to grow as the Browse screen needs it — install state
 * (installed vs. available extension), icon, title count, NSFW flag.
 *
 * @property id source-native identifier the runtime addresses the source by.
 * @property name human-readable source name.
 * @property lang language tag the source serves.
 * @property version monotonic version the source reports, or `null` when it reports none. Compared
 *   against the version advertised by a remote source repo to decide whether an update is available;
 *   see [Source.version] for the persisted counterpart.
 * @property iconUrl remote URL of the source's icon/logo shown beside its name in the source list, or
 *   `null` when the source reports none (the UI then falls back to a lettered avatar). Optional in the
 *   ABI's source metadata, so an older source that doesn't report it simply yields `null`.
 * @property listings which of the contract's [SourceListing]s this source serves, in [SourceListing]'s
 *   own order (not the source's — the vocabulary and its presentation order are the app's). Empty when
 *   the source exposes only its single default catalog.
 * @property nsfw whether the source is adult-only. A source with *some* adult titles is not `nsfw`; it
 *   filters those itself when the user hasn't allowed sensitive content.
 */
data class SourceMeta(
    val id: String,
    val name: Text,
    val lang: Text,
    val version: Int? = null,
    val iconUrl: Url? = null,
    val listings: List<SourceListing> = emptyList(),
    val nsfw: Boolean = false
)