import LuviaShared
import SwiftUI

struct TerminalPageView: View {
    @Bindable var model: AppModel
    var panes: [(id: String, title: String)]
    var selectedPaneID: String?
    var pendingDecisions: Int
    var waitingTitle: String? = nil
    var waitingDetail: String? = nil
    var canAskFix: Bool = false
    var textOverride: String? = nil
    /// Debug fixture is on screen. A host stuck in `.connecting` still reads as 离线.
    var fixtureOfflineChrome: Bool = false
    var onBack: () -> Void
    var onSelectPane: (String) -> Void
    var onAskFix: (String) -> Bool = { _ in false }
    var glass: Namespace.ID

    @State private var stickToBottom = true
    @State private var literal = ""
    @State private var ctrlLatched = false
    @State private var fixRefused = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Text(subtitle)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(subtitleColor)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)
                .accessibilityLabel(subtitle)
            if let status = model.terminalStatus, !status.isEmpty, !linkDown, textOverride == nil {
                Text(status)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            ZStack(alignment: .bottom) {
                TerminalCanvas(
                    text: shownText,
                    ansi: shownAnsi,
                    wrap: true,
                    stickToBottom: $stickToBottom,
                    outlinedLines: outlinedLineIndexes,
                    bottomInset: failureBanner == nil ? 0 : 96
                )
                .background(TerminalPalette.background)
                if let banner = failureBanner {
                    failureCard(banner)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 8)
                }
            }
            if pendingDecisions > 0 {
                decisionPill
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
            }
            keyBar
            if model.holdsTerminalControl && !linkDown {
                composer
            }
        }
        .background(TerminalPalette.background.ignoresSafeArea())
        .foregroundStyle(TerminalPalette.foreground)
        .preferredColorScheme(.dark)
        .onChange(of: shownText) { _, _ in
            fixRefused = false
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                    Text("对话")
                }
                .font(.body.weight(.medium))
                .padding(.leading, 10)
                .padding(.trailing, 14)
                .padding(.vertical, 8)
                .terminalPlate(in: Capsule(), fill: chromeFill)
            }
            .buttonStyle(.plain)
            .fixedSize(horizontal: true, vertical: false)
            .layoutPriority(2)
            .accessibilityLabel("回到对话")
            .overlay(alignment: .topTrailing) {
                if pendingDecisions > 0 {
                    Circle()
                        .fill(decisionOrange)
                        .frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Color.black, lineWidth: 1.5))
                        .offset(x: -2, y: 4)
                        .accessibilityHidden(true)
                }
            }
            if !panes.isEmpty {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 2) {
                            ForEach(Array(panes.enumerated()), id: \.element.id) { index, pane in
                                let selected = pane.id == selectedPaneID
                                Button {
                                    onSelectPane(pane.id)
                                } label: {
                                    Text(TerminalChromeKt.terminalSegmentLabel(index: Int32(index), title: pane.title))
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(selected ? Color.black : Color.white)
                                        .lineLimit(1)
                                        .fixedSize(horizontal: true, vertical: false)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 8)
                                        .background(selected ? Color.white.opacity(0.92) : Color.clear, in: Capsule())
                                }
                                .buttonStyle(.plain)
                                .fixedSize(horizontal: true, vertical: false)
                                .id(pane.id)
                                .accessibilityAddTraits(selected ? .isSelected : [])
                            }
                        }
                        .padding(3)
                        .fixedSize(horizontal: true, vertical: false)
                    }
                    .onAppear { revealSelectedPane(proxy) }
                    .onChange(of: selectedPaneID) { _, _ in revealSelectedPane(proxy) }
                    .onChange(of: paneIdentity) { _, _ in revealSelectedPane(proxy) }
                }
                .frame(minWidth: 0, maxWidth: .infinity)
                .frame(height: 44)
                .layoutPriority(1)
                .clipped()
                .terminalPlate(in: Capsule(), fill: chromeFill)
            } else {
                Text("终端")
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            controlChrome
                .fixedSize(horizontal: true, vertical: false)
                .layoutPriority(2)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var paneIdentity: String {
        panes.map(\.id).joined(separator: "\u{1e}")
    }

    private func revealSelectedPane(_ proxy: ScrollViewProxy) {
        guard let id = selectedPaneID, panes.contains(where: { $0.id == id }) else { return }
        DispatchQueue.main.async {
            proxy.scrollTo(id, anchor: .trailing)
        }
    }

    @ViewBuilder
    private var controlChrome: some View {
        if linkDown {
            HStack(spacing: 8) {
                Text(offlineWord)
                    .font(.subheadline.weight(.semibold))
                if model.selectedHost?.isController == true {
                    Text("接管")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.white.opacity(0.38))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .terminalPlate(in: Capsule(), fill: chromeFill)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                model.selectedHost?.isController == true ? "\(offlineWord)，接管不可用" : offlineWord
            )
        } else if model.holdsTerminalControl {
            Label("已接管", systemImage: "lock.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color(hex: "30D158"))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .terminalPlate(in: Capsule(), fill: chromeFill)
        } else if model.selectedHost?.isController == true {
            Button("接管") {
                model.requestTerminalControl()
            }
            .buttonStyle(.plain)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .terminalPlate(in: Capsule(), fill: chromeFill)
        }
    }

    private var decisionPill: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(decisionOrange)
                .frame(width: 8, height: 8)
                .shadow(color: decisionOrange, radius: 6)
            Text(waitingLine)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onBack) {
                Text("回到对话")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(decisionOrange, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .terminalPlate(in: Capsule(), fill: decisionFill)
        .accessibilityElement(children: .contain)
    }

    private var keyBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                key("esc", .escape)
                key("tab", .tab)
                Button {
                    ctrlLatched.toggle()
                } label: {
                    keyLabel("ctrl", selected: ctrlLatched)
                }
                .buttonStyle(.plain)
                key("↑", .up)
                key("↓", .down)
                key("←", .left)
                key("→", .right)
                Button {
                    ctrlLatched = false
                    _Concurrency.Task { await model.sendTerminalKey(.ctrlC) }
                } label: {
                    keyLabel("~C", selected: ctrlLatched)
                }
                .buttonStyle(.plain)
                key("回车", .enter)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
    }

    private var composer: some View {
        HStack {
            TextField("输入", text: $literal)
                .font(.system(.body, design: .monospaced))
                .textFieldStyle(.roundedBorder)
            Button("发送") {
                let text = literal
                literal = ""
                _Concurrency.Task { await model.sendTerminal(text) }
            }
            .disabled(literal.isEmpty)
            Button("释放") {
                model.releaseTerminalControl()
            }
        }
        .padding(12)
        .terminalPlate(in: RoundedRectangle(cornerRadius: 20, style: .continuous), fill: chromeFill)
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
    }

    private func failureCard(_ banner: TerminalFailureBanner) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(failureSummary(banner))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if let title = banner.failedTitle, !title.isEmpty {
                    Text(title.replacingOccurrences(of: " > ", with: " › "))
                        .font(.caption.monospaced())
                        .lineLimit(1)
                        .foregroundStyle(Color(hex: "FF9F99"))
                }
                if fixRefused {
                    Text("没发出去")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.red)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                fixRefused = !onAskFix(banner.excerpt)
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "sparkles")
                        .font(.caption.weight(.bold))
                    Text("让 claude 修")
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(canAskFix ? Color.black : Color.black.opacity(0.45))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.white.opacity(canAskFix ? 0.94 : 0.38), in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(!canAskFix)
            .accessibilityLabel("让 claude 修")
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .terminalPlate(
            in: RoundedRectangle(cornerRadius: 24, style: .continuous),
            fill: failureFill,
            halo: Color.red.opacity(0.45)
        )
    }

    private func key(_ title: String, _ value: TerminalKey) -> some View {
        Button {
            _Concurrency.Task { await model.sendTerminalKey(value) }
        } label: {
            keyLabel(title, selected: false)
        }
        .buttonStyle(.plain)
    }

    private func keyLabel(_ title: String, selected: Bool) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(selected ? Color.white : TerminalPalette.foreground)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(selected ? Color.accentColor : Color.clear, in: Capsule())
            .luviaGlass(in: Capsule(), tint: selected ? .blue : nil)
    }

    private var shownText: String {
        textOverride ?? model.terminalText
    }

    private var shownAnsi: Bool {
        textOverride == nil && model.terminalAnsi
    }

    private var failureBanner: TerminalFailureBanner? {
        TerminalChromeKt.terminalFailureBanner(text: shownText)
    }

    private var outlinedLineIndexes: [Int] {
        guard let mask = failureBanner?.outlinedMask else { return [] }
        return mask.enumerated().compactMap { index, bit in
            bit == Character("1") ? index : nil
        }
    }

    private func failureSummary(_ banner: TerminalFailureBanner) -> String {
        let head = "\(banner.passed)/\(banner.total) · \(banner.failed) 个失败"
        guard let duration = banner.duration, !duration.isEmpty else { return head }
        return "\(head) · \(duration)"
    }

    private var linkDown: Bool {
        guard let host = model.selectedHost else { return false }
        return host.connection != .live
    }

    /// Offline and stale both read as 离线. A live reconnect stays 重连中.
    /// The debug fixture is not a live link, so a host stuck on `.connecting`
    /// still shows 离线 and the offline capsule can be photographed.
    private var offlineWord: String {
        guard let host = model.selectedHost else { return "离线" }
        switch host.connection {
        case .connecting:
            return fixtureOfflineChrome ? "离线" : "重连中"
        case .stale, .offline:
            return "离线"
        default:
            return "离线"
        }
    }

    private var paneTitle: String {
        if let selectedPaneID, let match = panes.first(where: { $0.id == selectedPaneID }) {
            return match.title
        }
        return panes.first?.title ?? "终端"
    }

    private var activeSegment: String {
        if let selectedPaneID, let index = panes.firstIndex(where: { $0.id == selectedPaneID }) {
            return TerminalChromeKt.terminalSegmentLabel(index: Int32(index), title: panes[index].title)
        }
        if let first = panes.first {
            return TerminalChromeKt.terminalSegmentLabel(index: 0, title: first.title)
        }
        return paneTitle
    }

    private var subtitle: String {
        if linkDown { return offlineWord }
        if model.holdsTerminalControl {
            return shownText.isEmpty ? activeSegment : "\(activeSegment) · 已同步"
        }
        return "观察中"
    }

    private var subtitleColor: Color {
        if linkDown || !model.holdsTerminalControl {
            return Color.white
        }
        return Color.white.opacity(0.82)
    }

    private var waitingLine: String {
        let trimmed = waitingTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let name = trimmed.isEmpty ? "Agent" : trimmed
        let detail = waitingDetail?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if detail.isEmpty { return "\(name) 在等你决定" }
        return "\(name) 在等你决定 · \(detail)"
    }

    private var chromeFill: Color {
        Color.white.opacity(0.10)
    }

    private var failureFill: Color {
        Color(red: 0.32, green: 0.08, blue: 0.08).opacity(0.82)
    }

    private var decisionFill: Color {
        Color(red: 0.36, green: 0.20, blue: 0.02).opacity(0.82)
    }

    private var decisionOrange: Color {
        Color(hex: "FF9F0A")
    }
}

private extension View {
    func terminalPlate(in shape: some Shape, fill: Color, halo: Color? = nil) -> some View {
        background {
            shape
                .fill(.ultraThinMaterial)
                .overlay(shape.fill(fill))
                .overlay(shape.stroke(Color.white.opacity(0.16), lineWidth: 0.6))
        }
        .shadow(color: halo ?? .clear, radius: halo == nil ? 0 : 18, y: 4)
    }
}
