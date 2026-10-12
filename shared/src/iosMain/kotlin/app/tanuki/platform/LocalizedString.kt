package app.tanuki.platform

import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.localizedStringWithFormat

/**
 * Copy for Kotlin code that runs with no SwiftUI on screen (notifications posted from a background task),
 * from the app's default table: `Localizable.xcstrings`, which `scripts/generate-ios-strings.py` builds from
 * `:shared`'s `strings.xml`. A plural key resolves through its plural variations by the first number in [args].
 *
 * Arguments go through `localizedStringWithFormat`, an Objective-C *variadic*, where Kotlin values aren't
 * bridged: each `String` is cast to `NSString` (a bare one crashes on `%@`), and each `Int` widened to `Long`
 * for the `%lld` the generator writes.
 */
internal fun localizedString(
    key: String,
    vararg args: Any,
): String {
    val format = NSBundle.mainBundle.localizedStringForKey(key, value = null, table = null)
    if (args.isEmpty()) return format
    val bridged =
        args.map { arg ->
            when (arg) {
                is String -> arg as NSString
                is Int -> arg.toLong()
                else -> arg
            }
        }
    // Spelled out per count: Kotlin/Native only spreads a literal `arrayOf(...)` into an Objective-C variadic.
    return when (bridged.size) {
        1 -> NSString.localizedStringWithFormat(format, bridged[0])
        2 -> NSString.localizedStringWithFormat(format, bridged[0], bridged[1])
        else -> error("localizedString supports up to two arguments, got ${bridged.size} for '$key'.")
    }
}
