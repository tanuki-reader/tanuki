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

/**
 * Starts the platform browser engine once, early, so the first Cloudflare challenge doesn't have to.
 *
 * Building the *first* `WebView` in an Android process loads and initializes Chromium, and that happens on
 * the main thread — hundreds of milliseconds of it, which is a visible freeze. (WebKit's first `WKWebView`
 * is cheaper but not free.) The cost is unavoidable and cannot be moved off the main thread; the only
 * choice is **when** it is paid.
 *
 * It used to be paid behind a full-screen layer that was animating in, where a hitch reads as "the
 * challenge screen is opening". Now that challenges solve silently ([SiteSessionHost]), the same cost lands
 * while the user is mid-scroll, where the very same hitch reads as the app freezing. So it is moved here:
 * paid once, shortly after launch, while the user is still looking at a library that is loading anyway.
 *
 * Deliberately **not** at the very first frame — that would just move the freeze into cold start, which is
 * the one moment the app is already doing its most expensive work. [WEB_ENGINE_WARM_UP_DELAY] lets the
 * first screen settle first.
 *
 * The warmed engine is thrown away immediately; the initialized runtime, which is process-wide and outlives
 * the instance, is the point. Keeping the instance alive would hold tens of megabytes hostage for a
 * challenge that may never come — memory this app would rather spend on decoded pages.
 *
 * Idempotent per process, and safe to call from anywhere in the composition. Call once, near the app root.
 */
@Composable
fun WebEngineWarmupEffect() {
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
