package app.tanuki.core.domain.download.model

/**
 * Why a chapter download stopped — the *category*, never a message.
 *
 * The UI turns these into localized copy (a raw display `String` must never reach a domain model, and
 * the reason is stored in the database, where a string in whatever language happened to be active at
 * the time would be wrong forever after). Kept coarse on purpose: the user's only real decisions are
 * "retry now", "retry on Wi-Fi", "solve a challenge", or "free some space", and a finer taxonomy would
 * not change any of them.
 *
 * An `enum` rather than a sealed hierarchy of data objects: no arm carries a payload, and the name is
 * what gets persisted (`Converters` stores enums by `name`, which survives reordering).
 */
enum class DownloadError {
    /** The request never completed — offline, a timeout, a reset connection. Retry is worth offering. */
    Network,

    /** The source is uninstalled or its page list failed, so there is nothing to fetch. */
    SourceUnavailable,

    /**
     * A Cloudflare challenge stood in the way and there was no window to solve it in.
     *
     * The queue cannot clear this itself: solving needs an engine that is laid out and painting, which
     * needs the app on screen (see `docs/decisions.md`, 2026-08-22). The queue screen turns this into a
     * "verify to continue" affordance rather than a plain retry.
     */
    NeedsVerification,

    /** The device ran out of room. Pauses the whole queue rather than failing chapter after chapter. */
    OutOfSpace,

    /** Anything unclassified. Worth a retry, and worth a log line. */
    Unknown,
}
