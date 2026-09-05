package app.tanuki.core.source.net.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tests [ChallengeType] and its [silentBudget] — the rule that decides how long a Cloudflare challenge may
 * keep solving underneath the app before the browser is put on screen.
 *
 * The two failure modes this pins are opposite and both user-visible: a budget that reveals too eagerly
 * flashes a full-screen browser for a challenge that was about to clear, and one that never reveals leaves
 * a user staring at an app that looks stuck while a checkbox waits out of sight.
 */
class ChallengeTypeTest {

    @Test
    fun `reads the cType values cloudflare sends`() {
        assertEquals(ChallengeType.Managed, ChallengeType.fromCType("managed"))
        assertEquals(ChallengeType.Interactive, ChallengeType.fromCType("interactive"))
        assertEquals(ChallengeType.NonInteractive, ChallengeType.fromCType("non-interactive"))
    }

    /** The legacy spelling of the JS challenge. Same thing, same handling. */
    @Test
    fun `jschal is a non-interactive challenge`() {
        assertEquals(ChallengeType.NonInteractive, ChallengeType.fromCType("jschal"))
    }

    @Test
    fun `is not thrown by casing or padding`() {
        assertEquals(ChallengeType.Managed, ChallengeType.fromCType(" Managed "))
    }

    /**
     * An absent or unrecognised `cType` must not read as a *kind* — a `cType` Cloudflare adds later would
     * otherwise inherit whichever budget happened to be the fallback.
     */
    @Test
    fun `an unknown or missing cType is Unknown`() {
        assertEquals(ChallengeType.Unknown, ChallengeType.fromCType(null))
        assertEquals(ChallengeType.Unknown, ChallengeType.fromCType(""))
        assertEquals(ChallengeType.Unknown, ChallengeType.fromCType("some-future-kind"))
    }

    /** Waiting cannot help a challenge only a person can finish, so it is shown at once. */
    @Test
    fun `an interactive challenge is revealed immediately`() {
        assertEquals(Duration.ZERO, ChallengeType.Interactive.silentBudget)
    }

    /**
     * The floor that makes the numbers non-arbitrary: Cloudflare's own JS challenge has a deliberate ~5s
     * delay, so any budget under about six seconds reveals challenges that were always going to succeed.
     */
    @Test
    fun `every self-solving kind is given longer than cloudflare's own delay`() {
        val selfSolving = listOf(ChallengeType.NonInteractive, ChallengeType.Managed, ChallengeType.Unknown)
        selfSolving.forEach { type ->
            assertTrue(type.silentBudget >= 6.seconds, "$type reveals before its challenge could have cleared")
        }
    }

    /**
     * `Unknown` is what *every* session reports on its first poll, before the page has defined
     * `_cf_chl_opt`. A zero budget here would reveal every challenge instantly and undo the whole feature.
     */
    @Test
    fun `an unread challenge waits as long as a managed one`() {
        assertEquals(ChallengeType.Managed.silentBudget, ChallengeType.Unknown.silentBudget)
    }

    /** A challenge that can never need a tap can afford to wait longer than one that might escalate. */
    @Test
    fun `a non-interactive challenge waits longest`() {
        assertTrue(ChallengeType.NonInteractive.silentBudget > ChallengeType.Managed.silentBudget)
    }

    /**
     * The ceiling is a backstop for a session that has gone wrong, not a second budget: if it could fire
     * first it would pre-empt the per-kind rule and reveal challenges that were still solving normally.
     */
    @Test
    fun `the ceiling sits beyond every budget`() {
        ChallengeType.entries.forEach { type ->
            assertTrue(
                SILENT_SESSION_CEILING > type.silentBudget,
                "the ceiling would pre-empt $type's budget",
            )
        }
    }
}
