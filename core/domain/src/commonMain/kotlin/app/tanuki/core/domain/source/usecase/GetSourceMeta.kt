package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.model.SourceMeta
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: resolve one installed source's [SourceMeta] by its source-native id, or `null` when no such
 * source is installed.
 *
 * The runtime addresses sources by id but only exposes them as a list, so "find one" is a scan over
 * [GetInstalledSources]. That scan lives here rather than in a ViewModel: a screen that opens a source
 * asks for *that source*, and shouldn't know the lookup is a filter over an enumeration — this is the
 * seam to narrow if the loader ever gains a direct by-id metadata call.
 *
 * `null` is the caller's cue to show a hard error: a source that can't describe itself can't be browsed.
 */
sealed interface GetSourceMeta {
    suspend operator fun invoke(sourceId: String): SourceMeta?

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val getInstalledSources: GetInstalledSources
    ) : GetSourceMeta {
        override suspend fun invoke(sourceId: String): SourceMeta? =
            getInstalledSources().firstOrNull { it.id == sourceId }
    }
}