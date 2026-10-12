package app.tanuki.core.source.repo

import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.OfflineException
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.RemoteSource
import app.tanuki.core.domain.source.model.RepoPreview
import app.tanuki.core.domain.source.model.SourceCompatibility
import app.tanuki.core.domain.source.model.SourceRepo
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.model.SourceRepoException
import app.tanuki.core.domain.source.repository.SourceRepoRepository
import app.tanuki.core.domain.source.repository.StoredSourceRepo
import app.tanuki.core.source.abi.SourceAbi
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import kotlin.time.Clock

/**
 * The [SourceCatalog]: user-added static source repos, each a signed index (`repo.json`, `index.json`,
 * `index.json.sig`, versioned `.wasm` files; see `docs/design-remote-sources.md` §3).
 *
 * Trust is anchored at add time. Adding a repo pins the ed25519 key its `repo.json` publishes (the user
 * confirms the fingerprint), and from then on an index is stored only if its signature verifies
 * against **that** key; a bundle is installed only if its size and sha256 match the verified index.
 * Nothing unverified ever reaches the store, and a repo whose key changes simply stops refreshing.
 *
 * Repos are plain static hosts, so the shared client's Cloudflare handling never engages; it is used
 * for its timeouts and retries.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RemoteSourceCatalog(
    private val repos: SourceRepoRepository,
    private val bundleStore: SourceBundleStore,
    private val http: HttpClient,
    private val connection: ConnectionObserver
) : SourceCatalog {
    /**
     * Repos whose last refresh was refused, and why. In memory on purpose: the launch refresh re-derives
     * it, and the stored row keeps the last verified index serving in the meantime.
     */
    private val problems = MutableStateFlow<Map<String, SourceRepoError>>(emptyMap())

    override fun observeRepos(): Flow<List<SourceRepo>> =
        combine(repos.observeRepos(), problems) { stored, problems ->
            stored.map { SourceRepo(it.baseUrl, it.name, fingerprint(it.publicKey), problems[it.baseUrl]) }
        }

    override fun observeAvailable(): Flow<List<RemoteSource>> =
        repos.observeRepos().map { stored -> stored.flatMap(::offered) }

    override suspend fun previewRepo(url: String): RepoPreview {
        val baseUrl = normalize(url)
        val repo = decode<RepoJson>(fetch(baseUrl + REPO_JSON))
        if (repo.formatVersion > FORMAT_VERSION) throw SourceRepoException(SourceRepoError.UnsupportedFormat)
        val key = repo.publicKey?.decodeBase64()
        if (key == null || key.size != ED25519_KEY_SIZE) throw SourceRepoException(SourceRepoError.Unsigned)
        // A repo the app knows must present the key the app ships with, so even the first add can't be
        // fooled by a compromised host. Any other repo is trusted on first use, behind the fingerprint.
        PINNED_REPO_KEYS[baseUrl]?.let { pinned ->
            if (pinned != repo.publicKey) throw SourceRepoException(SourceRepoError.KeyChanged)
        }
        if (repos.getRepos().any { it.baseUrl == baseUrl }) throw SourceRepoException(SourceRepoError.AlreadyAdded)
        // Verified now rather than on add, so the confirmation can say how many sources the user is trusting.
        val index = fetchVerifiedIndex(baseUrl, repo.publicKey)
        val sourceCount = json.decodeFromString<IndexJson>(index).sources.size
        return RepoPreview(baseUrl, repo.name, fingerprint(repo.publicKey), sourceCount, repo.publicKey, index)
    }

    override suspend fun addRepo(preview: RepoPreview) {
        if (repos.getRepos().any { it.baseUrl == preview.baseUrl }) throw SourceRepoException(SourceRepoError.AlreadyAdded)
        repos.saveRepo(StoredSourceRepo(preview.baseUrl, preview.name, preview.publicKey, preview.indexJson, now()))
    }

    override suspend fun removeRepo(baseUrl: String) {
        repos.removeRepo(baseUrl)
        problems.update { it - baseUrl }
    }

    override suspend fun refresh() {
        var firstFailure: Throwable? = null
        for (repo in repos.getRepos()) {
            try {
                repos.saveRepo(repo.copy(indexJson = fetchVerifiedIndex(repo.baseUrl, repo.publicKey), fetchedAt = now()))
                problems.update { it - repo.baseUrl }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Keep going: one broken repo must not stop the others refreshing. It keeps its last good index.
                val failure = if (error.isBadSignature()) SourceRepoException(trustProblem(repo), error) else error
                if (failure is SourceRepoException) problems.update { it + (repo.baseUrl to failure.error) }
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    override suspend fun install(
        sourceId: String,
        onProgress: (InstallProgress) -> Unit
    ) {
        val (repo, entry) =
            repos.getRepos().firstNotNullOfOrNull { repo ->
                index(repo)?.sources?.firstOrNull { it.id == sourceId && compatibility(it.abi) == SourceCompatibility.Compatible }
                    ?.let { repo to it }
            } ?: throw IllegalArgumentException("No added repo offers a compatible '$sourceId'.")

        onProgress(InstallProgress.Downloading(null))
        val bytes =
            fetch(repo.baseUrl + entry.wasmPath) { received, total ->
                onProgress(InstallProgress.Downloading(total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }))
            }
        onProgress(InstallProgress.Verifying)
        if (bytes.size.toLong() != entry.size || bytes.toByteString().sha256().hex() != entry.sha256) {
            throw SourceRepoException(SourceRepoError.Mismatch)
        }
        bundleStore.writeBundle(sourceId, bytes)
    }

    /** A refused signature means one of two things, and the user must be told which: a new key, or tampering. */
    private suspend fun trustProblem(repo: StoredSourceRepo): SourceRepoError =
        runCatching { decode<RepoJson>(fetch(repo.baseUrl + REPO_JSON)).publicKey }
            .getOrNull()
            ?.takeIf { it != repo.publicKey }
            ?.let { SourceRepoError.KeyChanged }
            ?: SourceRepoError.BadSignature

    private fun Throwable.isBadSignature() = this is SourceRepoException && error == SourceRepoError.BadSignature

    private fun offered(repo: StoredSourceRepo): List<RemoteSource> =
        index(repo)?.sources.orEmpty().map { entry ->
            RemoteSource(
                id = entry.id,
                name = entry.name,
                lang = entry.lang,
                version = entry.version,
                nsfw = entry.nsfw,
                iconUrl = entry.iconUrl,
                repoUrl = repo.baseUrl,
                repoName = repo.name,
                compatibility = compatibility(entry.abi)
            )
        }

    /** The repo's stored index. It verified when stored; `null` only if it can't be read back. */
    private fun index(repo: StoredSourceRepo): IndexJson? = runCatching { json.decodeFromString<IndexJson>(repo.indexJson) }.getOrNull()

    /** Fetches `index.json` and returns its text only if its signature verifies against [publicKey]. */
    private suspend fun fetchVerifiedIndex(
        baseUrl: String,
        publicKey: String
    ): String {
        val index = fetch(baseUrl + INDEX_JSON)
        val signature = fetch(baseUrl + INDEX_SIG).decodeToString().trim().decodeBase64()
        if (signature == null || !verifies(publicKey, index, signature.toByteArray())) {
            throw SourceRepoException(SourceRepoError.BadSignature)
        }
        if (decode<IndexJson>(index).formatVersion > FORMAT_VERSION) throw SourceRepoException(SourceRepoError.UnsupportedFormat)
        return index.decodeToString()
    }

    private suspend fun verifies(
        publicKey: String,
        data: ByteArray,
        signature: ByteArray
    ): Boolean =
        runCatching {
            CryptographyProvider.Default
                .get(EdDSA)
                .publicKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, publicKey.decodeBase64()!!.toByteArray())
                .signatureVerifier()
                .tryVerifySignature(data, signature)
        }.getOrDefault(false)

    private suspend fun fetch(
        url: String,
        onDownload: ((received: Long, total: Long?) -> Unit)? = null
    ): ByteArray {
        if (!connection.isOnline) throw SourceRepoException(SourceRepoError.Offline, OfflineException("Cannot fetch $url while offline"))
        val response: HttpResponse =
            try {
                http.get(url) { if (onDownload != null) onDownload { received, total -> onDownload(received, total) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw SourceRepoException(SourceRepoError.Unreachable, error)
            }
        if (!response.status.isSuccess()) throw SourceRepoException(SourceRepoError.Unreachable)
        return response.readRawBytes()
    }

    private inline fun <reified T> decode(bytes: ByteArray): T =
        try {
            json.decodeFromString<T>(bytes.decodeToString())
        } catch (error: IllegalArgumentException) {
            throw SourceRepoException(SourceRepoError.Unreachable, error)
        }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    @Serializable
    private data class RepoJson(
        val formatVersion: Int,
        val name: String,
        val publicKey: String? = null
    )

    @Serializable
    private data class IndexJson(
        val formatVersion: Int,
        val sources: List<Entry> = emptyList()
    )

    @Serializable
    private data class Entry(
        val id: String,
        val name: String,
        val lang: String,
        val version: Int,
        val abi: Int,
        val nsfw: Boolean = false,
        val iconUrl: String? = null,
        val wasmPath: String,
        val sha256: String,
        val size: Long
    )

    internal companion object {
        const val FORMAT_VERSION = 1
        const val REPO_JSON = "repo.json"
        const val INDEX_JSON = "index.json"
        const val INDEX_SIG = "index.json.sig"
        private const val ED25519_KEY_SIZE = 32

        /** The first-party repo's signing key (`tanuki-reader/tanuki-sources`, base64 ed25519). */
        private const val TANUKI_SOURCES_KEY = "T+bZfLVGYzHZ0g7lDWQMUdgXkKD8eDLtmH4jqEZSgPI="

        /**
         * Keys the app ships for repos it knows, by normalized base URL: both addresses the first-party
         * repo is served from. Rotating that key now takes an app release (see the sources README); the
         * app still ships no repo *URL* — the user adds it.
         */
        internal val PINNED_REPO_KEYS: Map<String, String> =
            mapOf(
                "https://tanuki-reader.github.io/tanuki-sources/" to TANUKI_SOURCES_KEY,
                "https://cdn.jsdelivr.net/gh/tanuki-reader/tanuki-sources@repo/" to TANUKI_SOURCES_KEY
            )

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `https://` + host + path, ending in `/`. Accepts a bare host and a pasted `…/repo.json`;
         * plain `http` is refused (an index fetched in the clear could be swapped before the key is pinned).
         */
        fun normalize(url: String): String {
            val trimmed = url.trim().removeSuffix(REPO_JSON)
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            if (!withScheme.startsWith("https://") || withScheme.length <= "https://".length || ' ' in withScheme) {
                throw SourceRepoException(SourceRepoError.InvalidUrl)
            }
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }

        /**
         * First 128 bits of sha256(key) as two lines of four groups of four hex digits, the design system's
         * fingerprint format: `4F2A 9C1E 77B0 D3A5` / `0E6B 91C4 2D8F 6A13`.
         */
        fun fingerprint(publicKey: String): String =
            (publicKey.decodeBase64() ?: return "")
                .sha256()
                .hex()
                .uppercase()
                .take(32)
                .chunked(4)
                .chunked(4) { it.joinToString(" ") }
                .joinToString("\n")

        fun compatibility(abi: Int): SourceCompatibility =
            when {
                abi > SourceAbi.VERSION -> SourceCompatibility.NeedsAppUpdate
                abi < SourceAbi.MIN_SUPPORTED -> SourceCompatibility.Outdated
                else -> SourceCompatibility.Compatible
            }
    }
}
