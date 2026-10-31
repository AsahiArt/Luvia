import LuviaShared
import SwiftUI

struct TerminalPageView: View {
    @Bindable var model: AppModel
    var panes: [(id: String, title: String)]
    var selectedPaneID: String?
    var pendingDecisions: Int
    var onBack: () -> Void
    var onSelectPane: (String) -> Void
    var glass: Namespace.ID

    @State private var stickToBottom = true
    @State private var literal = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Label("回到对话", systemImage: "chevron.left")
                        .labelStyle(.iconOnly)
                }
                VStack(alignment: .leading) {
                    Text("终端").font(.headline)
                    Text(model.holdsTerminalControl ? "已接管" : "观察中")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if pendingDecisions > 0 {
                    Text("\(pendingDecisions) 条待决定")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .luviaGlass(in: Capsule(), tint: .blue)
                }
                if model.selectedHost?.isController == true {
                    Button(model.holdsTerminalControl ? "释放" : "接管") {
                        if model.holdsTerminalControl {
                            model.releaseTerminalControl()
                        } else {
                            model.requestTerminalControl()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .terminalGlass(namespace: glass)
            .padding(.horizontal, 8)
            if panes.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(panes, id: \.id) { pane in
                            terminalChip(pane.title, selected: pane.id == selectedPaneID) {
                                onSelectPane(pane.id)
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                }
            }
            if let status = model.terminalStatus, !status.isEmpty {
                Text(status)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            TerminalCanvas(
                text: model.terminalText,
                ansi: model.terminalAnsi,
                wrap: true,
                stickToBottom: $stickToBottom
            )
            .background(TerminalPalette.background)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    key("Esc", .escape)
                    key("Tab", .tab)
                    key("Ctrl-C", .ctrlC)
                    key("上", .up)
                    key("下", .down)
                    key("回车", .enter)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
            }
            if model.holdsTerminalControl {
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
                }
                .padding(12)
                .luviaGlass(in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .padding(.horizontal, 8)
            }
        }
        .background(TerminalPalette.background.ignoresSafeArea())
        .foregroundStyle(TerminalPalette.foreground)
    }

    private func key(_ title: String, _ value: TerminalKey) -> some View {
        terminalChip(title, selected: false) {
            _Concurrency.Task { await model.sendTerminalKey(value) }
        }
    }

    private func terminalChip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(selected ? .bold : .semibold))
                .foregroundStyle(selected ? Color.white : Color(hex: "14201E"))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(selected ? Color.blue : Color(hex: "E7EEEC"), in: Capsule())
                .overlay(
                    Capsule().stroke(
                        selected ? Color.white : Color(hex: "14201E").opacity(0.45),
                        lineWidth: selected ? 2 : 1
                    )
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : AccessibilityTraits())
    }
}
