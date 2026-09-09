package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.chapter.usecase.SyncSourceChapters
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.manga.service.fetchWindow
import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.update.model.LibraryUpdateTarget
import app.tanuki.core.domain.update.usecase.LibraryUpdateResult.Completed
import app.tanuki.core.domain.update.usecase.LibraryUpdateResult.Skipped
import app.tanuki.core.domain.update.usecase.UpdateLibrary.Trigger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Use case: check every series in the user's library for new chapters.
 *
 * The whole feature is one loop over [SyncSourceChapters] — which already knows how to merge a source's
 * feed and record what arrived — so nothing about "what is a new chapter" lives here. What this owns is
 * everything about doing it *en masse*: when a run is allowed, how hard to push the sources, and how a
 * single bad series is prevented from taking the run down with it.
 *
 * ### One run at a time
 *
 * Runs never overlap. A second caller (a scheduled run firing while the user pulled to refresh) gets
 * [Skipped] rather than queueing, because by the time it would start, the run in flight has already
 * fetched everything it was going to ask for.
 *
 * ### Per-source, not per-series, concurrency
 *
 * Series are grouped by source and each source is worked through **sequentially**, with a handful of
 * *sources* progressing at once. Firing every series of one source in parallel is what gets a client
 * rate-limited or handed a Cloudflare challenge — the very thing that makes the next fifty requests
 * fail. Spreading the parallelism across sources gets the throughput without the burst.
 *
 * ### Not every series, every run
 *
 * A scheduled run only checks the series that could plausibly have something new. Each successful
 * refresh works out how often that series actually publishes and stamps the day to next reconsider it
 * (see [app.tanuki.core.domain.manga.service.fetchSchedule], which [SyncSourceChapters] applies while
 * the chapter list is still in hand); later runs skip anything still ahead of that day. A finished
 * series decays to a monthly check, a weekly one keeps its weekly check, and neither needed a setting.
 * [Trigger.Manual] ignores the schedule entirely — asking to update the library means all of it.
 *
 * ### A failure is one series, not the run
 *
 * Sources go down, keys rot, and a phone loses signal mid-run. Each series is refreshed inside its own
 * failure boundary and a broken one is logged and counted; the rest still update. The one case treated
 * as a failed *run* is every series failing, which is a symptom of the environment (offline, no sources
 * installed) rather than of the library — so it deliberately does not stamp the "last updated" clock,
 * and the next launch tries again instead of waiting out the interval.
 *
 * ### Announcing is a separate step
 *
 * A run that the user cannot see hands off to [NotifyNewChapters] afterwards; one they triggered, or
 * that happened while they were in the app, stays silent (see [Trigger]). The notification is driven by
 * the *pending records*, not by this run's findings, so nothing is lost if a background run is killed
 * between refreshing and announcing.
 */
sealed interface UpdateLibrary {
    /**
     * @param trigger Why the update is running — see [Trigger].
     * @return What the run did, or [Skipped] when it declined to run at all.
     */
    suspend operator fun invoke(trigger: Trigger): LibraryUpdateResult

    /**
     * What asked for the update. The two things it decides are whether the user's interval applies and
     * whether anything found is announced — and those don't split the same way, which is why this is
     * three cases and not a boolean.
     *
     * @property honorsInterval Whether the user's chosen interval can veto this run.
     * @property notifies Whether new chapters are announced. Only a run the user cannot see is worth a
     *   notification: notifying someone who is looking at the app — and at a Home list that just
     *   updated itself — is telling them what they can already see.
     */
    enum class Trigger(
        val honorsInterval: Boolean,
        val notifies: Boolean
    ) {
        /** The user asked, so run now: they are watching, and an interval is a *background* policy. */
        Manual(honorsInterval = false, notifies = false),

        /** The catch-up on app launch. Due-checked, but the user is right here — nothing to announce. */
        Foreground(honorsInterval = true, notifies = false),

        /** A platform scheduler woke the app up. The only run that speaks. */
        Background(honorsInterval = true, notifies = true)
    }

    @Inject
    @SingleIn(AppScope::class)
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val syncSourceChapters: SyncSourceChapters,
        private val notifyNewChapters: NotifyNewChapters,
        private val settingsRepository: SettingsRepository,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger
    ) : UpdateLibrary {
        /**
         * Held for the length of a run. Never *waited* on — a caller that can't take it is told the
         * library is already being refreshed and moves on.
         */
        private val running = Mutex()

        override suspend fun invoke(trigger: Trigger): LibraryUpdateResult {
            if (!running.tryLock()) return Skipped
            return try {
                if (trigger.honorsInterval && !isDue()) return Skipped
                run(trigger).also { if (trigger.notifies) notify() }
            } finally {
                running.unlock()
            }
        }

        /**
         * Announces whatever is still unannounced — which is deliberately not "what this run found":
         * the pending records are the source of truth, so a run that was killed before it could notify
         * has its findings picked up by the next one.
         *
         * A notification failing must not fail the refresh that already succeeded; the records stay
         * unmarked and the next run tries again.
         */
        private suspend fun notify() {
            try {
                notifyNewChapters()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger.warn(TAG, "Could not notify about new chapters", error)
            }
        }

        private suspend fun run(trigger: Trigger): LibraryUpdateResult {
            // A one-shot snapshot of the library: a run refreshes the series that were there when it
            // started, and a title added midway is covered by the sync its own detail screen does.
            // Read as [LibraryUpdateTarget]s rather than as manga — a run reads a schedule, a source and
            // a title, so it pays for none of the unread/downloaded counts the library grid needs.
            val library = mangaRepository.getLibraryUpdateTargets()

            // One day-window for the whole run, so a run that straddles midnight doesn't measure its
            // first half against a different day than its second.
            val window = fetchWindow(Clock.System.now())
            val due = if (trigger.honorsInterval) library.filter { it.isDue(window) } else library
            if (due.size < library.size) {
                logger.info(TAG, "Library update: ${library.size - due.size} series not due yet")
            }

            val outcomes =
                withContext(dispatchers.io) {
                    val sources = Semaphore(MAX_CONCURRENT_SOURCES)
                    coroutineScope {
                        due
                            .groupBy(LibraryUpdateTarget::source)
                            .map { (_, series) -> async { sources.withPermit { series.map { refresh(it) } } } }
                            .awaitAll()
                            .flatten()
                    }
                }

            val failed = outcomes.count { it == null }
            val newChapters = outcomes.sumOf { it ?: 0 }
            val result =
                Completed(
                    checked = outcomes.size,
                    failed = failed,
                    newChapters = newChapters,
                    updatedSeries = outcomes.count { (it ?: 0) > 0 },
                )
            logger.info(TAG, "Library update finished: $result")

            // An all-failed run is the environment, not the library — don't let it burn the interval.
            // An empty run (nothing was due) counts as a success: it did everything there was to do.
            if (failed < outcomes.size || outcomes.isEmpty()) {
                settingsRepository.setLastLibraryUpdate(Clock.System.now())
            }
            return result
        }

        /**
         * Whether a scheduled run should check this series. A series with no schedule yet has never been
         * looked at through this lens and is always checked — which is what makes stamping the first
         * schedules a silent, one-run affair rather than an upgrade step.
         *
         * "Due" is measured against the *end* of the window: a check falling anywhere today happens
         * today, rather than being pushed a day out because the run started in the morning.
         */
        private fun LibraryUpdateTarget.isDue(window: ClosedRange<Instant>): Boolean =
            nextUpdateAt == null || nextUpdateAt <= window.endInclusive

        /**
         * Refreshes one series. The sync stamps the next schedule itself, and only on success — a source
         * that was down tells us nothing about how often the series publishes, and pushing its next
         * check out would turn one bad afternoon into weeks of silence.
         *
         * @return How many chapters it gained, or `null` if it could not be refreshed at all.
         */
        private suspend fun refresh(manga: LibraryUpdateTarget): Int? =
            try {
                // `force`: this run does its own scheduling — the interval check in [isDue], the
                // per-series one in [LibraryUpdateTarget.isDue], and a manual trigger that bypasses both — so the
                // sync's own staleness gate must not second-guess it.
                syncSourceChapters(manga.id, force = true).size
            } catch (cancellation: CancellationException) {
                // The run itself is being torn down (the app closed, the OS reclaimed the task). Not a
                // failure of this series, and swallowing it would keep the loop alive past its scope.
                throw cancellation
            } catch (error: Throwable) {
                logger.warn(TAG, "Library update failed for ${manga.title.value}", error)
                null
            }

        /** Whether the user's interval says a scheduled run is due. An interval of `OFF` never is. */
        private suspend fun isDue(): Boolean {
            val period =
                settingsRepository
                    .observeLibraryUpdateInterval()
                    .first()
                    .period ?: return false
            val last = settingsRepository.getLastLibraryUpdate() ?: return true
            return Clock.System.now() - last >= period
        }

        private companion object {
            const val TAG = "UpdateLibrary"

            /**
             * How many sources are worked at once. Small on purpose: the limit that matters is what a
             * single source tolerates, and the win from a fourth or fifth in flight is small next to a
             * background run's cost in battery and data.
             */
            const val MAX_CONCURRENT_SOURCES = 3
        }
    }
}

/** The outcome of an [UpdateLibrary] run. */
sealed interface LibraryUpdateResult {
    /**
     * Nothing was checked — either a run was already in flight, or a scheduled run wasn't due yet.
     * Deliberately indistinguishable to callers: in both cases there is nothing to report and nothing
     * went wrong.
     */
    data object Skipped : LibraryUpdateResult

    /**
     * The run finished. A [failed] count short of [checked] is normal — one flaky source doesn't spoil
     * the rest.
     *
     * @property checked How many series were refreshed, successfully or not — the ones that were *due*,
     *   which on a scheduled run is a fraction of the library.
     * @property failed How many could not be refreshed.
     * @property newChapters How many chapters arrived across the whole library.
     * @property updatedSeries How many series gained at least one — what a summary counts as "titles
     *   with updates".
     */
    data class Completed(
        val checked: Int,
        val failed: Int,
        val newChapters: Int,
        val updatedSeries: Int
    ) : LibraryUpdateResult
}