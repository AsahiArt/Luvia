import SwiftUI
import LuviaShared

struct HostDetailView: View {
    let host: HostViewState
    @Binding var section: HostSection
    @Bindable var model: AppModel
    var terminalText: String
    var terminalStatus: String?
    var holdsTerminalControl: Bool
    var onConnect: () -> Void
    var onDisconnect: () -> Void
    var onRefresh: () -> Void
    var onSendTerminal: (String) -> Void
    var onSendTerminalKey: (TerminalKey) -> Void
    var onRequestControl: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Picker("Section", selection: $section) {
                ForEach(HostSection.allCases) { item in
                    Label(item.rawValue, systemImage: item.symbol).tag(item)
                }
            }
            .pickerStyle(.segmented)
            .padding()

            Group {
                switch section {
                case .agents:
                    AgentsSectionView(model: model, host: host)
                case .review:
                    ReviewSectionView(model: model, host: host)
                case .tasks:
                    TasksSectionView(model: model, host: host)
                case .terminal:
                    TerminalPane(
                        host: host,
                        text: terminalText,
                        status: terminalStatus,
                        holdsControl: holdsTerminalControl,
                        onSend: onSendTerminal,
                        onSendKey: onSendTerminalKey,
                        onRequestControl: onRequestControl
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationTitle(host.name)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                if host.connection == .live || host.connection == .connecting {
                    Button("Disconnect", systemImage: "pause.circle", action: onDisconnect)
                } else {
                    Button("Connect", systemImage: "bolt.horizontal.circle", action: onConnect)
                }
                Button("Refresh", systemImage: "arrow.clockwise", action: onRefresh)
                    .disabled(host.connection != .live)
                Menu {
                    Button("Files", systemImage: MoreSurface.files.symbol) {
                        model.uhp.moreSurface = .files
                    }
                    Button("Search", systemImage: MoreSurface.search.symbol) {
                        model.uhp.moreSurface = .search
                    }
                    Button("Worktrees", systemImage: MoreSurface.worktrees.symbol) {
                        model.uhp.moreSurface = .worktrees
                    }
                    Button("Automations", systemImage: MoreSurface.automations.symbol) {
                        model.uhp.moreSurface = .automations
                    }
                    Button("Layout", systemImage: MoreSurface.layout.symbol) {
                        model.uhp.moreSurface = .layout
                    }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
                .disabled(host.connection != .live)
                .accessibilityLabel("More")
            }
        }
        .sheet(item: $model.uhp.moreSurface) { surface in
            MoreSurfaceSheet(model: model, surface: surface)
        }
    }
}

private enum TerminalChrome {
    static let background = Color(red: 17 / 255, green: 19 / 255, blue: 24 / 255)
    static let foreground = Color(red: 228 / 255, green: 231 / 255, blue: 236 / 255)
    static let muted = Color(red: 154 / 255, green: 164 / 255, blue: 178 / 255)
}

private struct TerminalKeySpec: Identifiable {
    let id: String
    let title: String
    let key: TerminalKey
}

private struct TerminalPane: View {
    let host: HostViewState
    let text: String
    let status: String?
    let holdsControl: Bool
    var onSend: (String) -> Void
    var onSendKey: (TerminalKey) -> Void
    var onRequestControl: () -> Void

    @State private var input = ""

    private var keys: [TerminalKeySpec] {
        [
            TerminalKeySpec(id: "esc", title: "Esc", key: .escape),
            TerminalKeySpec(id: "tab", title: "Tab", key: .tab),
            TerminalKeySpec(id: "ctrl-c", title: "Ctrl-C", key: .ctrlC),
            TerminalKeySpec(id: "ctrl-d", title: "Ctrl-D", key: .ctrlD),
            TerminalKeySpec(id: "up", title: "↑", key: .up),
            TerminalKeySpec(id: "down", title: "↓", key: .down),
            TerminalKeySpec(id: "left", title: "←", key: .left),
            TerminalKeySpec(id: "right", title: "→", key: .right),
            TerminalKeySpec(id: "enter", title: "Enter", key: .enter),
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(holdsControl ? "Controlling" : "Observing")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .foregroundStyle(holdsControl ? TerminalChrome.background : TerminalChrome.foreground)
                    .background(
                        holdsControl ? Color.green : TerminalChrome.muted.opacity(0.35),
                        in: Capsule()
                    )
                    .accessibilityLabel(holdsControl ? "Controlling terminal" : "Observing terminal")
                    .accessibilityAddTraits(.isStaticText)
                Spacer()
                if host.isController, !holdsControl {
                    Button("Request control") { onRequestControl() }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                        .disabled(host.connection != .live)
                        .accessibilityLabel("Request terminal control")
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)

            if !holdsControl {
                Text("Watching this pane. Request control to type.")
                    .font(.footnote)
                    .foregroundStyle(TerminalChrome.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                    .accessibilityLabel("Watching this pane. Request control to type.")
            }

            if let status {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(TerminalChrome.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
            if text.isEmpty {
                ContentUnavailableView(
                    "Terminal unavailable",
                    systemImage: "terminal",
                    description: Text("Select a live pane to observe or request control.")
                )
                .foregroundStyle(TerminalChrome.foreground)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                JumpToLatestScroll(token: text) {
                    Text(
                        ansiAttributedString(
                            text,
                            defaultForeground: TerminalChrome.foreground,
                            defaultBackground: TerminalChrome.background
                        )
                    )
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding()
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(
                            holdsControl ? Color.green.opacity(0.85) : TerminalChrome.muted.opacity(0.45),
                            lineWidth: 2
                        )
                        .padding(4)
                }
            }
            if host.isController {
                VStack(spacing: 8) {
                    if holdsControl {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(keys) { spec in
                                    Button(spec.title) {
                                        onSendKey(spec.key)
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                    .foregroundStyle(TerminalChrome.foreground)
                                    .disabled(host.connection != .live)
                                }
                            }
                        }
                    }
                    HStack(spacing: 8) {
                        TextField("Send to terminal", text: $input)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.body)
                            .foregroundStyle(TerminalChrome.foreground)
                        Button("Send") {
                            let payload = input
                            input = ""
                            onSend(payload)
                        }
                        .foregroundStyle(TerminalChrome.foreground)
                        .disabled(input.isEmpty || host.connection != .live || !holdsControl)
                    }
                }
                .padding()
            }
        }
        .background(TerminalChrome.background)
        .colorScheme(.dark)
    }
}

struct JumpToLatestScroll<Content: View>: View {
    let token: String
    @ViewBuilder var content: () -> Content

    @State private var stickToBottom = true
    private let endID = "jump-end"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView([.horizontal, .vertical]) {
                VStack(alignment: .leading, spacing: 0) {
                    content()
                    Color.clear.frame(width: 1, height: 1).id(endID)
                }
            }
            .onAppear {
                proxy.scrollTo(endID, anchor: .bottomLeading)
            }
            .onChange(of: token) { _, _ in
                if stickToBottom {
                    proxy.scrollTo(endID, anchor: .bottomLeading)
                }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 16).onChanged { value in
                    let vertical = value.translation.height
                    let horizontal = value.translation.width
                    if vertical > 20, abs(vertical) > abs(horizontal) {
                        stickToBottom = false
                    }
                }
            )
            .overlay(alignment: .bottom) {
                if !stickToBottom {
                    Button("Jump to latest") {
                        stickToBottom = true
                        proxy.scrollTo(endID, anchor: .bottomLeading)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.bottom, 12)
                }
            }
        }
    }
}
