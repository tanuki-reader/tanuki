import SwiftUI

/// Spacing, radii, sizes and motion, iOS dialect, from the design project's `tokens/spacing.css`.
/// Names match Android's `TanukiSpacing`, except that radii follow the Liquid Glass family (10–14pt)
/// where Android's are Material's (4pt).
enum TK {
    /// 8pt grid with 4pt half-steps.
    enum Space {
        static let none: CGFloat = 0
        static let xxs: CGFloat = 2
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 24
        static let x3l: CGFloat = 32
        static let x4l: CGFloat = 40
        static let x5l: CGFloat = 48
        static let x6l: CGFloat = 64

        /// Default screen edge padding.
        static let gutter: CGFloat = 16
        static let gutterTight: CGFloat = 12
        /// Library cover grid gap.
        static let gridGap: CGFloat = 12
    }

    enum Radius {
        /// Liquid Glass container (`--r-ios`).
        static let container: CGFloat = 10
        /// Glass row group (`--r-glass-card`).
        static let glassCard: CGFloat = 14
        /// Manga cover art corners. The same on both platforms.
        static let cover: CGFloat = 6
        /// Sheet / card top.
        static let sheet: CGFloat = 16
        static let chip: CGFloat = 8
        /// Search pill, badges. Use `Capsule()` for a shape.
        static let pill: CGFloat = 999
    }

    enum Size {
        /// Minimum interactive target.
        static let tapMin: CGFloat = 44
    }

    /// iOS motion: the springier `--ease-ios` curve at the design's three durations. Collapse to
    /// `.easeInOut(duration: Duration.fast)` when Reduce Motion is on, as Android does.
    enum Motion {
        enum Duration {
            static let fast: Double = 0.12
            static let base: Double = 0.2
            static let slow: Double = 0.32
        }

        static func ease(_ duration: Double = Duration.base) -> Animation {
            .timingCurve(0.32, 0.72, 0, 1, duration: duration)
        }
    }
}
