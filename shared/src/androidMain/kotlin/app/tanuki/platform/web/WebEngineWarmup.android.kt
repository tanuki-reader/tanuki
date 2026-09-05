package app.tanuki.platform.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Whether this process has already started Chromium. Plain (non-atomic) because it is only ever touched
 * from the main thread — the effect below runs on the composition's dispatcher, and constructing a
 * `WebView` is a main-thread-only operation anyway.
 */
private var warmed = false

@Composable
actual fun WebEngineWarmupEffect() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (warmed) return@LaunchedEffect
        delay(WEB_ENGINE_WARM_UP_DELAY_MS)
        warmed = true
        warmUp(context)
    }
}

/**
 * Loads Chromium by building one throwaway `WebView`, then destroys it. What survives is the initialized
 * engine, which is per-process — every later `WebView`, including the one a challenge is solved in, is then
 * cheap to build.
 *
 * `CookieManager.getInstance()` is touched for the same reason: on a cold process its *first* call also
 * initializes the provider, and the session's web view calls it before loading anything.
 *
 * Nothing here may fail the app. A device with a missing, updating, or disabled WebView provider throws
 * from the constructor, and the only consequence of not warming up is the freeze this avoids — which the
 * challenge layer already survives, since it builds its own engine regardless.
 */
private fun warmUp(context: Context) {
    runCatching { WebView(context).destroy() }
    runCatching { CookieManager.getInstance() }
}
