import SwiftUI

struct HostDetailView: View {
    let host: HostViewState
    @Binding var section: HostSection
    @Bindable var model: AppModel
    var terminalText: String
    var terminalStatus: String?
    var onConnect: () -> Void
    var onDisconnect: () -> Void
    var onRefresh: () -> Void
    var onSendTerminal: (String) -> Void

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
                        onSend: onSendTerminal
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

private struct TerminalPane: View {
    let host: HostViewState
    let text: String
    let status: String?
    var onSend: (String) -> Void

    @State private var input = ""

    var body: some View {
        VStack(spacing: 0) {
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
                ScrollView([.horizontal, .vertical]) {
                    Text(
                        ansiAttributedString(
                            text,
                            defaultForeground: TerminalChrome.foreground,
                            defaultBackground: TerminalChrome.background
                        )
                    )
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(TerminalChrome.foreground)
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding()
                }
            }
            if host.isController {
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
                    .disabled(input.isEmpty || host.connection != .live)
                }
                .padding()
            }
        }
        .background(TerminalChrome.background)
        .colorScheme(.dark)
    }
}
