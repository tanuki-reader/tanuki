package app.tanuki.core.domain.manga.model

enum class ReadingMode {
    /** Pages are laid out horizontally and advance left to right. Typical for Western comics. */
    LEFT_TO_RIGHT,

    /** Pages are laid out horizontally and advance right to left. Typical for Japanese manga. */
    RIGHT_TO_LEFT,

    /** Long-strip format with a single continuous vertical column. Typical for Korean webtoons. */
    WEBTOON,

    /** Pages are stacked vertically; the user scrolls or taps to advance one page at a time. */
    VERTICAL,

    /** Pages are stacked vertically with no breaks between them, forming a seamless scroll. */
    CONTINUOUS_VERTICAL
}