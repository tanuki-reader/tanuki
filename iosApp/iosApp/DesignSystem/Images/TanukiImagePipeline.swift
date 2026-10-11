import Nuke

extension ImagePipeline {
    /// The app's image pipeline. Kotlin fetches and disk-caches (`KotlinDataLoader`); Nuke decodes off
    /// the main thread, coalesces duplicate requests and keeps the in-memory cache. No Nuke disk cache:
    /// Coil's `cover_cache` is the only one, so a cover cached by a Compose screen is a hit here too.
    static let tanuki = ImagePipeline(configuration: Configuration(dataLoader: KotlinDataLoader()))

    /// Reader pages: the same Kotlin-first fetch through the page loader (`PageDataLoader`), with its own
    /// small memory cache, so a long chapter's full-width pages can't push every cover out of memory —
    /// the split Android makes with its dedicated page `ImageLoader`. Pages are decoded at the screen's
    /// width by the reader's requests (`ImageProcessors.Resize`), so a 10 000 px strip costs what it shows.
    static let tanukiPages: ImagePipeline = {
        var configuration = Configuration(dataLoader: PageDataLoader())
        configuration.imageCache = ImageCache(costLimit: 96 * 1024 * 1024, countLimit: 24)
        return ImagePipeline(configuration: configuration)
    }()
}
