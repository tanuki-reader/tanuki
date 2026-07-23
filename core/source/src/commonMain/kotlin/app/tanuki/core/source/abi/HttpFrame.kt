package app.tanuki.core.source.abi

import kotlinx.serialization.json.Json

/**
 * The binary framing for an [SourceAbi.Imports.HTTP_REQUEST] reply — the one payload on the ABI that
 * is **not** JSON.
 *
 * ```
 * [0, 4)                     status          i32, little-endian
 * [4, 8)                     headersLength   i32, little-endian (bytes of the JSON below)
 * [8, 8+headersLength)       headers         JSON object, {"name": "value", …}
 * [8+headersLength, end)     body            raw response bytes, verbatim
 * ```
 *
 * ### Why not JSON like everything else
 *
 * Because this is the one payload that carries **bulk bytes**, and a guest pays for every one of them
 * twice under JSON. v3 sent the body base64-encoded inside a JSON object, which meant the guest had to
 * run `serde_json` across the whole (33% inflated) envelope just to reach a string, base64-decode that
 * string back to the original bytes, and only then parse what it actually wanted. Measured on Android
 * with a 279 KB MangaDex response, those two steps cost **1.93 s of a 4.65 s call** — 0.43 s to parse
 * the envelope and 1.50 s to decode the base64 — in a WASM interpreter with no JIT to hide it.
 *
 * Framing removes both: the header block stays JSON (a handful of bytes, and headers are genuinely
 * string→string), while the body is copied through untouched and handed to the guest as a slice. A
 * source reaches its bytes without decoding anything, and binary payloads (images, archives) no longer
 * need an encoding that only existed to survive JSON.
 *
 * Every field is little-endian because that is WASM's memory order, so a guest can read the two
 * integers with a pointer cast instead of arithmetic.
 */
object HttpFrame {
    /** Bytes preceding the header JSON: the two little-endian `i32`s. */
    const val PREFIX_BYTES: Int = 8

    /** Encodes [response] into the frame the guest's `fetch` decodes. */
    fun encode(response: Wire.HttpResponseWire): ByteArray {
        val headers = Json.encodeToString(response.headers).encodeToByteArray()
        val frame = ByteArray(PREFIX_BYTES + headers.size + response.body.size)

        frame.writeIntLe(0, response.status)
        frame.writeIntLe(4, headers.size)
        headers.copyInto(frame, PREFIX_BYTES)
        response.body.copyInto(frame, PREFIX_BYTES + headers.size)

        return frame
    }

    /** Writes [value] at [offset] as a little-endian `i32`. */
    private fun ByteArray.writeIntLe(
        offset: Int,
        value: Int
    ) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
