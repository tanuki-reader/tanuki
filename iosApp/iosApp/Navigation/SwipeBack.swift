import UIKit

/// UIKit turns the edge-swipe back gesture off when a stack hides its navigation bar, as the reader does
/// for its own chrome. This turns it back on, for any stack deeper than its root.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
