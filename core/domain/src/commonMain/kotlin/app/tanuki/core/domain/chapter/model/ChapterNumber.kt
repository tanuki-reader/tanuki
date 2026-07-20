package app.tanuki.core.domain.chapter.model

import kotlin.jvm.JvmInline
import kotlin.math.floor

/**
 * Domain wrapper for a chapter's number, e.g. `4`, `4.5`, `7.99` (an "extra").
 *
 * Enforces two invariants: the wrapped [value] must be **finite** (no `NaN`/infinity) and
 * **non-negative**. Invalid input throws [IllegalArgumentException].
 *
 * Subchapter precision is intentionally preserved — chapter `4.5` is a real, distinct chapter,
 * so the underlying type is [Double], not an integer. (The recognition logic also encodes
 * `extra`/`omake`/`special` as fractional offsets such as `.99`/`.98`/`.97`.)
 *
 * Nullability is expressed at the use site, not inside the wrapper: a chapter whose number could
 * not be recognized is a `ChapterNumber?` holding `null`. A non-null [ChapterNumber] therefore
 * always holds a real, valid number, so `null` is the single, unambiguous "unrecognizable".
 *
 * @property value The underlying finite, non-negative chapter number.
 */
@JvmInline
value class ChapterNumber(
    val value: Double
) : Comparable<ChapterNumber> {
    init {
        require(value.isFinite()) { NON_FINITE_ERROR }
        require(value >= 0.0) { NEGATIVE_ERROR }
    }

    /**
     * Convenience for whole chapters: `ChapterNumber(42)` instead of `ChapterNumber(42.0)`.
     * A negative [whole] is rejected by the primary constructor's `init` check.
     */
    constructor(whole: Int) : this(whole.toDouble())

    /**
     * The whole-chapter index, e.g. `4.5` → `4`. Subchapter precision is discarded — use this
     * when comparing chapters by their integer position (gap/missing-chapter math).
     */
    val wholeNumber: Int get() = floor(value).toInt()

    override fun compareTo(other: ChapterNumber): Int = value.compareTo(other.value)

    companion object {
        /** Error message used when the input is `NaN` or infinite. */
        internal const val NON_FINITE_ERROR = "Chapter number must be a finite value"

        /** Error message used when the input is negative. */
        internal const val NEGATIVE_ERROR = "Chapter number must be non-negative"

        /**
         * Lenient factory for source/persistence boundaries: a `null`, non-finite, or negative
         * input becomes `null` (an unrecognizable chapter), any valid input becomes a
         * [ChapterNumber]. Use this to map an incoming raw [Double] — including legacy sentinels
         * such as `-1.0` — to the domain's `null`-means-unknown convention rather than throwing.
         */
        fun orNull(value: Double?): ChapterNumber? = value?.takeIf(::isValid)?.let(::ChapterNumber)

        /** The `init` invariant as a predicate: finite and non-negative. */
        private fun isValid(value: Double): Boolean = value.isFinite() && value >= 0.0
    }
}

/** Terse construction for whole chapters: `42.chapter`. */
val Int.chapter: ChapterNumber get() = ChapterNumber(this)

/** Terse construction for subchapters: `4.5.chapter`. */
val Double.chapter: ChapterNumber get() = ChapterNumber(this)