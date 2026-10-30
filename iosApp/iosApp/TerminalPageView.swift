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
                            Button(pane.title) { onSelectPane(pane.id) }
                                .buttonStyle(.bordered)
                                .tint(pane.id == selectedPaneID ? .blue : .secondary)
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
                HStack {
                    key("Esc", .escape)
                    key("Tab", .tab)
                    key("Ctrl-C", .ctrlC)
                    key("上", .up)
                    key("下", .down)
                    key("回车", .enter)
                }
                .padding(.horizontal, 12)
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
        Button(title) {
            _Concurrency.Task { await model.sendTerminalKey(value) }
        }
        .buttonStyle(.bordered)
        .tint(TerminalPalette.foreground)
    }
}
