package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

interface ObserveLibraryGridSize {
    operator fun invoke(): Flow<LibraryGridSize>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : ObserveLibraryGridSize {
        override fun invoke(): Flow<LibraryGridSize> = settingsRepository.observeLibraryGridSize()
    }
}
