import SwiftUI
import UIKit
import LuviaShared

struct AcpLaunchSheet: View {
    @Bindable var model: AppModel

    private var acp: AcpState? { model.acpState }
    private var agents: [AcpAgentKind] { KotlinLists.array(acp?.agents as Any) }

    private var canLaunch: Bool {
        guard let acp else { return false }
        let cwd = acp.launchCwd.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cwd.isEmpty, let id = acp.launchAgentId, !id.isEmpty else { return false }
        return agents.contains { $0.id == id && $0.available }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.Space.l) {
                    VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                        Text("Launch an agent")
                            .font(DesignTokens.Typography.title)
                            .foregroundStyle(DesignTokens.ink)
                        Text("Runs on the host over ACP. Approvals arrive here as buttons.")
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.inkMuted)
                    }

                    if acp?.agentsLoading == true && agents.isEmpty {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, DesignTokens.Space.l)
                    } else if agents.isEmpty {
                        Text("No agents on this host.")
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.inkMuted)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(agents, id: \.id) { agent in
                                agentRow(agent)
                                if agent.id != agents.last?.id {
                                    Divider()
                                }
                            }
                        }
                        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
                    }

                    VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                        Text("Directory")
                            .font(.caption)
                            .foregroundStyle(DesignTokens.inkMuted)
                        TextField(
                            "Working directory",
                            text: Binding(
                                get: { acp?.launchCwd ?? "" },
                                set: { model.setLaunchAcpCwd($0) }
                            )
                        )
                        .font(.system(.body, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(DesignTokens.Space.m)
                        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
                    }

                    Button("Launch") {
                        model.launchAcp()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(DesignTokens.accent)
                    .disabled(!canLaunch || acp?.agentsLoading == true)
                    .frame(maxWidth: .infinity)
                }
                .padding(DesignTokens.Space.l)
            }
            .background(DesignTokens.canvas)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.setShowLaunchAcp(false) }
                }
            }
        }
        .task {
            model.loadAcpAgents()
        }
    }

    private func agentRow(_ agent: AcpAgentKind) -> some View {
        let selected = acp?.launchAgentId == agent.id
        return Button {
            if agent.available {
                model.setLaunchAcpAgent(agent.id)
            }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Space.m) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(agent.name)
                        .font(.headline)
                        .foregroundStyle(agent.available ? DesignTokens.ink : DesignTokens.inkMuted)
                    Text(agent.command)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(2)
                }
                Spacer(minLength: DesignTokens.Space.s)
                if !agent.available {
                    Text("Not installed")
                        .font(.caption)
                        .foregroundStyle(DesignTokens.inkMuted)
                } else if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(DesignTokens.accent)
                        .accessibilityLabel("Selected")
                }
            }
            .padding(DesignTokens.Space.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!agent.available)
        .opacity(agent.available ? 1 : 0.55)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct AcpThoughtBlock: View {
    let text: String
    @State private var expanded = false

    var body: some View {
        DisclosureGroup("Thinking", isExpanded: $expanded) {
            Text(text)
                .font(.body.italic())
                .foregroundStyle(DesignTokens.inkMuted)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.subheadline)
        .foregroundStyle(DesignTokens.inkMuted)
        .tint(DesignTokens.inkMuted)
    }
}

struct AcpToolRow: View {
    let call: AcpToolCall

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Space.s) {
            Image(systemName: toolSymbol(call.kind))
                .foregroundStyle(DesignTokens.inkMuted)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: DesignTokens.Space.s) {
                    Text(call.title)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.ink)
                        .lineLimit(2)
                    Circle()
                        .fill(statusColor(call.status))
                        .frame(width: 8, height: 8)
                        .accessibilityLabel(statusLabel(call.status))
                }
                if let summary = call.summary, !summary.isEmpty {
                    Text(summary)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(DesignTokens.inkMuted)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    private func toolSymbol(_ kind: String?) -> String {
        switch kind?.lowercased() {
        case "read": "doc.text"
        case "edit": "pencil"
        case "execute": "terminal"
        case "search": "magnifyingglass"
        case "fetch": "arrow.down.circle"
        case "think": "brain"
        default: "wrench"
        }
    }

    private func statusColor(_ status: AcpToolStatus) -> Color {
        switch status {
        case .pending: DesignTokens.inkMuted
        case .inProgress: DesignTokens.connecting
        case .completed: DesignTokens.live
        case .failed: Color.red
        default: DesignTokens.inkMuted
        }
    }

    private func statusLabel(_ status: AcpToolStatus) -> String {
        switch status {
        case .pending: "Pending"
        case .inProgress: "In progress"
        case .completed: "Completed"
        case .failed: "Failed"
        default: "Unknown"
        }
    }
}

struct AcpTurnDivider: View {
    let reason: AcpStopReason

    var body: some View {
        HStack(spacing: DesignTokens.Space.s) {
            Rectangle()
                .fill(DesignTokens.inkMuted.opacity(0.3))
                .frame(height: 1)
            Text(label)
                .font(.caption)
                .foregroundStyle(DesignTokens.inkMuted)
                .fixedSize()
            Rectangle()
                .fill(DesignTokens.inkMuted.opacity(0.3))
                .frame(height: 1)
        }
        .padding(.vertical, DesignTokens.Space.xs)
    }

    private var label: String {
        switch reason {
        case .endTurn: "Turn ended"
        case .maxTokens: "Max tokens"
        case .maxTurnRequests: "Max turn requests"
        case .refusal: "Refusal"
        case .cancelled: "Cancelled"
        default: "Turn ended"
        }
    }
}

struct AcpPlanCard: View {
    let entries: [AcpPlanEntry]
    @State private var expanded = true

    var body: some View {
        DisclosureGroup("Plan", isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                    HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Space.s) {
                        Image(systemName: planSymbol(entry.status))
                            .foregroundStyle(planColor(entry.status))
                        Text(entry.content)
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.ink)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding(.top, DesignTokens.Space.s)
        }
        .font(DesignTokens.Typography.heading)
        .foregroundStyle(DesignTokens.ink)
        .padding(DesignTokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass()
    }

    private func planSymbol(_ status: AcpPlanStatus) -> String {
        switch status {
        case .completed: "checkmark.circle"
        case .inProgress: "circle.dotted"
        default: "circle"
        }
    }

    private func planColor(_ status: AcpPlanStatus) -> Color {
        switch status {
        case .completed: DesignTokens.live
        case .inProgress: DesignTokens.connecting
        default: DesignTokens.inkMuted
        }
    }
}

struct AcpExitBanner: View {
    let message: String?
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
            if let message, !message.isEmpty {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.ink)
            } else {
                Text("The agent has exited.")
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.ink)
            }
            Button("Close", action: onClose)
                .buttonStyle(.bordered)
        }
        .padding(DesignTokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            DesignTokens.stale.opacity(0.16),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous)
        )
    }
}

struct AcpStreamingCursor: View {
    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.53)) { timeline in
            let on = Int(timeline.date.timeIntervalSinceReferenceDate / 0.53) % 2 == 0
            RoundedRectangle(cornerRadius: 1, style: .continuous)
                .fill(DesignTokens.ink)
                .frame(width: 7, height: 16)
                .opacity(on ? 1 : 0.12)
                .accessibilityHidden(true)
        }
    }
}
