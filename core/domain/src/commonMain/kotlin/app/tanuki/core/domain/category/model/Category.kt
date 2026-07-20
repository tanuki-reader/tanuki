package app.tanuki.core.domain.category.model

import app.tanuki.core.domain.common.model.Text
import kotlin.uuid.Uuid

/**
 * A user-defined library category (shelf) that manga can be organized into.
 *
 * Categories are sorted by [order] for display.
 *
 * @property id Stable identity; defaults to the built-in "Uncategorized" id when omitted.
 * @property name Display name; a validated non-blank [Text].
 * @property associatedMangas Manga that are in this category.
 */
data class Category(
    val id: Uuid,
    val name: Text,
    val associatedMangas: Set<Uuid> = emptySet()
)