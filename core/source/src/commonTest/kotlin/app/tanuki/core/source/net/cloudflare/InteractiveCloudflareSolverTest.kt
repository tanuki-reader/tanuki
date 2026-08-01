package app.tanuki.core.source.net.cloudflare

import app.tanuki.core.source.TestLogger
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests that [InteractiveCloudflareSolver] asks for the right session and only trusts a real clearance. */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class InteractiveCloudflareSolverTest {

    @Test
    fun `asks for a session that ends when the clearance cookie appears`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()
        val solver = InteractiveCloudflareSolver(coordinator, TestLogger)

        val solving = async { solver.solve(CloudflareChallenge("https://protected.example/api/x?y=1")) }
        advanceUntilIdle()

        val request = assertNotNull(coordinator.pending.value).request
        // The host root, not the challenged URL: a clearance is domain-scoped, so the root earns the same
        // one while being ordinary HTML rather than the API's raw JSON — sensible to show, and a document
        // the interstitial probe can actually run against.
        assertEquals("https://protected.example/", request.url, "the session loads the host root")
        assertEquals("protected.example", request.host)
        assertEquals(SiteSessionReason.CLOUDFLARE_CHALLENGE, request.reason)
        assertEquals(
            SiteSessionCompletion.WhenCookieSet("cf_clearance"),
            request.completion,
            "a challenge that clears on its own must dismiss without the user acting",
        )

        coordinator.pending.value?.finish(
            SiteSessionResult(cookies = mapOf("cf_clearance" to "TOKEN", "session" to "s"), userAgent = "Real/1.0"),
        )

        val clearance = assertNotNull(solving.await())
        assertEquals("TOKEN", clearance.cookies["cf_clearance"])
        assertEquals("s", clearance.cookies["session"], "the site's own cookies ride along with the clearance")
        assertEquals("Real/1.0", clearance.userAgent, "the clearance is bound to the UA that earned it")
    }

    @Test
    fun `treats a session finished without the clearance cookie as unsolved`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()
        val solver = InteractiveCloudflareSolver(coordinator, TestLogger)

        val solving = async { solver.solve(CloudflareChallenge("https://protected.example/x")) }
        advanceUntilIdle()
        // The user closed the browser on a challenge that never cleared.
        coordinator.pending.value?.finish(SiteSessionResult(cookies = mapOf("session" to "s"), userAgent = "Real/1.0"))

        assertNull(solving.await(), "no cf_clearance means no clearance to store or replay")
    }

    @Test
    fun `returns null when there is no ui to show the challenge in`() = runTest {
        val solver = InteractiveCloudflareSolver(SiteSessionCoordinator(), TestLogger)

        assertNull(solver.solve(CloudflareChallenge("https://protected.example/x")))
    }

    /**
     * A hostless URL must be rejected *before* a session is raised. Ktor's parser is lenient enough to
     * accept one, and a browser pointed at nothing would sit there until the user dismissed it — turning a
     * malformed URL into a hang instead of an error.
     */
    @Test
    fun `returns null for a url with no host and raises no session`() = runTest {
        val coordinator = SiteSessionCoordinator()
        coordinator.attach()

        assertNull(InteractiveCloudflareSolver(coordinator, TestLogger).solve(CloudflareChallenge("/relative/path")))
        assertNull(coordinator.pending.value, "nothing should have been shown to the user")
    }
}