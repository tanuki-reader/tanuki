package app.tanuki

import app.tanuki.core.domain.common.service.CrashReporter
import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.download.usecase.VerifyDownloads
import app.tanuki.core.domain.history.usecase.PruneHistory
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
import app.tanuki.core.domain.source.usecase.RefreshSourceRepos
import app.tanuki.core.domain.update.usecase.KeepLibraryUpdateScheduled
import app.tanuki.core.domain.update.usecase.UpdateLibrary
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The work the app does once it comes up with a UI. Each job runs on the application scope, so leaving a
 * screen doesn't cancel it.
 *
 * Compose-free, and deliberately not part of any composition. These jobs used to be `LaunchedEffect`s
 * in [App]. That tied them to whichever UI happened to be the root, so they would stop when the SwiftUI
 * root replaces it, and re-ran on every Android Activity recreation (rotation, a dark-mode switch).
 * Each platform's UI entry point now calls [start]: `MainActivity.onCreate` on Android, the scene's
 * `.task` in `iOSApp.swift` on iOS.
 *
 * **The trigger is "the UI came up", not "the process started".** A process WorkManager or
 * `BGTaskScheduler` launched in the background must not run a *foreground* library update or pick the
 * download queue up behind the background task's back. That's why this isn't called from
 * `Application.onCreate` or the Swift `App.init`, both of which also run for background launches.
 */
@OptIn(ExperimentalAtomicApi::class)
@Inject
@SingleIn(AppScope::class)
class AppStartup(
    private val applicationScope: CoroutineScope,
    private val refreshSourceRepos: RefreshSourceRepos,
    private val updateLibrary: UpdateLibrary,
    private val verifyDownloads: VerifyDownloads,
    private val downloadScheduler: DownloadScheduler,
    private val pruneHistory: PruneHistory,
    private val keepLibraryUpdateScheduled: KeepLibraryUpdateScheduled,
    private val observeCrashReportingEnabled: ObserveCrashReportingEnabled,
    private val crashReporter: CrashReporter
) {
    private val started = AtomicBoolean(false)

    /** Starts the launch jobs. Idempotent: only the first call in a process does anything. */
    fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return

        // Dev-only: fill an empty library with sample manga so covers render before real sources exist.
        // No-op once the library has any entry. Needs a `LibrarySeeder` constructor parameter if revived.
        // applicationScope.launch { librarySeeder.seedIfEmpty() }

        // Re-fetch every source repo's signed index, so the catalog offers what the repos publish now.
        // A failure (offline, a repo down) is not worth surfacing at launch: each repo keeps its last
        // verified index, and the repos screen refreshes on demand.
        applicationScope.launch { runCatching { refreshSourceRepos() } }

        // Check the library for new chapters if the user's interval says one is due.
        //
        // This foreground catch-up is not a stand-in for the platform schedulers — it is the half that
        // works even when they don't fire. iOS grants background refresh opportunistically and may skip a
        // rarely-opened app for days; without a check on launch, those users would never see an update.
        // It stays silent: the user is looking at the Home list it feeds.
        applicationScope.launch { updateLibrary(UpdateLibrary.Trigger.Foreground) }

        // Reconcile the downloads, then pick the queue back up — in that order, and therefore in one job:
        // two jobs would give no ordering guarantee, and the order is the whole point. The sweep clears
        // records whose files are gone and files no record claims (storage cleared from the OS, a chapter a
        // sync pruned, a part an abandoned run left), so what the engine then works is a queue that matches
        // the device. It is safe beside a live run — see VerifyDownloads for why — and a no-op on a healthy
        // one.
        //
        // Picking the queue up is the second half, and nothing else would do it: enqueueing starts a run, but
        // a queue the user left behind — the app was killed, the chapter was still fetching — has no enqueue
        // coming to wake it, and would sit there looking active while nothing fetched it. The engine requeues
        // the rows an interrupted run left mid-flight and carries on; an empty queue makes this a no-op.
        applicationScope.launch {
            verifyDownloads()
            downloadScheduler.ensureRunning()
        }

        // Keep the history table bounded. Nothing else in the app ever removes a history row on age, and
        // both readers that take no period walk every row of it on each chapter the reader opens.
        //
        // Its own job, and ungated: a DELETE matching nothing fires no Room trigger, so on every launch
        // until an install is two calendar years old this costs one indexed scan and invalidates no query.
        // A series' most recent read is always kept, whatever its age — see PruneHistory for why that is
        // load-bearing rather than polite.
        applicationScope.launch { pruneHistory() }

        // Hand the platform scheduler the user's interval, and re-hand it whenever they change it. Runs for
        // the app's lifetime — it's a subscription, not a one-shot.
        applicationScope.launch { keepLibraryUpdateScheduled() }

        // Apply the user's crash-reporting consent, and keep applying it. This is the *only* thing that ever
        // starts the reporter: the SDK installs process-wide crash handlers, so it must not run before the
        // stored preference has been read, and it must stop the moment consent is withdrawn. Collecting the
        // flow rather than reading it once is what makes the second half true — unticking the switch shuts
        // the reporter down on the same emission, with no relaunch.
        applicationScope.launch {
            observeCrashReportingEnabled().collect(crashReporter::setEnabled)
        }
    }
}
