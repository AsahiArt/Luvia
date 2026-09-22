import SwiftUI
import UIKit
import LuviaShared

struct TerminalCanvas: UIViewRepresentable {
    var text: String
    var ansi: Bool
    var wrap: Bool
    @Binding var stickToBottom: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> TerminalCanvasHostView {
        let host = TerminalCanvasHostView()
        context.coordinator.attach(host)
        return host
    }

    func updateUIView(_ host: TerminalCanvasHostView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.host = host
        context.coordinator.sync()
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: TerminalCanvas
        weak var host: TerminalCanvasHostView?

        private var lastText: String?
        private var lastAnsi: Bool?
        private var lastWrap: Bool?
        private var lastViewport: CGSize = .zero
        private var parseGeneration: UInt64 = 0
        private var userZoom: CGFloat = 1
        private var fit: CGFloat = 1
        private var bufferWidth: CGFloat = 0
        private var unwrappedSize: CGSize = .zero
        private var ignoringScroll = false
        private var appliedStick = true
        private var userIsPinching = false
        private var relayoutAfterPinch = false

        init(_ parent: TerminalCanvas) {
            self.parent = parent
        }

        deinit {
            parseGeneration &+= 1
        }

        func attach(_ host: TerminalCanvasHostView) {
            self.host = host
            host.scrollView.delegate = self
            host.onLayout = { [weak self] size in
                self?.viewportDidChange(size)
            }
        }

        func sync() {
            let textChanged = lastText != parent.text || lastAnsi != parent.ansi
            let wrapChanged = lastWrap != parent.wrap
            lastWrap = parent.wrap

            if textChanged {
                lastText = parent.text
                lastAnsi = parent.ansi
                scheduleParse(text: parent.text, ansi: parent.ansi)
            } else if wrapChanged {
                relayout(forceZoom: true)
            }

            if parent.stickToBottom, !appliedStick {
                scrollToBottom()
            }
            appliedStick = parent.stickToBottom
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            host?.zoomView
        }

        func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
            userIsPinching = true
        }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            userIsPinching = false
            let pending = relayoutAfterPinch
            relayoutAfterPinch = false
            if pending {
                relayout(forceZoom: false)
            }
            rememberUserZoom(scale)
            if abs(scrollView.zoomScale - scale) > 0.001 {
                scrollView.zoomScale = scale
            }
            if pending, parent.stickToBottom {
                scrollToBottom()
            }
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard !ignoringScroll, !scrollView.isZooming else { return }
            guard scrollView.isDragging || scrollView.isTracking || scrollView.isDecelerating else { return }
            if isNearBottom(scrollView) {
                setStuck(true)
            } else {
                setStuck(false)
            }
        }

        private func scheduleParse(text: String, ansi: Bool) {
            parseGeneration &+= 1
            let generation = parseGeneration
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let attributed = makeTerminalAttributedString(text: text, parseANSI: ansi)
                DispatchQueue.main.async {
                    self?.apply(attributed, generation: generation)
                }
            }
        }

        private func apply(_ attributed: NSAttributedString, generation: UInt64) {
            guard generation == parseGeneration, let host else { return }
            bufferWidth = TerminalFont.unwrappedWidthAtReference(of: attributed)
            ignoringScroll = true
            host.textView.attributedText = attributed
            host.textView.isScrollEnabled = false
            ignoringScroll = false
            relayout(forceZoom: false)
            if parent.stickToBottom, !userIsPinching, !host.scrollView.isZooming {
                scrollToBottom()
            }
        }

        private func viewportDidChange(_ size: CGSize) {
            guard size.width > 0, size.height > 0 else { return }
            if size == lastViewport { return }
            relayout(forceZoom: false)
            if parent.stickToBottom {
                scrollToBottom()
            }
        }

        private func relayout(forceZoom: Bool) {
            guard let host else { return }
            let scrollView = host.scrollView
            if userIsPinching || scrollView.isZooming || scrollView.isZoomBouncing {
                relayoutAfterPinch = true
                return
            }
            let viewport = host.bounds.size
            guard viewport.width > 0, viewport.height > 0 else { return }
            let wrap = parent.wrap
            lastViewport = viewport

            ignoringScroll = true
            defer { ignoringScroll = false }

            host.textView.isScrollEnabled = false

            let minZoom: CGFloat
            let maxZoom: CGFloat
            let targetZoom: CGFloat

            if wrap {
                host.textView.textContainer.widthTracksTextView = true
                host.textView.frame = CGRect(x: 0, y: 0, width: viewport.width, height: 1)
                host.textView.textContainer.size = CGSize(
                    width: max(viewport.width - host.textView.textContainerInset.left - host.textView.textContainerInset.right, 1),
                    height: CGFloat.greatestFiniteMagnitude
                )
                let height = host.textView.sizeThatFits(
                    CGSize(width: viewport.width, height: CGFloat.greatestFiniteMagnitude)
                ).height
                let size = CGSize(width: viewport.width, height: max(height, 1))
                host.zoomView.frame = CGRect(origin: .zero, size: size)
                host.textView.frame = host.zoomView.bounds
                fit = 1
                if forceZoom {
                    userZoom = 1
                }
                userZoom = min(max(userZoom, TerminalFont.minZoom), TerminalFont.maxZoom)
                minZoom = TerminalFont.minZoom
                maxZoom = TerminalFont.maxZoom
                targetZoom = userZoom
            } else {
                host.textView.textContainer.widthTracksTextView = false
                host.textView.textContainer.size = CGSize(
                    width: CGFloat.greatestFiniteMagnitude,
                    height: CGFloat.greatestFiniteMagnitude
                )
                let fitted = host.textView.sizeThatFits(
                    CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
                )
                let inset = host.textView.textContainerInset
                let padding = host.textView.textContainer.lineFragmentPadding * 2
                let width = bufferWidth > 0
                    ? bufferWidth + inset.left + inset.right + padding
                    : max(fitted.width, 1)
                let size = CGSize(width: max(width, 1), height: max(fitted.height, 1))
                unwrappedSize = size
                host.zoomView.frame = CGRect(origin: .zero, size: size)
                host.textView.frame = host.zoomView.bounds
                fit = viewport.width > 0 ? min(1, viewport.width / size.width) : 1
                if forceZoom {
                    userZoom = 1
                }
                userZoom = min(max(userZoom, TerminalFont.minUserZoomWrapOff), TerminalFont.maxZoom)
                minZoom = fit
                maxZoom = fit * TerminalFont.maxZoom
                targetZoom = fit * userZoom
            }

            applyZoomScale(
                scrollView,
                minZoom: minZoom,
                maxZoom: maxZoom,
                targetZoom: targetZoom,
                force: forceZoom
            )
            let scale = scrollView.zoomScale
            scrollView.contentSize = CGSize(
                width: host.zoomView.bounds.width * scale,
                height: host.zoomView.bounds.height * scale
            )
        }

        private func applyZoomScale(
            _ scrollView: UIScrollView,
            minZoom: CGFloat,
            maxZoom: CGFloat,
            targetZoom: CGFloat,
            force: Bool
        ) {
            let current = scrollView.zoomScale
            let target = min(max(targetZoom, minZoom), maxZoom)
            if current < minZoom || current > maxZoom {
                scrollView.minimumZoomScale = min(current, minZoom, target)
                scrollView.maximumZoomScale = max(current, maxZoom, target)
                scrollView.zoomScale = target
                scrollView.minimumZoomScale = minZoom
                scrollView.maximumZoomScale = maxZoom
                return
            }
            scrollView.minimumZoomScale = minZoom
            scrollView.maximumZoomScale = maxZoom
            if force || abs(current - target) > 0.001 {
                scrollView.zoomScale = target
            }
        }



        private func rememberUserZoom(_ scale: CGFloat) {
            if parent.wrap {
                userZoom = min(max(scale, TerminalFont.minZoom), TerminalFont.maxZoom)
            } else {
                let currentFit = max(fit, 0.0001)
                userZoom = min(max(scale / currentFit, TerminalFont.minUserZoomWrapOff), TerminalFont.maxZoom)
            }
        }

        private func scrollToBottom() {
            guard let scrollView = host?.scrollView else { return }
            scrollView.layoutIfNeeded()
            let maxY = max(0, scrollView.contentSize.height - scrollView.bounds.height)
            let offset = CGPoint(x: scrollView.contentOffset.x, y: maxY)
            ignoringScroll = true
            scrollView.setContentOffset(offset, animated: false)
            ignoringScroll = false
            appliedStick = true
        }

        private func isNearBottom(_ scrollView: UIScrollView) -> Bool {
            let maxY = max(0, scrollView.contentSize.height - scrollView.bounds.height)
            return scrollView.contentOffset.y >= maxY - 8
        }

        private func setStuck(_ stuck: Bool) {
            guard parent.stickToBottom != stuck else { return }
            appliedStick = stuck
            parent.stickToBottom = stuck
        }
    }
}

final class TerminalCanvasHostView: UIView {
    let scrollView = UIScrollView()
    let zoomView = UIView()
    let textView = UITextView()
    var onLayout: ((CGSize) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        let background = terminalCanvasBackground
        backgroundColor = background
        isOpaque = true

        scrollView.backgroundColor = background
        scrollView.isOpaque = true
        scrollView.alwaysBounceVertical = true
        scrollView.alwaysBounceHorizontal = true
        scrollView.bouncesZoom = true
        scrollView.keyboardDismissMode = .none
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.indicatorStyle = .white
        scrollView.showsVerticalScrollIndicator = true
        scrollView.showsHorizontalScrollIndicator = true
        addSubview(scrollView)

        zoomView.backgroundColor = background
        zoomView.isOpaque = true
        scrollView.addSubview(zoomView)

        textView.backgroundColor = background
        textView.isOpaque = true
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.bounces = false
        textView.showsVerticalScrollIndicator = false
        textView.showsHorizontalScrollIndicator = false
        textView.textContainerInset = UIEdgeInsets(
            top: DesignTokens.Space.m,
            left: DesignTokens.Space.m,
            bottom: DesignTokens.Space.m,
            right: DesignTokens.Space.m
        )
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainer.lineBreakMode = .byClipping
        textView.textContainer.widthTracksTextView = false
        textView.textContainer.size = CGSize(
            width: CGFloat.greatestFiniteMagnitude,
            height: CGFloat.greatestFiniteMagnitude
        )
        textView.font = terminalCanvasFont
        textView.textColor = terminalCanvasForeground
        textView.overrideUserInterfaceStyle = .dark
        textView.adjustsFontForContentSizeCategory = false
        textView.dataDetectorTypes = []
        textView.accessibilityLabel = "Terminal output"
        zoomView.addSubview(textView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private var restoringZoom = false

    override func layoutSubviews() {
        super.layoutSubviews()
        guard !restoringZoom else { return }
        let scale = scrollView.zoomScale
        let offset = scrollView.contentOffset
        scrollView.frame = bounds
        if abs(scrollView.zoomScale - scale) > 0.001 {
            restoringZoom = true
            scrollView.zoomScale = scale
            scrollView.contentOffset = offset
            restoringZoom = false
        }
        onLayout?(bounds.size)
    }
}

private let terminalCanvasFont: UIFont =
    UIFont(name: TerminalFont.postScriptName, size: TerminalFont.referenceSize)
    ?? UIFont.monospacedSystemFont(ofSize: TerminalFont.referenceSize, weight: .regular)

private let terminalCanvasBackground = UIColor(DesignTokens.Terminal.background)
private let terminalCanvasForeground = UIColor(DesignTokens.Terminal.foreground)

private func makeTerminalAttributedString(text: String, parseANSI: Bool) -> NSAttributedString {
    let attributes: [NSAttributedString.Key: Any] = [
        .font: terminalCanvasFont,
        .foregroundColor: terminalCanvasForeground,
    ]
    if text.isEmpty {
        return NSAttributedString(string: "", attributes: attributes)
    }
    if !parseANSI || !terminalTextContainsEscape(text) {
        return NSAttributedString(string: text, attributes: attributes)
    }
    let spans = parseAnsi(text: text)
    let result = NSMutableAttributedString()
    result.beginEditing()
    for span in spans {
        result.append(NSAttributedString(string: span.text, attributes: attributesForSpan(span)))
    }
    result.endEditing()
    return result
}

private func attributesForSpan(_ span: AnsiSpan) -> [NSAttributedString.Key: Any] {
    var attrs: [NSAttributedString.Key: Any] = [
        .font: terminalUIFont(bold: span.bold, italic: span.italic),
    ]
    var foreground = span.foreground.map(UIColor.init(ansi:)) ?? terminalCanvasForeground
    var background = span.background.map(UIColor.init(ansi:)) ?? terminalCanvasBackground
    if span.inverse {
        swap(&foreground, &background)
    }
    if span.dim {
        foreground = foreground.withAlphaComponent(0.65)
    }
    attrs[.foregroundColor] = foreground
    if span.background != nil || span.inverse {
        attrs[.backgroundColor] = background
    }
    if span.underline {
        attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
    }
    if span.strikethrough {
        attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
    }
    return attrs
}

private func terminalUIFont(bold: Bool, italic: Bool) -> UIFont {
    guard bold || italic else { return terminalCanvasFont }
    var traits = terminalCanvasFont.fontDescriptor.symbolicTraits
    if bold { traits.insert(.traitBold) }
    if italic { traits.insert(.traitItalic) }
    guard let descriptor = terminalCanvasFont.fontDescriptor.withSymbolicTraits(traits) else {
        return terminalCanvasFont
    }
    return UIFont(descriptor: descriptor, size: TerminalFont.referenceSize)
}

private func terminalTextContainsEscape(_ text: String) -> Bool {
    text.unicodeScalars.contains { $0.value == 0x1B || $0.value == 0x9B }
}

private extension UIColor {
    convenience init(ansi rgb: AnsiRgb) {
        self.init(
            red: CGFloat(rgb.r) / 255,
            green: CGFloat(rgb.g) / 255,
            blue: CGFloat(rgb.b) / 255,
            alpha: 1
        )
    }
}
