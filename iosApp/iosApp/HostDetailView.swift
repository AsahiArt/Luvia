import SwiftUI

struct HostDetailView: View {
    let host: HostViewState
    @Binding var section: HostSection
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
                case .overview:
                    Overview(host: host)
                case .agents:
                    AgentList(host: host)
                case .tasks:
                    TaskList(host: host)
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

private struct Overview: View {
    let host: HostViewState

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                Metric(title: "Working", value: host.workingAgents, color: .blue)
                Metric(title: "Blocked", value: host.blockedAgents, color: .orange)
                Metric(title: "Done", value: host.completedAgents, color: .green)
            }
            .padding()

            if let failure = host.failureMessage {
                GroupBox("Connection") {
                    Text(failure)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal)
            }

            if let task = host.activeTask {
                GroupBox("Current task") {
                    Text(task).frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal)
            }
        }
    }
}

private struct Metric: View {
    let title: String
    let value: Int
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).foregroundStyle(.secondary)
            Text(value, format: .number).font(.system(.largeTitle, design: .rounded, weight: .semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct AgentList: View {
    let host: HostViewState

    var body: some View {
        if host.agents.isEmpty {
            ContentUnavailableView(
                "Agents",
                systemImage: "person.2",
                description: Text(host.connection == .live ? "No agents in this session." : "Connect to load agent state.")
            )
        } else {
            List(host.agents) { agent in
                VStack(alignment: .leading, spacing: 4) {
                    Text(agent.name).font(.headline)
                    Text(agent.status)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let detail = agent.detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
        }
    }
}

private struct TaskList: View {
    let host: HostViewState

    var body: some View {
        if host.tasks.isEmpty {
            ContentUnavailableView(
                "Tasks",
                systemImage: "checklist",
                description: Text(host.connection == .live ? "No orchestration tasks." : "Connect to load orchestration tasks.")
            )
        } else {
            List(host.tasks) { task in
                VStack(alignment: .leading, spacing: 4) {
                    Text(task.title).font(.headline)
                    Text(task.status)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
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
