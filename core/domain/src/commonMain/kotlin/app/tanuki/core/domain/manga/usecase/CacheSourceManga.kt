package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.toTextOrNull
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Use case: turn a browsed [MangaListing] into a persisted [Manga]
 * and return its local [Uuid], so the Browse screen can hand a tapped result off to the manga-detail
 * feature (which loads by [Uuid]).
 *
 * This is the seam the source layer deliberately left open: a listing has no local identity or user
 * state, and only becomes a `Manga` when the user acts on it. Here that inflation is **cache-only** —
 * the row is written with no library membership ([Manga.dateAdded] `null`), so it can be viewed and read
 * without being added to the library. Adding to the library is a separate action the detail screen owns
 * via [AddToLibrary].
 *
 * Idempotent: a series already cached (matched on its source + permalink) is returned as-is and is
 * **not** rewritten — that would risk clobbering an existing library membership. So re-tapping a title
 * in browse always resolves to the same row, whether or not it's a favorite.
 */
sealed interface CacheSourceManga {
    /**
     * @param sourceId source-native id the [listing] came from (e.g. `"mangadex"`).
     * @param listing the browsed entry to persist.
     * @return the local id of the cached (or already-present) manga.
     */
    suspend operator fun invoke(
        sourceId: String,
        listing: MangaListing
    ): Uuid

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceLoader: SourceLoader,
        private val mangaRepository: MangaRepository
    ) : CacheSourceManga {
        override suspend fun invoke(
            sourceId: String,
            listing: MangaListing
        ): Uuid {
            // Resolve (installing on first use) the source's local identity — Manga.source foreign-keys
            // to it, and it's the dedupe scope. A listing carries only the source-native id.
            val source = sourceLoader.loadSource(sourceId)

            // Already cached → reuse the existing row (preserves any library membership). Deduped on the
            // source-native key, not the permalink: url-less series all fall back to the source home, so
            // keying on url would merge unrelated titles into one row.
            mangaRepository.getMangaIdBySourceAndKey(source.id, listing.sourceMangaKey)?.let { existingId ->
                refreshCoverHeaders(existingId, listing)
                return existingId
            }

            // The permalink is a display/permalink URL; fall back to the source home if the source
            // published none, so the row still has a valid absolute URL.
            val url = listing.url ?: source.url

            val id = uuidV7()
            mangaRepository.upsertManga(
                Manga(
                    id = id,
                    source = source.id,
                    title = listing.title.toTextOrNull() ?: Text("Untitled"),
                    author = listing.author.toTextOrNull(),
                    description = listing.description.toTextOrNull(),
                    cover =
                        MangaCover(
                            mangaId = id,
                            url = listing.coverUrl,
                            lastModified = Clock.System.now(),
                            headers = listing.coverHeaders,
                        ),
                    sourceMangaKey = listing.sourceMangaKey.toTextOrNull(),
                    url = url,
                    dateAdded = null, // cache-only: viewable/readable, not a library entry
                    readingMode = ReadingMode.LEFT_TO_RIGHT, // sensible default until details refine it
                    publishStatus = listing.status,
                    readingStatus = null
                )
            )
            return id
        }

        /**
         * Brings an already-cached row's [MangaCover.headers] in line with what the source says now.
         *
         * The one thing about an existing row worth rewriting. Cover headers are source-owned transport
         * metadata, not user state, and getting them wrong is not cosmetic: a source whose CDN checks
         * the `Referer` renders every stale row as a blank tile, in the library, forever — a row cached
         * before the source gained its headers would never recover, since browse otherwise returns
         * early and only opening the series triggers a details sync.
         *
         * Writes only on an actual difference, so the overwhelmingly common case (no headers, before and
         * after) is a read and nothing else. [MangaCover.lastModified] is deliberately not bumped — it is
         * the image loader's cache key, and the art hasn't changed, only how it must be requested.
         */
        private suspend fun refreshCoverHeaders(
            mangaId: Uuid,
            listing: MangaListing
        ) {
            val manga = mangaRepository.observeManga(mangaId).first() ?: return
            if (manga.cover.headers == listing.coverHeaders) return
            mangaRepository.upsertManga(manga.copy(cover = manga.cover.copy(headers = listing.coverHeaders)))
        }
    }
}