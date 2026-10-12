import SwiftUI

/// The Tanuki type scale, iOS dialect. Per the design system, iOS text follows the system's Dynamic Type
/// styles in SF Pro: the tokens give Android's sizes and the relative hierarchy, and each maps onto the
/// nearest system style here. The one exception is `hero`, the display face (Bricolage Grotesque, bundled
/// as `BricolageGrotesque-Bold.ttf` and listed under `UIAppFonts`), scaling with `.largeTitle`.
///
/// Apply with `.tkStyle(.body)`. Never set a raw font size.
enum TanukiTextStyle {
    case hero, titleXL, titleL, headline, bodyLarge, body, label, caption, overline, tileTitle, numeric

    fileprivate var font: Font {
        switch self {
        case .hero: .custom("BricolageGrotesque-Bold", size: 34, relativeTo: .largeTitle)
        case .titleXL: .title.bold()
        case .titleL: .title2.weight(.semibold)
        case .headline: .headline
        case .bodyLarge, .body: .body
        case .label: .subheadline.weight(.semibold)
        case .caption: .footnote
        case .overline: .caption2.weight(.bold)
        case .tileTitle: .footnote.weight(.semibold)
        case .numeric: .subheadline.monospacedDigit().weight(.medium)
        }
    }
}

extension View {
    /// Sets text in a Tanuki type style. `overline` is set in uppercase and `hero` tracked tight, as the
    /// design system asks.
    func tkStyle(_ style: TanukiTextStyle) -> some View {
        font(style.font)
            .textCase(style == .overline ? .uppercase : nil)
            // Bricolage is tracked slightly tight (-0.02em at 34pt).
            .tracking(style == .hero ? -0.68 : 0)
    }
}
