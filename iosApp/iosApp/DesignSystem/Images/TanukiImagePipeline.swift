import Nuke

extension ImagePipeline {
    /// The app's image pipeline. Kotlin fetches and disk-caches (`KotlinDataLoader`); Nuke decodes off
    /// the main thread, coalesces duplicate requests and keeps the in-memory cache. No Nuke disk cache:
    /// Coil's `cover_cache` is the only one, so a cover cached by a Compose screen is a hit here too.
    static let tanuki = ImagePipeline(configuration: Configuration(dataLoader: KotlinDataLoader()))
}
