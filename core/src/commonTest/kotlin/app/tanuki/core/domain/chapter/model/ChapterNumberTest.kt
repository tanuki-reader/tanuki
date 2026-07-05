package app.tanuki.core.domain.chapter.model

import app.tanuki.core.domain.chapter.model.ChapterNumber.Companion.NEGATIVE_ERROR
import app.tanuki.core.domain.chapter.model.ChapterNumber.Companion.NON_FINITE_ERROR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChapterNumberTest {
    // region construction & validation

    @Test
    fun `constructor keeps a valid non-negative value`() {
        assertEquals(4.5, ChapterNumber(4.5).value)
    }

    @Test
    fun `constructor allows zero`() {
        assertEquals(0.0, ChapterNumber(0.0).value)
    }

    @Test
    fun `constructor throws on a negative value`() {
        val error =
            assertFailsWith(IllegalArgumentException::class) {
                ChapterNumber(-1.0)
            }
        assertEquals(NEGATIVE_ERROR, error.message)
    }

    @Test
    fun `constructor throws on NaN`() {
        val error =
            assertFailsWith(IllegalArgumentException::class) {
                ChapterNumber(Double.NaN)
            }
        assertEquals(NON_FINITE_ERROR, error.message)
    }

    @Test
    fun `constructor throws on infinity`() {
        assertFailsWith(IllegalArgumentException::class) {
            ChapterNumber(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `Int constructor wraps a whole chapter`() {
        assertEquals(ChapterNumber(42.0), ChapterNumber(42))
    }

    @Test
    fun `Int constructor throws on a negative value`() {
        assertFailsWith(IllegalArgumentException::class) {
            ChapterNumber(-1)
        }
    }

    // endregion

    // region wholeNumber

    @Test
    fun `wholeNumber floors a sub-chapter`() {
        assertEquals(4, ChapterNumber(4.5).wholeNumber)
    }

    @Test
    fun `wholeNumber of a whole chapter is itself`() {
        assertEquals(4, ChapterNumber(4.0).wholeNumber)
    }

    @Test
    fun `wholeNumber of a sub-one chapter is zero`() {
        assertEquals(0, ChapterNumber(0.99).wholeNumber)
    }

    // endregion

    // region orNull boundary mapper

    @Test
    fun `orNull returns null for null`() {
        assertNull(ChapterNumber.orNull(null))
    }

    @Test
    fun `orNull maps the legacy -1 sentinel to null`() {
        assertNull(ChapterNumber.orNull(-1.0))
    }

    @Test
    fun `orNull returns null for NaN and infinity`() {
        assertNull(ChapterNumber.orNull(Double.NaN))
        assertNull(ChapterNumber.orNull(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `orNull wraps a valid value`() {
        assertEquals(ChapterNumber(4.5), ChapterNumber.orNull(4.5))
    }

    // endregion

    // region comparison & extensions

    @Test
    fun `compareTo orders by value`() {
        assertEquals(
            -1,
            ChapterNumber(4.0).compareTo(ChapterNumber(4.5)),
            "Chapter 4.0 should be less than Chapter 4.5"
        )
    }

    @Test
    fun `sorting orders chapters ascending`() {
        val sorted = listOf(4.5.chapter, 1.chapter, 2.chapter).sorted()
        assertEquals(listOf(1.chapter, 2.chapter, 4.5.chapter), sorted)
    }

    @Test
    fun `Int chapter extension wraps a whole chapter`() {
        assertEquals(ChapterNumber(42.0), 42.chapter)
    }

    @Test
    fun `Double chapter extension wraps a sub-chapter`() {
        assertEquals(ChapterNumber(4.5), 4.5.chapter)
    }

    // endregion
}