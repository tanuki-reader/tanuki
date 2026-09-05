package app.tanuki.platform.web

import androidx.compose.runtime.Composable

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
expect fun WebEngineWarmupEffect()

/**
 * How long after the UI starts the engine is warmed.
 *
 * Long enough that it lands after the first screen has drawn and its initial data has loaded, short enough
 * that a challenge on the user's first browse still benefits. Not tuned to a measurement — the only
 * property that matters is "not during cold start, and well before a user could reach a protected source".
 */
internal const val WEB_ENGINE_WARM_UP_DELAY_MS = 2_500L
