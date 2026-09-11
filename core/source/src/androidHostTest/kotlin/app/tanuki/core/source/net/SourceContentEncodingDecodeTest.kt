package app.tanuki.core.source.net

import app.tanuki.core.source.TestLogger
import app.tanuki.core.source.net.cloudflare.CfClearance
import app.tanuki.core.source.net.cloudflare.CloudflareChallenge
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cookie.SourceCookieJar
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The half of the content-encoding contract that only holds on the JVM: a gzipped body is **decoded**
 * before any caller sees it.
 *
 * It lives here rather than in `commonTest` because Ktor's `ContentEncoding` decodes per platform.
 * Its `shouldDecode` is `response.headers[Content-Encoding] != null` on the JVM but a flat `false` on
 * Darwin — *"Darwin handles content encoding automatically"* — where NSURLSession decompresses instead.
 * `commonTest` runs on both the Android host and the iOS simulator, so these assertions would pass on one
 * and fail on the other while the app is correct on both. What is uniform is the request header, and that
 * is pinned in `SourceContentEncodingTest`.
 *
 * The iOS side of this is the one thing no unit test in the repo reaches; it is checked by hand against a
 * gzip-serving source from the simulator.
 */
class SourceContentEncodingDecodeTest {

    @Test
    fun `a gzipped body reaches the caller decoded`() = runTest {
        val client = clientRespondingGzip(BODY)

        val response = client.get("https://example.org/data")

        assertContentEquals(BODY.encodeToByteArray(), response.readRawBytes())
    }

    @Test
    fun `the encoding headers do not survive the decode`() = runTest {
        // KtorSourceHost forwards the response headers to the wasm guest verbatim, so leaving
        // `Content-Encoding: gzip` on a body that is no longer gzipped would be an outright lie — and
        // `Content-Length` would describe the compressed size of something that has been expanded.
        val client = clientRespondingGzip(BODY)

        val response = client.get("https://example.org/data")

        assertNull(response.headers[HttpHeaders.ContentEncoding])
        assertNull(response.headers[HttpHeaders.ContentLength])
        assertEquals("application/json", response.headers[HttpHeaders.ContentType], "other headers are untouched")
    }

    private fun clientRespondingGzip(body: String): HttpClient {
        val compressed = gzip(body.encodeToByteArray())
        return HttpClient(MockEngine) {
            installSourcePlugins(
                solver = NoSolver,
                cookieStore = CloudflareCookieStore(),
                cookieJar = SourceCookieJar(),
                userAgent = { "Configured/9.9" },
                logger = TestLogger,
            )
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(compressed),
                        status = HttpStatusCode.OK,
                        headers = Headers.build {
                            append(HttpHeaders.ContentEncoding, "gzip")
                            append(HttpHeaders.ContentLength, compressed.size.toString())
                            append(HttpHeaders.ContentType, "application/json")
                        },
                    )
                }
            }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private companion object {
        /** Long enough that the compressed form differs from the input, so "decoded" is a real claim. */
        val BODY = """{"data":[${"""{"id":"abcd"},""".repeat(40)}{"id":"last"}]}"""
    }
}

/** The client requires a solver; no test here provokes a challenge. */
private object NoSolver : CloudflareChallengeSolver {
    override suspend fun solve(challenge: CloudflareChallenge): CfClearance? = null
}
