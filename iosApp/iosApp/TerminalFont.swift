import CoreText
import SwiftUI
import UIKit

/// JetBrains Mono Nerd Font registration and glyph prewarm. Kept independent of the v2 theme.
enum TerminalFont {
    static let postScriptName = "JetBrainsMonoNLNFM-Regular"
    static let referenceSize: CGFloat = 13
    /// Wrap-on readability floor of 13pt. Wrap-off pinch uses `minUserZoomWrapOff`.
    static let minZoom: CGFloat = 0.7
    static let minUserZoomWrapOff: CGFloat = 1
    static let maxZoom: CGFloat = 2.5

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

    static func prewarm() {
        DispatchQueue.global(qos: .userInitiated).async {
            warmGlyphCache()
        }
    }

    nonisolated private static func warmGlyphCache() {
        let sample = prewarmGlyphs
        for size in [CGFloat(13), CGFloat(13) * 2.5] {
            guard let font = UIFont(name: postScriptName, size: size) else { continue }
            let attributed = NSAttributedString(string: sample, attributes: [.font: font])
            let constraint = CGSize(width: 4096, height: CGFloat.greatestFiniteMagnitude)
            _ = attributed.boundingRect(
                with: constraint,
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                context: nil
            )
            let framesetter = CTFramesetterCreateWithAttributedString(attributed)
            let path = CGPath(
                rect: CGRect(origin: .zero, size: CGSize(width: 4096, height: 2048)),
                transform: nil
            )
            let frame = CTFramesetterCreateFrame(
                framesetter,
                CFRange(location: 0, length: attributed.length),
                path,
                nil
            )
            _ = CTFrameGetLines(frame)
        }
    }

    nonisolated private static let prewarmGlyphs: String = {
        var sample = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 "
        for value in 0xE0A0...0xE0BF {
            sample.unicodeScalars.append(UnicodeScalar(value)!)
        }
        sample += "\u{2500}\u{2502}\u{250C}\u{2510}\u{2514}\u{2518}\u{251C}\u{2524}\u{252C}\u{2534}\u{253C}\u{2550}\u{2551}\u{256C}\u{2570}\u{2573}\u{25C6}"
        return sample
    }()

    /// Unwrapped layout width at 13pt Nerd Font. Shrink-to-fit uses this, not zoom.
    static func unwrappedWidthAtReference(of attributed: NSAttributedString) -> CGFloat {
        guard attributed.length > 0 else { return 0 }
        let rect = attributed.boundingRect(
            with: CGSize(
                width: CGFloat.greatestFiniteMagnitude,
                height: CGFloat.greatestFiniteMagnitude
            ),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        return ceil(rect.width)
    }
}

enum TerminalPalette {
    /// Misaka 2026-11-01 light board, and the SPEC dark pair. Not the older `#1A1815` / `#E8E2D8` pane.
    static let background = Color(uiBackground)
    static let foreground = Color(uiForeground)
    /// Light secondary is `#3F4947`. Dark dim stays the SPEC foreground at 65%, matching the Android dark dim scale.
    static let dim = Color(uiDim)
    static let inset: CGFloat = 16

    static let uiBackground = UIColor(light: UIColor(hex: "EEF5F2"), dark: UIColor(hex: "101615"))
    static let uiForeground = UIColor(light: UIColor(hex: "161D1C"), dark: UIColor(hex: "E4EEEB"))
    static let uiDim = UIColor { traits in
        if traits.userInterfaceStyle == .dark {
            return UIColor(hex: "E4EEEB").withAlphaComponent(0.65)
        }
        return UIColor(hex: "3F4947")
    }
    /// SPEC allows `#410002` for failure text. Dark outlined body stays `#E4EEEB`.
    static let uiFailureInk = UIColor(light: UIColor(hex: "410002"), dark: UIColor(hex: "E4EEEB"))
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
}

extension UIColor {
    convenience init(hex: String) {
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
            red: CGFloat(r) / 255,
            green: CGFloat(g) / 255,
            blue: CGFloat(b) / 255,
            alpha: 1
        )
    }

    convenience init(light: UIColor, dark: UIColor) {
        self.init { traitCollection in
            traitCollection.userInterfaceStyle == .dark ? dark : light
        }
    }
}
