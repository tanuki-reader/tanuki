package app.tanuki.core.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun metadataHttpClient(): HttpClient = HttpClient(Darwin)
