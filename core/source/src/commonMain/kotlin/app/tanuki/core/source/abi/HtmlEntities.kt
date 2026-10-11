package app.tanuki.core.source.abi

/**
 * Decodes the HTML character references sources leave in their text (`Murim&#039;s`, `&amp;`,
 * `&quot;`): numeric (`&#39;`, `&#x27;`) and the common named ones. Unknown or malformed references are
 * left as they are rather than guessed at. Applied once, host-side, where source text is mapped — so no
 * screen and no source has to remember it.
 */
internal fun String.decodeHtmlEntities(): String {
    if ('&' !in this) return this
    return ENTITY.replace(this) { match ->
        val body = match.groupValues[1]
        val decoded =
            when {
                body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.toCodePointString()
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.toCodePointString()
                else -> NAMED[body]
            }
        decoded ?: match.value
    }
}

private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

private val NAMED =
    mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×", "eacute" to "é",
    )

private fun Int.toCodePointString(): String? =
    when {
        this <= 0 || this > 0x10FFFF || this in 0xD800..0xDFFF -> null
        this <= 0xFFFF -> this.toChar().toString()
        else -> {
            val offset = this - 0x10000
            charArrayOf(((offset shr 10) + 0xD800).toChar(), ((offset and 0x3FF) + 0xDC00).toChar()).concatToString()
        }
    }
