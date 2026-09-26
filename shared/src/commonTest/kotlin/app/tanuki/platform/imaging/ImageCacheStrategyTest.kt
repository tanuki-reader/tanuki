package app.tanuki.platform.imaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [isCacheableImageResponse] — the one decision [SuccessOnlyCacheStrategy] makes, and the only
 * thing it changes relative to Coil's default.
 *
 * The strategy object itself isn't driven here: its methods take a Coil `Options`, which needs a
 * `PlatformContext`, and on the Android host that is a real `Context`. So the decision is kept in this pure
 * predicate and the object only routes to it.
 */
class ImageCacheStrategyTest {

    @Test
    fun `a successful response is cached`() {
        listOf(200, 203, 204, 206, 299).forEach { assertTrue(isCacheableImageResponse(it), "$it") }
    }

    @Test
    fun `a not-modified response is cached - it only refreshes an entry that already succeeded`() {
        assertTrue(isCacheableImageResponse(304))
    }

    /**
     * Coil's `DefaultCacheStrategy` writes exactly these to disk, and its default read then serves them
     * forever — the fetcher throws on a cached failure without asking the network again. Listed one by one
     * so that a Coil upgrade widening its set is something this test is read against, not something missed.
     */
    @Test
    fun `none of the failures coil caches by default is cached`() {
        listOf(300, 301, 404, 405, 410, 414, 501).forEach { assertFalse(isCacheableImageResponse(it), "$it") }
    }

    @Test
    fun `transient and challenge failures are not cached`() {
        listOf(403, 408, 429, 500, 502, 503).forEach { assertFalse(isCacheableImageResponse(it), "$it") }
    }
}
