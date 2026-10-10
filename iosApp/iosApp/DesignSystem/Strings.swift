import Foundation

/// Localized copy, from the generated string tables in `Strings/`. Those are made from each Kotlin
/// module's Compose `strings.xml` by `scripts/generate-ios-strings.py`. Edit the `strings.xml`, never
/// the `.xcstrings`: copy has one source, shared with Android.
///
/// Format arguments are positional, like the source (`%1$@` for a `String`, `%1$lld` for an `Int`), and
/// a key with plural variations picks its form from the number:
///
/// ```swift
/// tr("home_continue_reading_chapter", "7", table: .home)  // "Chapter 7" / "Capítulo 7"
/// tr("home_time_days", 3, table: .home)                    // "3 days ago"
/// ```
///
/// Plain keys in the default table also work directly as a `LocalizedStringKey`: `Text("tab_home")`.
func tr(_ key: String, _ args: CVarArg..., table: StringTable = .localizable) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: nil, table: table.rawValue)
    return args.isEmpty ? format : String(format: format, locale: .current, arguments: args)
}
