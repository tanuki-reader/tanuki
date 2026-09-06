package app.tanuki.core.domain.settings.model

/**
 * Which newly-found chapters the app downloads on its own.
 *
 * Per category from day one rather than a plain on/off switch: [Category] already exists, most libraries
 * mix a handful of series the user follows closely with a long tail they dip into, and retrofitting the
 * distinction later would be a preference migration. The cost of having it now is one extra arm.
 *
 * Stored by [name], so the order of these entries can change freely — only the names are a compatibility
 * promise (see `Converters` and `SettingsRepositoryImp`).
 */
enum class AutoDownloadMode {
    /** Nothing is downloaded unless the user asks for it. The default, and deliberately so: this
     * setting spends storage and mobile data on the user's behalf, which is never the safe default. */
    Off,

    /** Every new chapter of every series in the library. */
    AllLibrary,

    /**
     * Only new chapters of series in the chosen categories. An empty selection behaves like [Off] rather
     * than like [AllLibrary] — "download for these categories", with none picked, cannot mean "all".
     */
    SelectedCategories,
}
