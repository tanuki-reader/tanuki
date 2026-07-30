package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.update.service.LibraryUpdateScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

/**
 * Use case: keep the platform's background schedule in step with the user's chosen interval.
 *
 * Collecting a setting rather than reading it once is what makes changing the interval take effect at
 * all: the schedule lives in WorkManager / `BGTaskScheduler`, not in the app, so nothing re-applies it
 * unless something is watching. Doing it here — rather than in whatever settings screen eventually
 * offers the choice — means the schedule is correct even on a launch where the user never opens
 * settings, and there is no way to add a second way of changing the interval that forgets to reschedule.
 *
 * Suspends forever; call it once on an application-lifetime scope.
 */
sealed interface KeepLibraryUpdateScheduled {
    suspend operator fun invoke(): Nothing

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
        private val scheduler: LibraryUpdateScheduler
    ) : KeepLibraryUpdateScheduled {
        override suspend fun invoke(): Nothing {
            settingsRepository
                .observeLibraryUpdateInterval()
                // Re-applying an unchanged interval would reset its timer on every emission, so an app
                // opened twice a day would never reach the end of a daily period.
                .distinctUntilChanged()
                .onEach(scheduler::schedule)
                .collect()
            error("observeLibraryUpdateInterval completed; a settings flow is expected to run forever")
        }
    }
}