import SwiftUI
import LuviaShared

struct AgentsSectionView: View {
    @Bindable var model: AppModel
    let host: HostViewState
    @Binding var query: String

    private var hasContent: Bool {
        model.hasLiveSession || !model.uhp.agentEntries.isEmpty || host.hasCachedContent
    }

    var body: some View {
        Group {
            if hasContent || host.connection == .connecting || host.connection == .stale {
                if !hasContent && host.connection == .connecting {
                    EmptyState(title: "Connecting…", message: "Loading this Host.", systemImage: "bolt.horizontal.circle")
                } else if !hasContent {
                    EmptyState(
                        title: "This Host has not connected yet.",
                        message: "The last snapshot will appear here after the first live session.",
                        systemImage: "bolt.horizontal.circle"
                    )
                } else {
                    AgentsListView(model: model, host: host, query: $query)
                        .modifier(
                            ConditionalSearchable(
                                text: $query,
                                enabled: model.selectedSection == .agents,
                                prompt: "Search"
                            )
                        )
                        .toolbar {
                            if model.uhp.snapshot?.capabilities.acpSession == true {
                                ToolbarItem(placement: .topBarTrailing) {
                                    Button {
                                        model.beginLaunchAcp()
                                    } label: {
                                        Image(systemName: "plus")
                                    }
                                    .accessibilityLabel("New agent")
                                }
                            }
                        }
                        .sheet(isPresented: model.acpLaunchPresented) {
                            AcpLaunchSheet(model: model)
                        }
                }
            } else {
                EmptyState(
                    title: "This Host has not connected yet.",
                    message: "The last snapshot will appear here after the first live session.",
                    systemImage: "bolt.horizontal.circle"
                )
            }
        }
        .task {
            if model.uhp.caps.agentSessions {
                await model.loadAgentSessions()
            }
        }
    }
}

struct AgentsListView: View {
    @Bindable var model: AppModel
    let host: HostViewState
    @Binding var query: String

    private var entries: [AgentListItem] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let all = model.uhp.agentEntries
        guard !trimmed.isEmpty else { return all }
        return all.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed)
                || ($0.projectLabel?.localizedCaseInsensitiveContains(trimmed) ?? false)
                || ($0.branch?.localizedCaseInsensitiveContains(trimmed) ?? false)
        }
    }

    private var grouped: [(title: String, items: [AgentListItem])] {
        let order: [(AgentStatusKind, String)] = [
            (.blocked, "Waiting"),
            (.working, "Working"),
            (.idle, "Idle"),
            (.unknown, "Unknown"),
            (.done, "Done"),
        ]
        return order.compactMap { kind, title in
            let items = entries.filter { $0.statusKind == kind }
            guard !items.isEmpty else { return nil }
            return (title, items)
        }
    }

    private var canLaunchAcp: Bool {
        model.uhp.snapshot?.capabilities.acpSession == true
    }

    var body: some View {
        Group {
            if model.uhp.agentEntries.isEmpty && model.uhp.agentSessions.isEmpty {
                if canLaunchAcp {
                    LaunchAgentCard { model.beginLaunchAcp() }
                } else {
                    EmptyState(
                        title: "No Agents",
                        message: "Launch one, or start one in Luvus.",
                        systemImage: "person.2"
                    )
                }
            } else {
                agentList
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let errorMessage = model.uhp.errorMessage, !errorMessage.isEmpty {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
        }
        .refreshable {
            await model.loadAgents()
            if model.uhp.caps.agentSessions {
                await model.loadAgentSessions()
            }
        }
    }

    private var agentList: some View {
        List {
            if model.uhp.attentionCount > 0 {
                Section {
                    AttentionBanner(count: model.uhp.attentionCount) {
                        openWaiting()
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            } else {
                Section {
                    MissionStrip(
                        working: host.workingAgents,
                        blocked: host.blockedAgents,
                        done: host.completedAgents
                    )
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
            }
            ForEach(grouped, id: \.title) { group in
                Section(group.title) {
                    ForEach(group.items) { item in
                        if item.isAcp {
                            Button {
                                model.viewAcp()
                            } label: {
                                AgentRow(item: item)
                            }
                            .buttonStyle(.plain)
                        } else {
                            NavigationLink(value: item.paneId ?? item.id) {
                                AgentRow(item: item)
                            }
                        }
                    }
                }
            }
            if !model.uhp.agentSessions.isEmpty {
                Section("Resumable") {
                    ForEach(model.uhp.agentSessions) { session in
                        HStack(alignment: .firstTextBaseline) {
                            ResumableSessionLabels(session: session)
                            Spacer()
                            if model.uhp.allowsMutation && model.uhp.caps.agentResume {
                                Button("Resume") {
                                    _Concurrency.Task { await model.resumeHostAgent(session.sessionId) }
                                }
                                .disabled(model.uhp.isSending)
                            }
                        }
                    }
                }
            }
        }
    }

    private func openWaiting() {
        guard let first = model.uhp.agentEntries.first(where: \.isWaiting) else { return }
        if first.isAcp {
            model.viewAcp()
        } else {
            model.pendingOpenAgentID = first.paneId ?? first.id
        }
    }
}

struct LaunchAgentCard: View {
    let onLaunch: () -> Void

    var body: some View {
        EmptyState(
            title: "No Agents",
            message: "Launch one, or start one in Luvus.",
            systemImage: "person.2",
            actionTitle: "New agent",
            action: onLaunch
        )
    }
}



struct StatusChip: View {
    let status: String
    var isBlocked: Bool

    var body: some View {
        Text(status)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(isBlocked ? Color.white : DesignTokens.inkMuted)
            .background(
                isBlocked ? DesignTokens.accent : DesignTokens.inkMuted.opacity(0.16),
                in: Capsule()
            )
            .accessibilityLabel(isBlocked ? "Blocked" : status)
    }
}


struct AgentSessionsSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pendingResume: AgentSessionItem?

    var body: some View {
        NavigationStack {
            Group {
                if !model.uhp.caps.agentSessions {
                    ContentUnavailableView(
                        "Sessions",
                        systemImage: "clock.arrow.circlepath",
                        description: Text("This Host does not expose Agent sessions.")
                    )
                } else if model.uhp.agentSessions.isEmpty {
                    ContentUnavailableView(
                        "Sessions",
                        systemImage: "clock.arrow.circlepath",
                        description: Text("No resumable Agent sessions.")
                    )
                } else {
                    List(model.uhp.agentSessions) { session in
                        HStack(alignment: .firstTextBaseline) {
                            ResumableSessionLabels(session: session)
                            Spacer()
                            if model.uhp.allowsMutation && model.uhp.caps.agentResume {
                                Button("Resume") { pendingResume = session }
                                    .disabled(model.uhp.isSending)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Sessions")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                SurfaceStatusBanner(model: model)
            }
            .refreshable { await model.loadAgentSessions() }
            .confirmationDialog(
                "Resume this Agent session?",
                isPresented: Binding(
                    get: { pendingResume != nil },
                    set: { if !$0 { pendingResume = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("Resume") {
                    if let id = pendingResume?.sessionId {
                        pendingResume = nil
                        _Concurrency.Task { await model.resumeHostAgent(id) }
                    }
                }
                Button("Cancel", role: .cancel) { pendingResume = nil }
            } message: {
                Text(pendingResume?.agent ?? "The Host will resume this Agent session.")
            }
        }
        .task { await model.loadAgentSessions() }
    }
}

private struct ResumableSessionLabels: View {
    let session: AgentSessionItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(primary)
                .font(.headline)
            if let secondary {
                Text(secondary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if !session.cwd.isEmpty {
                Text(session.cwd)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var cwdLeaf: String? {
        guard !session.cwd.isEmpty else { return nil }
        let leaf = URL(fileURLWithPath: session.cwd).lastPathComponent
        return leaf.isEmpty ? session.cwd : leaf
    }

    private var primary: String {
        cwdLeaf ?? (session.agent.isEmpty ? "Session" : session.agent)
    }

    private var secondary: String? {
        guard cwdLeaf != nil else { return nil }
        let agent = session.agent.trimmingCharacters(in: .whitespacesAndNewlines)
        return agent.isEmpty ? nil : agent
    }

    private var accessibilityText: String {
        [primary, secondary, session.cwd.isEmpty ? nil : session.cwd]
            .compactMap { $0 }
            .joined(separator: ", ")
    }
}

struct NameAgentSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $model.uhp.nameAgentText)
            }
            .navigationTitle("Name Agent")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        _Concurrency.Task { await model.nameOpenAgent() }
                    }
                    .disabled(
                        model.uhp.nameAgentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || !model.uhp.allowsMutation
                    )
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct ForkAgentSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name (optional)", text: $model.uhp.forkAgentName)
            }
            .navigationTitle("Fork Agent")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fork") {
                        _Concurrency.Task { await model.forkOpenAgent() }
                    }
                    .disabled(!model.uhp.allowsMutation)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

#Preview("Agents list") {
    NavigationStack {
        List {
            AgentRow(
                item: AgentListItem(
                    id: "7",
                    name: "Codex",
                    statusKind: .blocked,
                    isAcp: false,
                    projectLabel: "luvia",
                    branch: "main",
                    lastLine: "Approve this change? (y/n)"
                )
            )
            AgentRow(
                item: AgentListItem(
                    id: "8",
                    name: "Grok",
                    statusKind: .working,
                    isAcp: true,
                    projectLabel: "luvia",
                    branch: "feature/uhp"
                )
            )
        }
        .navigationTitle("Agents")
    }
}

