import SwiftUI

extension TK {
    /// Motion, from the design system's README (it has no motion tokens): enter 250ms decelerating, exit
    /// 200ms accelerating, so exits are always shorter. Under Reduce Motion every transition becomes a
    /// 100ms crossfade (`reduced`). Pushes use the native navigation transition, not these.
    enum Motion {
        static let enter: Animation = .easeOut(duration: 0.25)
        static let exit: Animation = .easeIn(duration: 0.2)
        static let reduced: Animation = .easeInOut(duration: 0.1)
    }
}
