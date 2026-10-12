package app.tanuki.core.source.abi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAbiTest {
    @Test
    fun `the supported range is inclusive at both ends`() {
        assertTrue(SourceAbi.isSupported(SourceAbi.MIN_SUPPORTED))
        assertTrue(SourceAbi.isSupported(SourceAbi.VERSION))
    }

    @Test
    fun `a source outside the range is refused`() {
        assertFalse(SourceAbi.isSupported(SourceAbi.MIN_SUPPORTED - 1))
        assertFalse(SourceAbi.isSupported(SourceAbi.VERSION + 1))
    }
}
