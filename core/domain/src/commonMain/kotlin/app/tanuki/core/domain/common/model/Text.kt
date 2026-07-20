package app.tanuki.core.domain.common.model

import kotlin.jvm.JvmInline

/**
 * Domain wrapper for non-blank display names.
 *
 * Enforces a single invariant: the wrapped [value] must not be blank or whitespace-only.
 * Invalid input throws [IllegalArgumentException].
 *
 * Use this type wherever a domain entity carries a user-visible name (manga title,
 * chapter name, category label, etc.) so the blank-check is enforced at construction
 * time rather than scattered across callers.
 *
 * Nullability is expressed at the use site, not inside the wrapper: a required name is a
 * [Text], an optional one is a `Text?`. A `Text` therefore always holds a real, non-blank
 * string, so `null` (the absent `Text?`) is the single, unambiguous "no value".
 *
 * @property value The underlying non-blank string value.
 */
@JvmInline
value class Text(
    val value: String
) {
    init {
        require(value.isNotBlank()) { BLANK_NAME_ERROR }
    }

    companion object {
        /** Error message used when the input string is blank or whitespace-only. */
        internal const val BLANK_NAME_ERROR = "Name cannot be blank or whitespace only"
    }
}

/**
 * Lenient factory for optional fields: blank or `null` input becomes `null`
 * (an absent value), any other input becomes a validated [Text]. Use this for
 * source/network data where a missing name arrives as `""` or `null` rather
 * than throwing — reserve the constructor for fields that must be present.
 */
fun String?.toTextOrNull(): Text? =
    this
        ?.takeIf(String::isNotBlank)
        ?.let(::Text)