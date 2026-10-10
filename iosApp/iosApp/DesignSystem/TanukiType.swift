import SwiftUI
import UIKit

/// The Tanuki type scale, iOS dialect: SF Pro at the design project's sizes
/// (`tokens/typography.css`: Display 28 / Title 20 / Headline 17 / Body 14 / Caption 11 / Label 9), with
/// the same weights, line heights and tracking as Android's `TanukiTextStyles`.
///
/// Apply with `.tkStyle(.body)`. Never set a raw font size: snap to the nearest style. Sizes scale with
/// Dynamic Type, each relative to the closest system style, so the scale holds its proportions while
/// respecting the user's text size.
enum TanukiTextStyle {
    case display, title, headline, body, caption, label, mono

    fileprivate var spec: (size: CGFloat, lineHeight: CGFloat, weight: UIFont.Weight, trackingEm: CGFloat,
                           relativeTo: Font.TextStyle) {
        switch self {
        case .display: (28, 34, .bold, -0.02, .largeTitle)
        case .title: (20, 26, .bold, -0.02, .title3)
        case .headline: (17, 22, .medium, 0, .headline)
        case .body: (14, 20, .regular, 0, .subheadline)
        case .caption: (11, 15, .regular, 0, .caption2)
        case .label: (9, 12, .medium, 0.04, .caption2)
        case .mono: (14, 20, .regular, 0, .subheadline)
        }
    }
}

extension View {
    /// Sets text in a Tanuki type style: font, weight, line height and tracking together.
    func tkStyle(_ style: TanukiTextStyle) -> some View {
        modifier(TanukiTextStyleModifier(style: style))
    }
}

private struct TanukiTextStyleModifier: ViewModifier {
    let style: TanukiTextStyle
    @ScaledMetric private var size: CGFloat
    @ScaledMetric private var lineHeight: CGFloat

    init(style: TanukiTextStyle) {
        self.style = style
        let spec = style.spec
        _size = ScaledMetric(wrappedValue: spec.size, relativeTo: spec.relativeTo)
        _lineHeight = ScaledMetric(wrappedValue: spec.lineHeight, relativeTo: spec.relativeTo)
    }

    func body(content: Content) -> some View {
        let spec = style.spec
        let design: Font.Design = style == .mono ? .monospaced : .default
        // SwiftUI has no line-height setter. lineSpacing adds the gap between lines on top of the font's
        // own line height, so the token's line height minus the font's gives the same leading.
        let natural = UIFont.systemFont(ofSize: size, weight: spec.weight).lineHeight
        return content
            .font(.system(size: size, weight: Font.Weight(spec.weight), design: design))
            .tracking(spec.trackingEm * size)
            .lineSpacing(max(0, lineHeight - natural))
    }
}

private extension Font.Weight {
    init(_ weight: UIFont.Weight) {
        switch weight {
        case .bold: self = .bold
        case .medium: self = .medium
        default: self = .regular
        }
    }
}
