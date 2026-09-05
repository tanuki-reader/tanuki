package app.tanuki.core.data.store

import android.content.Context

/**
 * Android [DownloadDirectoryFactory]: `<filesDir>/downloads`, the app's private internal storage — the
 * same place installed `.wasm` bundles go, and deliberately **not** `cacheDir`, which the OS is free to
 * empty. Android's auto-backup ignores a directory of this size in practice and the app opts out of
 * backing it up in its manifest, so there is nothing extra to do here.
 */
actual class DownloadDirectoryFactory(
    private val context: Context,
) {
    actual fun resolve(): String =
        context.applicationContext
            .filesDir
            .resolve(DOWNLOADS_DIRECTORY_NAME)
            .apply { mkdirs() }
            .absolutePath
}
