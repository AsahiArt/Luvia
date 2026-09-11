import SwiftUI
import LuviaShared

func ansiAttributedString(
    _ text: String,
    defaultForeground: Color,
    defaultBackground: Color
) -> AttributedString {
    let spans = parseAnsi(text: text)
    if spans.isEmpty { return AttributedString() }
    var result = AttributedString()
    for span in spans {
        var piece = AttributedString(span.text)
        var fg = span.foreground.map { Color(rgb: $0) } ?? defaultForeground
        var bg = span.background.map { Color(rgb: $0) } ?? defaultBackground
        if span.inverse { swap(&fg, &bg) }
        if span.dim { fg = fg.opacity(0.65) }
        piece.foregroundColor = fg
        if span.background != nil || span.inverse {
            piece.backgroundColor = bg
        }
        var intent: InlinePresentationIntent = []
        if span.bold { intent.insert(.stronglyEmphasized) }
        if span.italic { intent.insert(.emphasized) }
        if !intent.isEmpty { piece.inlinePresentationIntent = intent }
        if span.underline { piece.underlineStyle = .single }
        if span.strikethrough { piece.strikethroughStyle = .single }
        result += piece
    }
    return result
}

private extension Color {
    init(rgb: AnsiRgb) {
        self.init(
            red: Double(rgb.r) / 255,
            green: Double(rgb.g) / 255,
            blue: Double(rgb.b) / 255
        )
    }
}
