package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.model.SourceMeta
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Use case: the installed sources the user can see — adult-only ones are left out until sensitive content
 * is allowed (Settings → Sources). Follows both the installed set and the toggle, so a screen holding it
 * never shows a stale list.
 */
interface ObserveVisibleSources {
    operator fun invoke(): Flow<List<SourceMeta>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val getInstalledSources: GetInstalledSources,
        private val observeShowSensitiveContent: ObserveShowSensitiveContent
    ) : ObserveVisibleSources {
        override fun invoke(): Flow<List<SourceMeta>> =
            combine(getInstalledSources(), observeShowSensitiveContent()) { sources, showSensitive ->
                if (showSensitive) sources else sources.filterNot { it.nsfw }
            }
    }
}
