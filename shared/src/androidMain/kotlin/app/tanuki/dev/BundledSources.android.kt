package app.tanuki.dev

/** Read from the APK's Java resources, where `:shared:buildDevSources` puts them (`src/androidMain/resources`). */
internal actual suspend fun readBundledSource(sourceId: String): ByteArray =
    requireNotNull(DevSourceInstaller::class.java.classLoader?.getResourceAsStream("sources/$sourceId.wasm")) {
        "No bundled source '$sourceId.wasm' in the app's resources."
    }.use { it.readBytes() }
