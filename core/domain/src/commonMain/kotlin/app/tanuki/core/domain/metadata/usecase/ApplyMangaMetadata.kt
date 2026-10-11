package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: carry a series' metadata onto its `manga` row, where every list reads it.
 *
 * - **Cover:** the user's pick, else the metadata's cover (unless the user prefers the source's info),
 *   else none — which means the source's own.
 * - **Reading mode:** the one [suggestedReadingMode] gives, unless the user chose one themselves (the
 *   repository keeps that rule, see [MangaRepository.suggestReadingMode]).
 *
 * Run after enrichment and after every edit to the link, so a change shows in the library at once.
 */
interface ApplyMangaMetadata {
    suspend operator fun invoke(mangaId: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val metadataRepository: MetadataRepository
    ) : ApplyMangaMetadata {
        override suspend fun invoke(mangaId: Uuid) {
            val link = metadataRepository.getLink(mangaId)
            val metadata = link?.anilistId?.let { metadataRepository.getMetadata(it, link.lang) }
            val preferred = metadata?.takeUnless { link.useSourceInfo }
            mangaRepository.setCoverOverride(mangaId, link?.coverChoice ?: preferred?.coverUrl)
            metadata?.let(::suggestedReadingMode)?.let { mangaRepository.suggestReadingMode(mangaId, it) }
        }
    }
}

/**
 * How a series is usually read, from where it was published: Japanese manga right to left; Korean,
 * Chinese and Taiwanese comics — and anything tagged a long strip — as a webtoon. ("Full Color" alone
 * isn't enough: plenty of Japanese colour manga read right to left.)
 * `null` when the metadata says nothing either way.
 */
fun suggestedReadingMode(metadata: SeriesMetadata): ReadingMode? =
    when {
        metadata.tags.any { it.name.equals(LONG_STRIP, ignoreCase = true) } -> ReadingMode.WEBTOON
        metadata.countryOfOrigin == "JP" -> ReadingMode.RIGHT_TO_LEFT
        metadata.countryOfOrigin in WEBTOON_ORIGINS -> ReadingMode.WEBTOON
        else -> null
    }

private const val LONG_STRIP = "Long Strip"
private val WEBTOON_ORIGINS = setOf("KR", "CN", "TW")
