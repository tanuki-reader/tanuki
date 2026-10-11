package app.tanuki.core.domain.library.usecase

import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** The library's remembered filter and sort; see [LibraryPreferences]. */
interface ObserveLibraryPreferences {
    operator fun invoke(): Flow<LibraryPreferences>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : ObserveLibraryPreferences {
        override fun invoke(): Flow<LibraryPreferences> = settingsRepository.observeLibraryPreferences()
    }
}
