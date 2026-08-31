import SwiftUI

struct HostDetailView: View {
    let host: HostViewState
    @Binding var section: HostSection

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
                    StatusPlaceholder(title: "Agents", symbol: "person.2", message: "Connect to load agent state.")
                case .tasks:
                    StatusPlaceholder(title: "Tasks", symbol: "checklist", message: "Connect to load orchestration tasks.")
                case .terminal:
                    TerminalPlaceholder()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationTitle(host.name)
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

private struct StatusPlaceholder: View {
    let title: String
    let symbol: String
    let message: String

    var body: some View {
        ContentUnavailableView(title, systemImage: symbol, description: Text(message))
    }
}

private struct TerminalPlaceholder: View {
    var body: some View {
        ContentUnavailableView(
            "Terminal unavailable",
            systemImage: "terminal",
            description: Text("Select a live pane to observe or request control.")
        )
    }
}
