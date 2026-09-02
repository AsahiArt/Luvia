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
            }
        }
    }
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
                    .foregroundStyle(.secondary)
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
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    Text(text)
                        .font(.system(.footnote, design: .monospaced))
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
                    Button("Send") {
                        let payload = input
                        input = ""
                        onSend(payload)
                    }
                    .disabled(input.isEmpty || host.connection != .live)
                }
                .padding()
            }
        }
    }
}
