import Shared

/// The app's label for a contract listing (sources can't name their own: labels must be translatable).
func listingTitle(_ listing: SourceListing?) -> String {
    guard let listing else { return tr("source_all_series", table: .source) }
    return switch listing {
    case .popular: tr("listing_popular", table: .source)
    case .latest: tr("listing_latest", table: .source)
    case .theNew: tr("listing_new", table: .source)
    }
}

/// "Ch. 222 · 15 h ago", or whichever half the source reported; `nil` when it reported neither.
func chapterCaption(_ item: CatalogItem) -> String? {
    let chapter = item.latestChapterLabel.map { tr("source_latest_chapter", $0, table: .source) } ?? item.latestChapterName
    let ago = item.updatedAgo.map(elapsedCaption)
    let parts = [chapter, ago].compactMap { $0 }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

private func elapsedCaption(_ elapsed: Elapsed) -> String {
    let count = Int(elapsed.count)
    return switch elapsed.unit {
    case .justNow: tr("source_time_just_now", table: .source)
    case .minutes: tr("source_time_minutes", count, table: .source)
    case .hours: tr("source_time_hours", count, table: .source)
    case .days: tr("source_time_days", count, table: .source)
    case .weeks: tr("source_time_weeks", count, table: .source)
    }
}
