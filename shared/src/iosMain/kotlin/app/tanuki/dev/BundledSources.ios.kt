package app.tanuki.dev

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readBundledSource(sourceId: String): ByteArray {
    val path =
        NSBundle.mainBundle.pathForResource(sourceId, ofType = "wasm", inDirectory = "sources")
            ?: error("No bundled source '$sourceId.wasm' in the app bundle.")
    val data = NSData.dataWithContentsOfFile(path) ?: error("Could not read $path.")
    return ByteArray(data.length.toInt()).apply {
        if (isNotEmpty()) usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    }
}
