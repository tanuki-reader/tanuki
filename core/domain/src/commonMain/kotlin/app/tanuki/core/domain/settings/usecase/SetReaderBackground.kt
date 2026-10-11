package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.model.ReaderBackground
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

interface SetReaderBackground {
    suspend operator fun invoke(background: ReaderBackground)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : SetReaderBackground {
        override suspend fun invoke(background: ReaderBackground) = settingsRepository.setReaderBackground(background)
    }
}
