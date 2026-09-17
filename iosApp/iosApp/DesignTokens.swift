import CoreText
import SwiftUI
import UIKit
enum DesignTokens {
    static let canvas = Color(light: "F4F0EA", dark: "161412")
    static let surface = Color(light: "FFFBF6", dark: "1E1B18")
    static let ink = Color(light: "1C1916", dark: "F3EDE6")
    static let inkMuted = Color(light: "6A635C", dark: "A39B93")
    static let accent = Color(light: "C45C26", dark: "E07A42")
    static let live = Color(light: "2F6F4E", dark: "5BA87A")
    static let connecting = Color(light: "3D6B99", dark: "7BA3C9")
    static let stale = Color(light: "B56A1B", dark: "E09A4A")
    static let offline = Color(light: "8A837C", dark: "8A837C")

    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 16
        static let l: CGFloat = 24
        static let xl: CGFloat = 32
    }

    enum Radius {
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
    }

    enum Typography {
        static let display = Font.system(.largeTitle, design: .serif).weight(.bold)
        static let title = Font.system(.title2, design: .serif).weight(.semibold)
        static let heading = Font.system(.headline, design: .serif)
        static let mono = Font.custom(TerminalFont.postScriptName, size: 13, relativeTo: .footnote)
    }

    enum Terminal {
        static let background = Color(hex: "1A1815")
        static let foreground = Color(hex: "E8E2D8")
        static let muted = Color(hex: "9A9186")
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
