package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url

/**
 * A source as the *management* surface sees it: identity, how to label it, and whether its bundle is
 * currently on disk. This is the one shape that spans both sides of that screen — an installed source
 * and an uninstalled-but-reinstallable one differ only in [installed].
 *
 * Distinct from its two neighbours by what it can describe:
 * - [SourceMeta] comes from a bundle's own metadata, so it exists **only while the source is
 *   installed** — there is nothing to read once the `.wasm` is gone.
 * - [Source] is the persisted local row, which survives an uninstall but is only minted once a source
 *   has actually been loaded.
 *
 * A management screen needs to list both, so [name]/[lang]/[iconUrl] are filled from whichever of the
 * two is available — the live metadata when installed, the persisted snapshot when not. When neither
 * is (a bundled source that has never been installed on this device), [name] falls back to [sourceId],
 * which is a slug like `"mangadex"` — poor copy, but it never leaves a row blank.
 *
 * @property sourceId source-native identifier the runtime addresses the source by.
 * @property name display name, or the raw [sourceId] when nothing better is known.
 * @property lang language tag the source serves, or `null` when it isn't known yet.
 * @property iconUrl remote icon URL, or `null` for the lettered fallback avatar.
 * @property installed whether this source's bundle is on disk right now.
 */
data class CatalogSource(
    val sourceId: String,
    val name: Text,
    val lang: Text? = null,
    val iconUrl: Url? = null,
    val installed: Boolean = false
)
