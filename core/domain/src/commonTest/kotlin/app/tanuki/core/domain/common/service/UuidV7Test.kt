package app.tanuki.core.domain.common.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class UuidV7Test {
    /** Reads the 48-bit big-endian millisecond timestamp back out of the high bytes. */
    private fun Uuid.timestampMillis(): Long {
        val bytes = toByteArray()
        var ts = 0L
        for (i in 0 until 6) {
            ts = (ts shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return ts
    }

    /** RFC 9562 version nibble, stored in the high nibble of byte 6. */
    private fun Uuid.version(): Int = (toByteArray()[6].toInt() shr 4) and 0x0F

    /** RFC 9562 variant, stored in the high two bits of byte 8 (expected `0b10`). */
    private fun Uuid.variant(): Int = (toByteArray()[8].toInt() shr 6) and 0x03

    @Test
    fun `version nibble is 7`() {
        assertEquals(7, uuidV7(unixMillis = 0x0123_4567_89ABL).version())
    }

    @Test
    fun `variant bits are 0b10`() {
        assertEquals(0b10, uuidV7(unixMillis = 0x0123_4567_89ABL).variant())
    }

    @Test
    fun `high 48 bits encode the timestamp big-endian`() {
        val millis = 0x0123_4567_89ABL
        assertEquals(millis, uuidV7(unixMillis = millis).timestampMillis())
    }

    @Test
    fun `current-time timestamp round-trips`() {
        val now = 1_752_000_000_000L // arbitrary realistic epoch-millis
        assertEquals(now, uuidV7(unixMillis = now).timestampMillis())
    }

    @Test
    fun `only the low 48 bits of the timestamp are kept`() {
        // Bits above bit 47 must not leak into the id, so these two collapse to the same timestamp.
        val low = 0x0000_FFFF_FFFF_FFFFL
        val withHighBits = low or (0x7FL shl 48)
        assertEquals(
            uuidV7(unixMillis = low, random = Random(1)),
            uuidV7(unixMillis = withHighBits, random = Random(1))
        )
    }

    @Test
    fun `zero timestamp encodes as all-zero high bytes`() {
        assertEquals(0L, uuidV7(unixMillis = 0L).timestampMillis())
    }

    @Test
    fun `same timestamp and seed are deterministic`() {
        val millis = 1_700_000_000_000L
        assertEquals(
            uuidV7(unixMillis = millis, random = Random(42)),
            uuidV7(unixMillis = millis, random = Random(42))
        )
    }

    @Test
    fun `same timestamp with different randomness differs`() {
        val millis = 1_700_000_000_000L
        assertNotEquals(
            uuidV7(unixMillis = millis, random = Random(1)),
            uuidV7(unixMillis = millis, random = Random(2))
        )
    }

    @Test
    fun `ids minted at increasing timestamps sort in order`() {
        val ids = (0L until 100L).map { uuidV7(unixMillis = 1_700_000_000_000L + it, random = Random(0)) }
        val sorted = ids.sortedBy { it.toString() }
        assertEquals(ids, sorted)
    }

    @Test
    fun `random bits keep ids unique within the same millisecond`() {
        val millis = 1_700_000_000_000L
        val ids = (0 until 10_000).map { uuidV7(unixMillis = millis) }.toSet()
        assertEquals(10_000, ids.size)
    }

    @Test
    fun `canonical string has version and variant markers`() {
        // Layout: xxxxxxxx-xxxx-7xxx-Vxxx-xxxxxxxxxxxx — position 14 is '7', position 19 is 8..b.
        val text = uuidV7(unixMillis = 1_700_000_000_000L).toString()
        assertEquals('7', text[14])
        assertTrue(text[19] in "89ab", "variant nibble was '${text[19]}'")
    }

    @Test
    fun `default arguments produce a well-formed v7 id`() {
        val id = uuidV7()
        assertEquals(7, id.version())
        assertEquals(0b10, id.variant())
    }
}