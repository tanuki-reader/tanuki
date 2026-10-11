package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Whether the user allows sensitive (adult) content in sources and their catalogs; `false` by default. */
interface ObserveShowSensitiveContent {
    operator fun invoke(): Flow<Boolean>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : ObserveShowSensitiveContent {
        override fun invoke(): Flow<Boolean> = settingsRepository.observeShowSensitiveContent()
    }
}
