package app.tanuki.core.source.repo

import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.SourceCompatibility
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.model.SourceRepoException
import app.tanuki.core.domain.source.repository.SourceRepoRepository
import app.tanuki.core.domain.source.repository.StoredSourceRepo
import app.tanuki.core.source.FakeConnectionObserver
import app.tanuki.core.source.abi.SourceAbi
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://repo.test/tanuki/"
private val WASM = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)

/**
 * Tests the trust chain of [RemoteSourceCatalog] against a fake static host serving really signed
 * files: what gets pinned, what gets stored, and above all what must never reach the bundle store.
 */
class RemoteSourceCatalogTest {

    @Test
    fun `adding a repo pins its key and lists what it offers`() = runTest {
        val world = World()
        world.publish()

        val preview = world.catalog.previewRepo("repo.test/tanuki")
        world.catalog.addRepo(preview)

        assertEquals(BASE, preview.baseUrl)
        assertEquals(1, preview.sourceCount)
        assertEquals(RemoteSourceCatalog.fingerprint(world.publicKey), preview.fingerprint)
        assertEquals(world.publicKey, world.repos.stored.value.single().publicKey)
        val offered = world.catalog.observeAvailable().first().single()
        assertEquals("mangadex", offered.id)
        assertEquals("Tanuki Sources", offered.repoName)
        assertEquals(SourceCompatibility.Compatible, offered.compatibility)
    }

    @Test
    fun `a repo with no signing key is refused`() = runTest {
        val world = World()
        world.publish(publishKey = false)

        val error = assertFailsWith<SourceRepoException> { world.catalog.previewRepo(BASE) }

        assertEquals(SourceRepoError.Unsigned, error.error)
    }

    @Test
    fun `plain http is refused before anything is fetched`() = runTest {
        val error = assertFailsWith<SourceRepoException> { World().catalog.previewRepo("http://repo.test/tanuki/") }

        assertEquals(SourceRepoError.InvalidUrl, error.error)
    }

    @Test
    fun `an index signed by another key is refused before anything is stored`() = runTest {
        val world = World()
        world.publish(signer = World().signer)

        val error = assertFailsWith<SourceRepoException> { world.catalog.previewRepo(BASE) }

        assertEquals(SourceRepoError.BadSignature, error.error)
        assertTrue(world.repos.stored.value.isEmpty())
    }

    @Test
    fun `a repo that rotates its key fails closed and says the key changed`() = runTest {
        val world = World()
        world.publish(version = 9)
        world.catalog.addRepo(world.catalog.previewRepo(BASE))
        val other = World()
        world.publish(version = 10, signer = other.signer, publicKey = other.publicKey)

        val error = assertFailsWith<SourceRepoException> { world.catalog.refresh() }

        assertEquals(SourceRepoError.KeyChanged, error.error)
        assertEquals(SourceRepoError.KeyChanged, world.catalog.observeRepos().first().single().problem)
        assertEquals(9, world.catalog.observeAvailable().first().single().version, "the last verified index keeps serving")
    }

    @Test
    fun `fingerprints are two lines of four groups`() {
        val lines = RemoteSourceCatalog.fingerprint(World().publicKey).lines()

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.matches(Regex("[0-9A-F]{4}( [0-9A-F]{4}){3}")) })
    }

    @Test
    fun `a refresh that fails verification keeps the last good index`() = runTest {
        val world = World()
        world.publish(version = 9)
        world.catalog.addRepo(world.catalog.previewRepo(BASE))
        world.publish(version = 10, signer = World().signer) // same published key, foreign signature: tampered

        val error = assertFailsWith<SourceRepoException> { world.catalog.refresh() }

        assertEquals(SourceRepoError.BadSignature, error.error)
        assertEquals(9, world.catalog.observeAvailable().first().single().version)
    }

    @Test
    fun `a refresh with a good signature replaces the index`() = runTest {
        val world = World()
        world.publish(version = 9)
        world.catalog.addRepo(world.catalog.previewRepo(BASE))
        world.publish(version = 10)

        world.catalog.refresh()

        assertEquals(10, world.catalog.observeAvailable().first().single().version)
        assertNull(world.catalog.observeRepos().first().single().problem)
    }

    @Test
    fun `install writes the bundle when it matches the signed index`() = runTest {
        val world = World()
        world.publish()
        world.catalog.addRepo(world.catalog.previewRepo(BASE))

        val progress = mutableListOf<InstallProgress>()

        world.catalog.install("mangadex") { progress += it }

        assertContentEquals(WASM, world.store.bundles["mangadex"])
        assertTrue(progress.first() is InstallProgress.Downloading)
        assertEquals(InstallProgress.Verifying, progress.last())
    }

    @Test
    fun `install writes nothing when the bytes don't match the signed index`() = runTest {
        val world = World()
        world.publish()
        world.catalog.addRepo(world.catalog.previewRepo(BASE))
        world.files["${BASE}wasm/mangadex-v9.wasm"] = WASM.copyOf().also { it[7] = 1 }

        val error = assertFailsWith<SourceRepoException> { world.catalog.install("mangadex") }

        assertEquals(SourceRepoError.Mismatch, error.error)
        assertNull(world.store.bundles["mangadex"])
    }

    @Test
    fun `install is refused offline but the catalog still lists`() = runTest {
        val world = World()
        world.publish()
        world.catalog.addRepo(world.catalog.previewRepo(BASE))
        world.connection.set(ConnectionStatus.OFFLINE)

        val error = assertFailsWith<SourceRepoException> { world.catalog.install("mangadex") }

        assertEquals(SourceRepoError.Offline, error.error)
        assertEquals(1, world.catalog.observeAvailable().first().size)
        assertNull(world.store.bundles["mangadex"])
    }

    @Test
    fun `a source built for a newer ABI is listed but not installable`() = runTest {
        val world = World()
        world.publish(abi = SourceAbi.VERSION + 1)
        world.catalog.addRepo(world.catalog.previewRepo(BASE))

        assertEquals(SourceCompatibility.NeedsAppUpdate, world.catalog.observeAvailable().first().single().compatibility)
        assertFailsWith<IllegalArgumentException> { world.catalog.install("mangadex") }
    }

    @Test
    fun `urls normalize to an https base ending in a slash`() {
        assertEquals(BASE, RemoteSourceCatalog.normalize(" repo.test/tanuki "))
        assertEquals(BASE, RemoteSourceCatalog.normalize("https://repo.test/tanuki/repo.json"))
        assertEquals(BASE, RemoteSourceCatalog.normalize(BASE))
    }
}

/** A static host of files, a signing key, and a catalog over in-memory stores. */
private class World {
    private val keys = CryptographyProvider.Default.get(EdDSA).keyPairGenerator(EdDSA.Curve.Ed25519).generateKeyBlocking()
    val signer = keys.privateKey
    val publicKey: String = keys.publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW).toByteString().base64()

    val files = mutableMapOf<String, ByteArray>()
    val repos = FakeRepoRepository()
    val store = FakeBundleStore()
    val connection = FakeConnectionObserver()
    private val http =
        HttpClient(MockEngine { request ->
            files[request.url.toString()]?.let { respond(it) } ?: respond(ByteArray(0), HttpStatusCode.NotFound)
        })
    val catalog = RemoteSourceCatalog(repos, store, http, connection)

    /** Serves a repo offering one `mangadex` bundle, with its index signed by [signer]. */
    fun publish(
        version: Int = 9,
        abi: Int = SourceAbi.VERSION,
        publishKey: Boolean = true,
        signer: EdDSA.PrivateKey = this.signer,
        publicKey: String = this.publicKey
    ) {
        val key = if (publishKey) "\"$publicKey\"" else "null"
        files["${BASE}repo.json"] = """{"formatVersion":1,"name":"Tanuki Sources","publicKey":$key}""".encodeToByteArray()
        val index =
            """{"formatVersion":1,"generatedAt":"2026-10-12T00:00:00Z","sources":[{"id":"mangadex","name":"MangaDex",""" +
                """"lang":"en","version":$version,"abi":$abi,"nsfw":false,"listings":[],"wasmPath":"wasm/mangadex-v9.wasm",""" +
                """"sha256":"${WASM.toByteString().sha256().hex()}","size":${WASM.size}}]}"""
        val bytes = index.encodeToByteArray()
        files["${BASE}index.json"] = bytes
        files["${BASE}index.json.sig"] = signer.signatureGenerator().generateSignatureBlocking(bytes).toByteString().base64().encodeToByteArray()
        files["${BASE}wasm/mangadex-v9.wasm"] = WASM
    }
}

private class FakeRepoRepository : SourceRepoRepository {
    val stored = MutableStateFlow<List<StoredSourceRepo>>(emptyList())

    override fun observeRepos(): Flow<List<StoredSourceRepo>> = stored

    override suspend fun getRepos(): List<StoredSourceRepo> = stored.value

    override suspend fun saveRepo(repo: StoredSourceRepo) {
        stored.value = stored.value.filterNot { it.baseUrl == repo.baseUrl } + repo
    }

    override suspend fun removeRepo(baseUrl: String) {
        stored.value = stored.value.filterNot { it.baseUrl == baseUrl }
    }
}

private class FakeBundleStore : SourceBundleStore {
    val bundles = mutableMapOf<String, ByteArray>()
    private val revision = MutableStateFlow(0)

    override suspend fun installedIds() = bundles.keys.toList()

    override fun observeInstalledIds(): Flow<List<String>> = revision.map { installedIds() }

    override suspend fun readBundle(sourceId: String) = bundles[sourceId]

    override suspend fun writeBundle(
        sourceId: String,
        bytes: ByteArray
    ) {
        bundles[sourceId] = bytes
        revision.value++
    }

    override suspend fun removeBundle(sourceId: String) {
        bundles.remove(sourceId)
        revision.value++
    }

    override suspend fun isInstalled(sourceId: String) = sourceId in bundles
}
