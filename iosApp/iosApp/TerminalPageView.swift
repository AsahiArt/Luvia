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
    var onBack: () -> Void
    var onSelectPane: (String) -> Void
    var onAskFix: (String) -> Void = { _ in }
    var glass: Namespace.ID

    @State private var stickToBottom = true
    @State private var literal = ""
    @State private var ctrlLatched = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Text(subtitle)
                .font(.caption.weight(emphasizeSubtitle ? .semibold : .regular))
                .foregroundStyle(emphasizeSubtitle ? TerminalPalette.foreground : TerminalPalette.foreground.opacity(0.72))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)
            if let status = model.terminalStatus, !status.isEmpty, !linkDown {
                Text(status)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            ZStack(alignment: .bottom) {
                TerminalCanvas(
                    text: model.terminalText,
                    ansi: model.terminalAnsi,
                    wrap: true,
                    stickToBottom: $stickToBottom,
                    outlinedLines: outlinedLineIndexes
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
            }
            keyBar
            if model.holdsTerminalControl && !linkDown {
                composer
            }
        }
        .background(TerminalPalette.background.ignoresSafeArea())
        .foregroundStyle(TerminalPalette.foreground)
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                HStack(spacing: 2) {
                    Image(systemName: "chevron.left")
                    Text("对话")
                }
            }
            .accessibilityLabel("回到对话")
            .overlay(alignment: .topTrailing) {
                if pendingDecisions > 0 {
                    Circle()
                        .fill(Color.orange)
                        .frame(width: 8, height: 8)
                        .offset(x: 6, y: -4)
                }
            }
            if panes.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 2) {
                        ForEach(Array(panes.enumerated()), id: \.element.id) { index, pane in
                            let selected = pane.id == selectedPaneID
                            Button {
                                onSelectPane(pane.id)
                            } label: {
                                Text(TerminalChromeKt.terminalSegmentLabel(index: Int32(index), title: pane.title))
                                    .font(.subheadline.weight(selected ? .bold : .semibold))
                                    .foregroundStyle(selected ? Color.white : TerminalPalette.foreground)
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .background(selected ? Color.accentColor : Color.clear, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                    .padding(3)
                }
                .frame(minWidth: 0, maxWidth: .infinity)
                .luviaGlass(in: Capsule())
            } else {
                Text(paneHeading)
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            controlChrome
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var controlChrome: some View {
        if linkDown {
            Text(offlineLabel)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .luviaGlass(in: Capsule())
            if model.selectedHost?.isController == true {
                Button("接管") {}
                    .buttonStyle(.bordered)
                    .tint(.gray)
                    .disabled(true)
            }
        } else if model.holdsTerminalControl {
            Label("已接管", systemImage: "lock.fill")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.green.opacity(0.22), in: Capsule())
                .overlay(Capsule().stroke(Color.green.opacity(0.85), lineWidth: 1))
        } else if model.selectedHost?.isController == true {
            Button("接管") {
                model.requestTerminalControl()
            }
            .buttonStyle(.borderedProminent)
            .tint(.accentColor)
        }
    }

    private var decisionPill: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Color.orange)
                .frame(width: 8, height: 8)
            Text(waitingLine)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
            Spacer(minLength: 8)
            Button("回到对话", action: onBack)
                .buttonStyle(.borderedProminent)
                .tint(.accentColor)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .luviaGlass(in: Capsule(), tint: .orange)
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
        .luviaGlass(in: RoundedRectangle(cornerRadius: 20, style: .continuous))
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
                    Text(title)
                        .font(.caption)
                        .lineLimit(1)
                        .foregroundStyle(TerminalPalette.foreground.opacity(0.8))
                }
            }
            Spacer(minLength: 8)
            Button("交给 claude 修") {
                onAskFix(banner.excerpt)
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .disabled(!canAskFix)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous), tint: .red)
        .shadow(color: Color.red.opacity(0.45), radius: 18, y: 4)
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

    private var failureBanner: TerminalFailureBanner? {
        TerminalChromeKt.terminalFailureBanner(text: model.terminalText)
    }

    private var outlinedLineIndexes: [Int] {
        guard let mask = failureBanner?.outlinedMask else { return [] }
        return mask.enumerated().compactMap { index, bit in
            bit == Character("1") ? index : nil
        }
    }

    private func failureSummary(_ banner: TerminalFailureBanner) -> String {
        let head = "\(banner.passed)/\(banner.total) · \(banner.failed)失败"
        guard let duration = banner.duration, !duration.isEmpty else { return head }
        return "\(head) · \(duration)"
    }

    private var linkDown: Bool {
        guard let host = model.selectedHost else { return false }
        return host.connection != .live
    }

    private var offlineLabel: String {
        guard let host = model.selectedHost else { return "离线" }
        return connectionLabel(host.connection)
    }

    private var paneTitle: String {
        if let selectedPaneID, let match = panes.first(where: { $0.id == selectedPaneID }) {
            return match.title
        }
        return panes.first?.title ?? "终端"
    }

    private var paneHeading: String {
        if let selectedPaneID, let index = panes.firstIndex(where: { $0.id == selectedPaneID }) {
            return "pane \(index + 1)"
        }
        return paneTitle
    }

    private var subtitle: String {
        if linkDown { return offlineLabel }
        if model.holdsTerminalControl {
            return model.terminalText.isEmpty ? paneTitle : "\(paneTitle) · 已同步"
        }
        return "观察中"
    }

    private var emphasizeSubtitle: Bool {
        linkDown || !model.holdsTerminalControl
    }

    private var waitingLine: String {
        let trimmed = waitingTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let name = trimmed.isEmpty ? "Agent" : trimmed
        let detail = waitingDetail?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if detail.isEmpty { return "\(name) 在等你决定" }
        return "\(name) 在等你决定 · \(detail)"
    }
}
