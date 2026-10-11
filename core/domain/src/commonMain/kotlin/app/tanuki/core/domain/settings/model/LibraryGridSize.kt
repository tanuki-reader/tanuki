package app.tanuki.core.domain.settings.model

/**
 * How big library covers are drawn. The grid stays adaptive: each size is a minimum cover width each
 * platform maps to, so the column count still follows the screen (more on a wide phone or a tablet).
 * [Medium] is the original size.
 */
enum class LibraryGridSize { Small, Medium, Large }
