package app.tanuki.platform.web

/**
 * How long after the UI starts the browser engine is warmed (Android's `WebEngineWarmupEffect`, iOS's
 * `warmUpWebEngine`), so the first Cloudflare challenge doesn't pay the engine's start-up mid-scroll.
 *
 * Long enough that it lands after the first screen has drawn and its initial data has loaded, short enough
 * that a challenge on the user's first browse still benefits. Not tuned to a measurement — the only
 * property that matters is "not during cold start, and well before a user could reach a protected source".
 */
internal const val WEB_ENGINE_WARM_UP_DELAY_MS = 2_500L
