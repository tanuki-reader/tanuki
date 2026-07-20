package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Text

/**
 * Lightweight catalog entry for a source — enough to list/browse sources without loading one.
 *
 * Identified by a source-native string [id] (e.g. `"mangadex"`), NOT the local persistence `Uuid`
 * carried by an installed [Source]. A source's identity comes from its WASM metadata/ABI, not from
 * our database (see "Source ecosystem & naming" in CLAUDE.md); a `Uuid` is assigned only once the
 * source is persisted locally.
 *
 * Kept deliberately minimal. Expected to grow as the Browse screen needs it — version, install
 * state (installed vs. available extension), icon, title count, NSFW flag.
 *
 * @property id source-native identifier the runtime addresses the source by.
 * @property name human-readable source name.
 * @property lang language tag the source serves.
 */
data class SourceMeta(
    val id: String,
    val name: Text,
    val lang: Text
)