package app.tanuki.core.domain.common.service

import kotlin.random.Random
import kotlin.time.Clock.System.now
import kotlin.uuid.Uuid

/**
 * Mints a time-ordered **UUID version 7** ([RFC 9562](https://www.rfc-editor.org/rfc/rfc9562)).
 *
 * The high 48 bits are a Unix-millisecond timestamp, so ids minted close in time sort close together.
 * As a `BLOB` primary key this gives near-append insert locality in SQLite's B-tree (far fewer page
 * splits than a fully random [Uuid.random] v4), while keeping UUID's decentralized, collision-free,
 * mergeable-across-devices properties.
 *
 * The stdlib has no v7 factory — [Uuid.random] is v4 — so this fills the timestamp, version (`0b0111`)
 * and variant (`0b10`) bits by hand and leaves the remaining 74 bits random.
 *
 * @param unixMillis Source timestamp; defaults to now. Injectable so tests can mint deterministic,
 *   ordered ids.
 * @param random Randomness source for the non-timestamp bits; injectable for deterministic tests.
 */
fun uuidV7(
    unixMillis: Long = now().toEpochMilliseconds(),
    random: Random = Random
): Uuid {
    val bytes = random.nextBytes(16)

    // Bytes 0..5: 48-bit big-endian millisecond timestamp.
    bytes[0] = (unixMillis ushr 40).toByte()
    bytes[1] = (unixMillis ushr 32).toByte()
    bytes[2] = (unixMillis ushr 24).toByte()
    bytes[3] = (unixMillis ushr 16).toByte()
    bytes[4] = (unixMillis ushr 8).toByte()
    bytes[5] = unixMillis.toByte()

    // Byte 6 high nibble = version 7; byte 8 high two bits = variant 0b10. Other bits stay random.
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    return Uuid.fromByteArray(bytes)
}