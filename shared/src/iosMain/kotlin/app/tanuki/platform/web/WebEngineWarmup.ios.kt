package app.tanuki.platform.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectZero
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/**
 * Whether this process has already touched WebKit. Main-thread only, like the Android side — a `WKWebView`
 * may only be built on the main thread.
 */
private var warmed = false

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun WebEngineWarmupEffect() {
    LaunchedEffect(Unit) {
        if (warmed) return@LaunchedEffect
        delay(WEB_ENGINE_WARM_UP_DELAY_MS)
        warmed = true
        // Building one WKWebView links and initializes the WebKit framework and its data store. Smaller
        // than Chromium's start-up — WebKit is a system framework, already resident — so this is the lesser
        // half of the fix, and it costs a discarded object to get it.
        //
        // It deliberately does not keep the instance or a shared WKProcessPool alive: WebKit's helper
        // processes are pooled and short-lived, and holding a web view to keep them warm would trade the
        // memory a page decode wants for a challenge that may never arrive.
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration())
    }
}
