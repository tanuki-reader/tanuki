package app.tanuki

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.tanuki.di.AndroidAppGraph
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.android.ActivityKey

@ActivityKey(MainActivity::class)
@ContributesIntoMap(AppScope::class, binding = binding<Activity>())
class MainActivity(
    private val appGraph: AndroidAppGraph,
) : ComponentActivity() {
    /**
     * Asks for `POST_NOTIFICATIONS`. Registered as a field so it is in place before `onCreate`
     * returns, which the Activity Result API requires.
     *
     * The answer is deliberately ignored: nothing is gated on it. A user who declines simply never
     * sees update notifications, and Home's "New Chapters" list — which needs no permission — carries
     * the same information.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        // Here rather than in TanukiApplication: the jobs are for a launch with UI, and WorkManager can
        // start the process in the background (see AppStartup). Idempotent across Activity recreation.
        appGraph.appStartup.start()

        setContent {
            App(appGraph)
        }
    }

    /**
     * Asked here because this is the only place that *can* ask: the permission needs an Activity, and
     * the code that posts an update notification runs in a background worker with no UI.
     *
     * Not deferred until there is something to announce, for the same reason — by then the app is
     * asleep. Android stops showing the dialog once the user has declined twice, so a check on every
     * launch cannot turn into nagging.
     */
    private fun requestNotificationPermissionIfNeeded() {
        val granted =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
