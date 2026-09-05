package app.tanuki.platform.web

import app.tanuki.core.source.net.session.ChallengeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [parseChallengeProbe] — how the two browser engines' answers to [CHALLENGE_PROBE_JS] are read.
 *
 * The shapes differ per platform and neither is negotiable: Android's `evaluateJavascript` hands back a
 * **JSON value**, so a string arrives quoted and a null arrives as the literal `null`, while WebKit's
 * `evaluateJavaScript` hands back the string itself. Both go through this one parser so the decision about
 * what the browser is looking at stays in common code.
 */
class ChallengeProbeTest {

    @Test
    fun `reads a challenge and its kind from the android shape`() {
        val probe = parseChallengeProbe("\"1|managed\"")
        assertTrue(probe.onChallengePage)
        assertEquals(ChallengeType.Managed, probe.type)
    }

    @Test
    fun `reads a challenge and its kind from the webkit shape`() {
        val probe = parseChallengeProbe("1|interactive")
        assertTrue(probe.onChallengePage)
        assertEquals(ChallengeType.Interactive, probe.type)
    }

    @Test
    fun `reads a page that is not a challenge`() {
        val probe = parseChallengeProbe("\"0|\"")
        assertTrue(!probe.onChallengePage)
        assertEquals(ChallengeType.Unknown, probe.type)
    }

    /**
     * A challenge page that hasn't filled in `cType` yet is still a challenge — the flag and the kind are
     * independent readings, and losing the flag would resume the caller on a half-solved challenge.
     */
    @Test
    fun `a challenge with no kind yet is still a challenge`() {
        val probe = parseChallengeProbe("1|")
        assertTrue(probe.onChallengePage)
        assertEquals(ChallengeType.Unknown, probe.type)
    }

    /**
     * The safe direction. A page mid-navigation answers nothing; reading that as "challenge over" would
     * hand the caller a clearance that isn't live, whereas reading it as "still going" costs one more poll.
     */
    @Test
    fun `an unreadable answer counts as still challenged`() {
        listOf(null, "", "   ", "null").forEach { raw ->
            val probe = parseChallengeProbe(raw)
            assertTrue(probe.onChallengePage, "'$raw' should read as still challenged")
            assertEquals(ChallengeType.Unknown, probe.type)
        }
    }

    /** Nothing downstream should ever see a type for a page that isn't a challenge. */
    @Test
    fun `a non-challenge page reports no kind even if one comes back`() {
        assertEquals(ChallengeType.Unknown, parseChallengeProbe("0|managed").type)
    }
}
