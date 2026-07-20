package app.tanuki.core.domain.common.model

/**
 * A three-way toggle for a boolean property of a filterable item: ignore it, require it, or exclude it.
 *
 * Library/browse filters are tri-state rather than on/off, so the user can express "must be downloaded",
 * "must NOT be downloaded", and "don't care" with a single control. [matches] applies the toggle to a
 * concrete boolean, collapsing [DISABLED] to "always passes".
 */
enum class TriState {
    /** The filter is off — every item passes regardless of the property. */
    DISABLED,

    /** Only items whose property is `true` pass. */
    REQUIRED,

    /** Only items whose property is `false` pass. */
    EXCLUDED;

    /**
     * @param value The item's actual value for the property this toggle gates.
     * @return `true` if the item passes this toggle.
     */
    fun matches(value: Boolean): Boolean =
        when (this) {
            DISABLED -> true
            REQUIRED -> value
            EXCLUDED -> !value
        }
}