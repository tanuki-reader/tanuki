package app.tanuki.features.settings.sources

import java.util.Locale

internal actual fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    // An unrecognised tag has no display name; show it as written rather than an empty chip.
    return locale.getDisplayName(Locale.getDefault()).ifBlank { tag }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
