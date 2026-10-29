import SwiftUI

extension View {
    /// Meldet, ob die Liste weiter als `threshold` Punkte nach unten gescrollt ist.
    ///
    /// Ab iOS 18 über `onScrollGeometryChange`; unter iOS 17 gibt es dafür keine Schnittstelle,
    /// dann bleibt der Zustand unverändert (der Knopf bleibt ausgeschrieben).
    func onScrolledPast(_ threshold: CGFloat, action: @escaping (Bool) -> Void) -> some View {
        modifier(ScrolledPast(threshold: threshold, action: action))
    }
}

/// Umsetzung von ``SwiftUI/View/onScrolledPast(_:action:)``.
private struct ScrolledPast: ViewModifier {
    let threshold: CGFloat
    let action: (Bool) -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > threshold } action: { _, scrolled in
                action(scrolled)
            }
        } else {
            content
        }
    }
}
