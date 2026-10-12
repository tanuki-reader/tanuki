package app.tanuki.features.settings.sources

/** [tag] (BCP-47, from a source's metadata) as a language name in the user's language: `pt-BR` → "Português (Brasil)". */
internal expect fun languageName(tag: String): String
