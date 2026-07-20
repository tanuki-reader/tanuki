package app.tanuki.core.domain.common.model

import kotlin.jvm.JvmInline
import io.ktor.http.Url as KtorUrl

/**
 * Domain wrapper for a validated absolute URL.
 *
 * Enforces that [value] parses as a well-formed URL with a scheme and host.
 * Ktor is used only to validate/parse at construction — it never crosses the
 * shared/UI boundary; callers see this domain type, not `io.ktor.http.Url`.
 *
 * @property value The underlying URL string.
 */
@JvmInline
value class Url(
    val value: String
) {
    init {
        val parsed = KtorUrl(value)
        require(parsed.protocol.name.isNotBlank() && parsed.host.isNotBlank()) {
            "$INVALID_URL_ERROR: $value"
        }
    }

    companion object {
        internal const val INVALID_URL_ERROR = "Not a valid absolute URL"
    }
}

/** Lenient factory: blank/`null`/unparseable input becomes `null`. Mirrors [toTextOrNull]. */
fun String?.toUrlOrNull(): Url? =
    this?.takeIf(String::isNotBlank)?.let { runCatching { Url(it) }.getOrNull() }