package app.tanuki.core.domain.source.model

/**
 * A source repo the user added: a static host serving a signed index of installable sources (see
 * `docs/design-remote-sources.md`). Identified by its [baseUrl], which always ends in `/`.
 *
 * @property name the repo's own name, from its `repo.json`.
 * @property fingerprint human-checkable digest of the pinned signing key: 128 bits as two lines of four
 *   groups of four hex digits, shown when adding the repo so the user can compare it with the one the
 *   repo publishes.
 * @property problem why the last refresh refused this repo's index ([SourceRepoError.KeyChanged] or
 *   [SourceRepoError.BadSignature]), or `null`. Its last verified index keeps serving meanwhile.
 */
data class SourceRepo(
    val baseUrl: String,
    val name: String,
    val fingerprint: String,
    val problem: SourceRepoError? = null
)

/**
 * What adding [baseUrl] would pin, fetched and already verified but not yet stored: confirm it with the
 * user first.
 *
 * @property sourceCount how many sources its verified index offers.
 * @property indexJson that verified index, stored as-is when the user confirms.
 */
data class RepoPreview(
    val baseUrl: String,
    val name: String,
    val fingerprint: String,
    val sourceCount: Int,
    val publicKey: String,
    val indexJson: String
)

/** Where an install is: downloading ([fraction] `0..1`, or `null` until the size is known), then checking the bytes. */
sealed interface InstallProgress {
    data class Downloading(val fraction: Float?) : InstallProgress

    data object Verifying : InstallProgress
}

/**
 * A source a repo offers, as its verified index describes it. Exists before (and without) the bundle
 * being on disk, which is what lets the catalog list it.
 */
data class RemoteSource(
    val id: String,
    val name: String,
    val lang: String,
    val version: Int,
    val nsfw: Boolean,
    val iconUrl: String?,
    val repoUrl: String,
    val repoName: String,
    val compatibility: SourceCompatibility
)

/** Whether this app can run a [RemoteSource], from its ABI version against the host's supported range. */
enum class SourceCompatibility {
    Compatible,

    /** Built for a newer ABI than this app speaks. */
    NeedsAppUpdate,

    /** Built for an ABI this app no longer runs. */
    Outdated
}

/** Why a repo couldn't be added or refreshed. Meaning only; each platform words it. */
enum class SourceRepoError {
    /** Not an `https://` URL. */
    InvalidUrl,

    /** The host didn't answer, or didn't serve a repo there. */
    Unreachable,

    /** The repo publishes no signing key, so nothing it serves can be trusted. */
    Unsigned,

    /** The index's signature doesn't match the pinned key, which the repo still publishes: tampered with. */
    BadSignature,

    /** The repo now publishes a different signing key than the one pinned. Fails closed. */
    KeyChanged,

    /** A downloaded bundle's size or sha256 doesn't match the repo's signed index. */
    Mismatch,

    /** The repo uses an index format newer than this app understands. */
    UnsupportedFormat,

    /** The device is offline. */
    Offline,

    /** The repo is already added. */
    AlreadyAdded
}

class SourceRepoException(
    val error: SourceRepoError,
    cause: Throwable? = null
) : Exception("Source repo: $error", cause)
