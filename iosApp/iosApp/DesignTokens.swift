import CoreText
import SwiftUI
import UIKit
enum DesignTokens {
    static let canvas = Color(light: "F4F0EA", dark: "161412")
    static let surface = Color(light: "FFFBF6", dark: "1E1B18")
    static let ink = Color(light: "1C1916", dark: "F3EDE6")
    static let inkMuted = Color(light: "6A635C", dark: "A39B93")
    static let accent = Color(light: "C45C26", dark: "E07A42")
    static let linkLive = Color(light: "2F6F4E", dark: "5BA87A")
    static let linkConnecting = Color(light: "3D6B99", dark: "7BA3C9")
    static let linkStale = Color(light: "B56A1B", dark: "E09A4A")
    static let linkOffline = Color(light: "8A837C", dark: "8A837C")
    static let live = linkLive
    static let connecting = linkConnecting
    static let stale = linkStale
    static let offline = linkOffline
    static let agentIdle = inkMuted
    static let agentWorking = linkConnecting
    static let agentBlocked = accent
    static let agentUnknown = linkOffline
    static let diffAdd = Color(light: "0B6E3F", dark: "81C784")
    static let diffDel = Color(light: "B71C1C", dark: "EF9A9A")

    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 16
        static let l: CGFloat = 24
        static let xl: CGFloat = 32
        static let xxl: CGFloat = 48
    }

    enum Radius {
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
    }

    enum Typography {
        static let display = Font.system(.largeTitle, design: .serif).weight(.bold)
        static let title = Font.system(.title2, design: .serif).weight(.semibold)
        static let heading = Font.headline
        static let mono = Font.custom(TerminalFont.postScriptName, size: 13, relativeTo: .footnote)

        static var largeTitleUIFont: UIFont {
            let base = UIFontDescriptor.preferredFontDescriptor(withTextStyle: .largeTitle)
            let serif = base.withDesign(.serif) ?? base
            let bold = serif.withSymbolicTraits(.traitBold) ?? serif
            return UIFont(descriptor: bold, size: 0)
        }
    }

    enum Terminal {
        static let background = Color(hex: "1A1815")
        static let foreground = Color(hex: "E8E2D8")
        static let muted = Color(hex: "9A9186")
        static let bg = background
        static let fg = foreground
    }
}

extension View {
    @ViewBuilder
    func luviaGlass(
        in shape: some Shape = RoundedRectangle(cornerRadius: DesignTokens.Radius.l, style: .continuous)
    ) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
    }

    func luviaSerifLargeTitle() -> some View {
        background(SerifLargeNavigationTitle())
    }
}

private struct SerifLargeNavigationTitle: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = FinderView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        (uiView as? FinderView)?.apply()
    }

    private final class FinderView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }

        func apply() {
            guard let bar = navigationBar() else { return }
            let attrs: [NSAttributedString.Key: Any] = [
                .font: DesignTokens.Typography.largeTitleUIFont,
                .foregroundColor: UIColor(DesignTokens.ink),
            ]
            let standard = bar.standardAppearance.copy()
            standard.largeTitleTextAttributes = attrs
            bar.standardAppearance = standard
            let scroll = (bar.scrollEdgeAppearance ?? bar.standardAppearance).copy()
            scroll.largeTitleTextAttributes = attrs
            bar.scrollEdgeAppearance = scroll
            if let compact = bar.compactAppearance?.copy() {
                compact.largeTitleTextAttributes = attrs
                bar.compactAppearance = compact
            }
            bar.compactScrollEdgeAppearance?.largeTitleTextAttributes = attrs
        }

        private func navigationBar() -> UINavigationBar? {
            var responder: UIResponder? = self
            while let current = responder {
                if let nav = current as? UINavigationController {
                    return nav.navigationBar
                }
                responder = current.next
            }
            var view: UIView? = self
            while let current = view {
                if let bar = current as? UINavigationBar { return bar }
                view = current.superview
            }
            return nil
        }
    }
}

enum TerminalFont {
    static let postScriptName = "JetBrainsMonoNLNFM-Regular"
    private static let fileName = "JetBrainsMonoNLNerdFontMono-Regular"

    static func register() {
        let urls = [
            Bundle.main.url(forResource: fileName, withExtension: "ttf"),
            Bundle.main.url(forResource: fileName, withExtension: "ttf", subdirectory: "Fonts"),
        ]
        for url in urls.compactMap({ $0 }) {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let r, g, b: UInt64
        switch hex.count {
        case 6:
            (r, g, b) = (int >> 16, int >> 8 & 0xFF, int & 0xFF)
        default:
            (r, g, b) = (0, 0, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: 1
        )
    }

    init(light: String, dark: String) {
        self.init(
            uiColor: UIColor(
                light: UIColor(Color(hex: light)),
                dark: UIColor(Color(hex: dark))
            )
        )
    }
}

extension UIColor {
    convenience init(light: UIColor, dark: UIColor) {
        self.init { traitCollection in
            traitCollection.userInterfaceStyle == .dark ? dark : light
        }
    }
}
