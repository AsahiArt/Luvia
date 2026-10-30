import SwiftUI

extension View {
    /// Liquid Glass on iOS 26, and a material card before that.
    @ViewBuilder
    func luviaGlass(
        in shape: some Shape = RoundedRectangle(cornerRadius: 22, style: .continuous),
        tint: Color? = nil
    ) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: shape)
        } else if let tint {
            self.background(tint.opacity(0.18), in: shape)
                .background(.ultraThinMaterial, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
    }

    @ViewBuilder
    func terminalGlass(namespace: Namespace.ID) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: Capsule()).glassEffectID("terminal", in: namespace)
        } else {
            self.background(.ultraThinMaterial, in: Capsule())
        }
    }
}

struct LuviaGlassContainer<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 16) {
                content()
            }
        } else {
            content()
        }
    }
}

enum ChatPalette {
    static let seed = Color(hex: "006A62")
    static let pairBackground = Color(hex: "070B16")
    static let pairBlue = Color(hex: "4C8DFF")
    static let tertiary = Color(hex: "466179")
}
