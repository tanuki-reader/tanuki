package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.manga.model.PublishStatus

/**
 * A single catalog/search result as a source provides it — the **pre-persistence** shape of a manga.
 *
 * This is deliberately NOT [app.tanuki.core.domain.manga.model.Manga]: a browsed entry has no local
 * `Uuid` and no user state (favorite flag, reading status, date added). Those exist only once the user
 * adds the series to their library, at which point an add-to-library use case inflates a [MangaListing]
 * into a persisted `Manga`. Keeping browse results in their own type stops persistence identity from
 * leaking into the source layer and lets the loader map source data totally and losslessly.
 *
 * Addressed by [sourceMangaKey] — the source-native manga id (Mihon's "url"/key) — which is what later
 * source calls (chapter lists, pages) use to identify this series.
 *
 * @property sourceMangaKey Source-native manga id; stable address of this series within its source.
 * @property url Absolute permalink to the series on its source, when the source provides one — the
 *   natural key a persisted `Manga` stores. `null` when the source publishes none; the caller then
 *   falls back to the source's base URL.
 * @property title The series title as the source lists it.
 * @property originalTitle The source's original/romanized title when it differs from [title]; `null`
 *   otherwise.
 * @property coverUrl Cover image URL, or `null` when the source provides none/an unparseable one.
 * @property coverHeaders Headers the app must send when fetching [coverUrl] — empty for almost every
 *   source; non-empty when its image CDN checks the `Referer`. Carried on to
 *   [app.tanuki.core.domain.manga.model.MangaCover.headers] when the series is persisted.
 * @property author Author/artist credit, or `null` when the source provides none.
 * @property description Synopsis, or `null` when the source provides none.
 * @property status Publication status as reported by the source; [PublishStatus.UNKNOWN] when absent or
 *   unrecognised.
 */
data class MangaListing(
    val sourceMangaKey: String,
    val url: Url? = null,
    val title: String,
    val originalTitle: String? = null,
    val coverUrl: Url? = null,
    val coverHeaders: Map<String, String> = emptyMap(),
    val author: String? = null,
    val description: String? = null,
    val status: PublishStatus = PublishStatus.UNKNOWN,
)