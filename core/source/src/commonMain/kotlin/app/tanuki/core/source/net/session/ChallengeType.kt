package app.tanuki.core.source.net.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What kind of Cloudflare interstitial the browser is sitting on, read from `window._cf_chl_opt.cType`.
 *
 * This exists to answer one question: **is a person needed?** A challenge that clears by itself can be
 * solved out of sight — the engine still has to be laid out and painting, but nothing has to be shown to
 * the user (see [silentBudget] and `SiteSessionHost`). One that ends in a checkbox cannot, and putting it
 * on screen a moment sooner is the whole difference between a session that resolves and one that times out.
 *
 * The values are Cloudflare's own, not ours; anything unrecognised (including a page that hasn't defined
 * `_cf_chl_opt` yet) is [Unknown] rather than a guess.
 */
enum class ChallengeType {
    /** Clears on its own once the engine has run the challenge's JavaScript. Never needs a tap. */
    NonInteractive,

    /** Renders a Turnstile widget. Usually self-clearing, but **may escalate** to a checkbox. */
    Managed,

    /** Always ends in something only a person can do. */
    Interactive,

    /** Not read yet, or a `cType` Cloudflare has since added. */
    Unknown;

    companion object {
        /**
         * Maps a raw `_cf_chl_opt.cType` to a type. `jschal` is the legacy spelling of the
         * non-interactive JS challenge and is treated the same.
         */
        fun fromCType(raw: String?): ChallengeType =
            when (raw?.trim()?.lowercase()) {
                "non-interactive", "jschal" -> NonInteractive
                "managed" -> Managed
                "interactive" -> Interactive
                else -> Unknown
            }
    }
}

/**
 * How long a challenge of this kind is given to solve itself, measured from when the session opened,
 * before the browser is put on screen.
 *
 * Nothing is *cancelled* when the budget runs out — the same engine keeps solving the same challenge, it
 * simply becomes visible so the user can finish it. So the cost of a budget that is too long is a user
 * staring at an app that seems stuck, and the cost of one that is too short is a full-screen browser that
 * flashes up for a challenge which was about to clear anyway.
 *
 * The floor is what makes the numbers non-arbitrary: **Cloudflare's own JS challenge has a deliberate ~5s
 * delay**, so any budget under about six seconds reveals challenges that were always going to succeed.
 * From there: [Interactive] reveals at once (waiting cannot help — only a person can finish it),
 * [NonInteractive] gets the longest wait (it either clears or fails, and a person cannot help either way),
 * and [Managed] sits between the two because it may or may not escalate. [Unknown] is treated as
 * [Managed]: it is the value seen before the page has defined `_cf_chl_opt`, which is *every* session for
 * its first poll, so a zero budget here would make every challenge visible and defeat the feature.
 */
val ChallengeType.silentBudget: Duration
    get() =
        when (this) {
            ChallengeType.Interactive -> Duration.ZERO
            ChallengeType.NonInteractive -> 12.seconds
            ChallengeType.Managed, ChallengeType.Unknown -> 8.seconds
        }

/**
 * The point at which a session that has resolved neither way is shown regardless of what the page claims
 * to be doing.
 *
 * [silentBudget] only fires while the browser is *still on an interstitial*, which is the right rule for
 * deciding "has this escalated to something needing a person" — but it leaves a gap. A challenge can leave
 * the interstitial without ever issuing a clearance: a redirect to an error page, a WAF block behind the
 * challenge, a navigation that lands somewhere unexpected. Nothing in the solve path has a timeout, so
 * before this ceiling existed such a session would sit there silently forever, with the request suspended
 * and the user given nothing to dismiss — invisible, and therefore worse than the challenge it replaced.
 *
 * Generous on purpose: it is a backstop for a session that has gone wrong, not a second budget. Every
 * [silentBudget] must be comfortably inside it, or it would pre-empt the rule it is backing up.
 */
val SILENT_SESSION_CEILING: Duration = 25.seconds
