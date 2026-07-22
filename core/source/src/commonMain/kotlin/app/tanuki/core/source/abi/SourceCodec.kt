package app.tanuki.core.source.abi

import kotlinx.serialization.json.Json

/**
 * The JSON codec for ABI wire payloads ([Wire]) — the single place encoding/serialization config is
 * pinned, so host and (Kotlin) guests share identical settings. Bytes are UTF-8 JSON.
 *
 * Config choices, both for forward-compatibility (see [Wire] compatibility rules):
 *  - [Json.ignoreUnknownKeys] `= true` — a reader tolerates fields added by a newer minor revision.
 *  - `explicitNulls = false` — absent optionals are omitted rather than written as `null`, keeping
 *    payloads small and stable across versions.
 */
object SourceCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Encodes [value] to UTF-8 JSON bytes for passing across the ABI. */
    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(value).encodeToByteArray()

    /** Decodes UTF-8 JSON [bytes] received across the ABI into [T]. */
    inline fun <reified T> decode(bytes: ByteArray): T =
        json.decodeFromString(bytes.decodeToString())

    /**
     * Decodes an [Wire.Envelope] of [T] and unwraps it: returns [Wire.Envelope.ok] or throws
     * [SourceAbiException] if the guest reported an [Wire.Envelope.error] (or sent neither).
     */
    inline fun <reified T> decodeEnvelope(bytes: ByteArray): T {
        val envelope = decode<Wire.Envelope<T>>(bytes)
        envelope.error?.let { throw SourceAbiException(it.message, it.code) }
        return envelope.ok ?: throw SourceAbiException("Source returned an empty result envelope", code = null)
    }
}

/** Raised when a source reports an in-band ABI error, or returns a malformed/empty envelope. */
class SourceAbiException(
    message: String,
    /** Optional machine-readable code from [Wire.AbiError.code]. */
    val code: String?,
) : Exception(message)