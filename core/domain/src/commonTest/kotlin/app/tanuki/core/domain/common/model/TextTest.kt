package app.tanuki.core.domain.common.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TextTest {
    @Test
    fun `text should be not blank`() {
        assertFailsWith<IllegalArgumentException> { Text("  ") }
    }

    @Test
    fun `error message should be BLANK_NAME_ERROR`() {
        assertFailsWith<IllegalArgumentException> { Text("  ") }
            .message
            .equals(Text.BLANK_NAME_ERROR)
    }

    @Test
    fun `blank calls should return null`() {
        assertNull("  ".toTextOrNull())
    }
}