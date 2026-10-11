package app.tanuki.core.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun metadataHttpClient(): HttpClient = HttpClient(OkHttp)
