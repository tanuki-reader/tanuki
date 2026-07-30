package app.tanuki.core.source.net.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [SiteSessionCoordinator] — the handoff between a suspended network coroutine and the UI. The
 * behaviours that matter are the ones a caller depends on to not hang: nothing attached fails immediately,
 * a dismissal is distinguishable from a success, and the UI going away releases whoever was waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class SiteSessionCoordinatorTest {

    @Test
    fun `fails fast when no ui is attached to ask`() = runTest {
        val coordinator = SiteSessionCoordinator()

        // A library update running in the background has no screen; it must get an answer, not park.
        assertNull(coordinator.request(challengeFor("https://protected.example/x")))
    }

    @Test
    fun `exposes the pending session and resumes the caller when it finishes`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()

        val caller = async { coordinator.request(challengeFor("https://protected.example/x")) }
        advanceUntilIdle()

        val pending = assertNotNull(coordinator.pending.value, "the UI should see a session to render")
        assertEquals("https://protected.example/x", pending.request.url)

        pending.finish(SiteSessionResult(cookies = mapOf("cf_clearance" to "TOKEN"), userAgent = "RealBrowser/1.0"))

        val result = assertNotNull(caller.await())
        assertEquals(mapOf("cf_clearance" to "TOKEN"), result.cookies)
        assertEquals("RealBrowser/1.0", result.userAgent)
        assertNull(coordinator.pending.value, "the layer must come down once resolved")
    }

    @Test
    fun `a dismissed session resumes the caller with null`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()

        val caller = async { coordinator.request(challengeFor("https://protected.example/x")) }
        advanceUntilIdle()
        coordinator.pending.value?.cancel()

        assertNull(caller.await(), "a dismissal is not a solve")
    }

    @Test
    fun `resolving twice keeps the first outcome`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()

        val caller = async { coordinator.request(challengeFor("https://protected.example/x")) }
        advanceUntilIdle()

        // The completion predicate firing at the same moment the user hits close must not resume twice.
        val pending = assertNotNull(coordinator.pending.value)
        pending.finish(SiteSessionResult(mapOf("cf_clearance" to "TOKEN"), userAgent = null))
        pending.cancel()

        assertNotNull(caller.await(), "the first resolution wins")
    }

    @Test
    fun `detaching the ui releases a session in flight`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()

        val caller = async { coordinator.request(challengeFor("https://protected.example/x")) }
        advanceUntilIdle()
        // The app backgrounds, or the composition goes away, while a challenge is on screen.
        coordinator.detach()

        assertNull(caller.await(), "nobody left to answer — the caller must not hang")
    }

    @Test
    fun `queues a second caller until the first is resolved`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()

        val first = async { coordinator.request(challengeFor("https://one.example/x")) }
        advanceUntilIdle()
        val second = async { coordinator.request(challengeFor("https://two.example/x")) }
        advanceUntilIdle()

        assertEquals(
            "https://one.example/x",
            coordinator.pending.value?.request?.url,
            "two challenged sites must not fight over the screen",
        )

        coordinator.pending.value?.cancel()
        advanceUntilIdle()
        assertEquals("https://two.example/x", coordinator.pending.value?.request?.url, "the queue then advances")

        coordinator.pending.value?.cancel()
        assertTrue(first.await() == null && second.await() == null)
    }

    /**
     * The bug this pins cost two rounds of misdiagnosis. Android's `CookieManager` is process-global and
     * persists across launches, so a `cf_clearance` from an earlier run is already in the store when the
     * session opens. A presence check passed 5ms after the web view started — before the page had loaded —
     * and handed the caller a dead cookie, which then got re-challenged and read as "the challenge failed".
     */
    @Test
    fun `a leftover cookie from a previous session does not finish a challenge`() {
        val completion = SiteSessionCompletion.WhenCookieSet("cf_clearance")
        val stale = mapOf("cf_clearance" to "OLD")

        assertFalse(
            completion.isSatisfiedBy(baseline = stale, current = solved(stale)),
            "the cookie the request was just challenged with is known-bad; seeing it again means nothing",
        )
        assertTrue(
            completion.isSatisfiedBy(baseline = stale, current = solved(mapOf("cf_clearance" to "FRESH"))),
            "a new value is the only thing that can mean the browser solved it",
        )
    }

    @Test
    fun `a cookie set for the first time finishes a challenge`() {
        val completion = SiteSessionCompletion.WhenCookieSet("cf_clearance")

        assertTrue(completion.isSatisfiedBy(emptyMap(), solved(mapOf("cf_clearance" to "FRESH"))))
        assertFalse(completion.isSatisfiedBy(emptyMap(), solved(mapOf("other" to "x"))), "wrong cookie")
    }

    /**
     * Cloudflare sets `cf_clearance` *during* the challenge, before the round trip that commits it, and
     * flags the flow in progress with `cf_chl_*`. Finishing on the cookie alone replayed a clearance that
     * wasn't live yet — 48ms after it appeared, with `cf_chl_rc_ni` still present — and got re-challenged,
     * which cost the user a second challenge before it stuck.
     */
    @Test
    fun `a challenge still on screen does not finish the session`() {
        val completion = SiteSessionCompletion.WhenCookieSet("cf_clearance")
        val cookies = mapOf("cf_clearance" to "FRESH", "cf_chl_rc_ni" to "1")

        assertFalse(
            completion.isSatisfiedBy(
                baseline = emptyMap(),
                current = SiteSessionResult(cookies, userAgent = null, onChallengePage = true),
            ),
            "the clearance is not live server-side while the interstitial is still up",
        )
        assertTrue(
            completion.isSatisfiedBy(emptyMap(), solved(cookies)),
            "the lingering cf_chl_* cookie must not block it once the browser has left the interstitial",
        )
    }

    @Test
    fun `a login is never finished by cookies alone`() {
        assertFalse(
            SiteSessionCompletion.WhenUserConfirms.isSatisfiedBy(emptyMap(), solved(mapOf("session" to "live"))),
            "no cookie reliably means signed in — only the user's confirm action ends a login",
        )
    }

    /** A snapshot of a browser that has finished the challenge and left the interstitial. */
    private fun solved(cookies: Map<String, String>) =
        SiteSessionResult(cookies = cookies, userAgent = null, onChallengePage = false)

    private fun challengeFor(url: String) =
        SiteSessionRequest(
            url = url,
            host = "protected.example",
            completion = SiteSessionCompletion.WhenCookieSet("cf_clearance"),
            reason = SiteSessionReason.CLOUDFLARE_CHALLENGE,
        )
}