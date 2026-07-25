package app.tanuki.core.source.runtime

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.abi.Wire.HttpRequestWire
import app.tanuki.core.source.abi.Wire.HttpResponseWire
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * [SourceHost] backed by the app's shared Ktor [HttpClient] — the single client the Cloudflare
 * interceptor is (will be) installed on, so every source request inherits its cf_clearance cookie.
 * One instance serves all sources; it holds no per-source state.
 */
@OptIn(ExperimentalEncodingApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KtorSourceHost(
    private val client: HttpClient,
    private val logger: Logger,
) : SourceHost {

    override suspend fun httpRequest(request: HttpRequestWire): HttpResponseWire {
        val response: HttpResponse = client.request(request.url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> headers.append(name, value) }
            request.bodyBase64?.let { setBody(Base64.decode(it)) }
        }
        return HttpResponseWire(
            status = response.status.value,
            headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(", ") },
            body = response.readRawBytes(),
        )
    }

    /** Routes a source's own diagnostics through the app logger, tagged so they're attributable to a source. */
    override fun log(level: Wire.LogLevel, message: String) {
        val priority = when (level) {
            Wire.LogLevel.DEBUG -> LogPriority.DEBUG
            Wire.LogLevel.INFO -> LogPriority.INFO
            Wire.LogLevel.WARN -> LogPriority.WARN
            Wire.LogLevel.ERROR -> LogPriority.ERROR
        }
        logger.log(priority, TAG, message, throwable = null, analyticsEvent = null, addToBreadcrumbs = false)
    }

    private companion object {
        const val TAG = "Source"
    }
}