import SwiftUI
import LuviaShared

struct AgentsSectionView: View {
    @Bindable var model: AppModel
    let host: HostViewState

    var body: some View {
        if !model.hasLiveSession {
            ContentUnavailableView(
                "Connect to this host",
                systemImage: "bolt.horizontal.circle",
                description: Text("A live session is required to load Agents, Review, and Tasks.")
            )
        } else {
            NavigationStack {
                AgentsListView(
                    agents: model.uhp.agents,
                    errorMessage: model.uhp.errorMessage,
                    onRefresh: { await model.loadAgents() }
                )
                .navigationDestination(for: String.self) { id in
                    AgentDetailView(model: model, agentID: id)
                        .task { await model.openAgent(id) }
                }
            }
        }
    }
}

struct AgentsListView: View {
    let agents: [AgentViewState]
    var errorMessage: String?
    var onRefresh: (() async -> Void)?

    var body: some View {
        Group {
            if agents.isEmpty {
                ContentUnavailableView(
                    "Agents",
                    systemImage: "person.2",
                    description: Text("No agents in this session.")
                )
            } else {
                List(agents) { agent in
                    NavigationLink(value: agent.id) {
                        AgentRowView(agent: agent)
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let errorMessage, !errorMessage.isEmpty {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
        }
        .refreshable {
            await onRefresh?()
        }
    }
}

struct AgentRowView: View {
    let agent: AgentViewState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(agent.name)
                    .font(.headline)
                    .lineLimit(2)
                Spacer(minLength: 8)
                StatusChip(status: agent.status, isBlocked: agent.isBlocked)
            }
            let subtitle = [agent.kind, agent.workspace].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if let branch = agent.branch, !branch.isEmpty {
                Text(branch)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }
}

struct AgentDetailView: View {
    @Bindable var model: AppModel
    let agentID: String

    @State private var pendingKey: QuickAgentKey?
    @State private var confirmPrompt = false

    private var uhp: UhpSurfaceState { model.uhp }
    private var header: AgentHeaderState? { uhp.header }
    private var canMutate: Bool { uhp.isController && uhp.unconfirmed == nil }
    private var isBlocked: Bool { header?.isBlocked == true }

    var body: some View {
        VStack(spacing: 0) {
            headerBlock
            if let unconfirmed = uhp.unconfirmed {
                UnconfirmedBanner(action: unconfirmed) {
                    _Concurrency.Task { await model.checkUnconfirmed() }
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
            }
            if let error = uhp.errorMessage, !error.isEmpty {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
            transcriptBlock
        }
        .navigationTitle(header?.name ?? "Agent")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            if canMutate {
                composer
            }
        }
        .refreshable {
            await model.refreshOpenAgent()
        }
        .confirmationDialog(
            confirmTitle,
            isPresented: Binding(
                get: { confirmPrompt || pendingKey != nil },
                set: { if !$0 { confirmPrompt = false; pendingKey = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Send") {
                if confirmPrompt {
                    confirmPrompt = false
                    _Concurrency.Task { await model.sendAgentPrompt() }
                } else if let pendingKey {
                    let key = pendingKey
                    self.pendingKey = nil
                    _Concurrency.Task { await perform(key) }
                }
            }
            Button("Cancel", role: .cancel) {
                confirmPrompt = false
                pendingKey = nil
            }
        } message: {
            Text("The Agent is Blocked and will receive this answer.")
        }
    }

    private var confirmTitle: String {
        if confirmPrompt { return "Send Agent prompt?" }
        if let pendingKey { return "Send \(pendingKey.title)?" }
        return "Send?"
    }

    @ViewBuilder
    private var headerBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                StatusChip(status: header?.status ?? "Unknown", isBlocked: isBlocked)
                if let kind = header?.kind, !kind.isEmpty {
                    Text(kind)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            if let workspace = header?.workspace, !workspace.isEmpty {
                labeled("Workspace", workspace)
            }
            if let branch = header?.branch, !branch.isEmpty {
                labeled("Branch", branch)
            }
            if let cwd = header?.cwd, !cwd.isEmpty {
                labeled("cwd", cwd, mono: true)
            }
            if let usage = header?.missionUsage, !usage.isEmpty {
                labeled("Mission", usage)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func labeled(_ title: String, _ value: String, mono: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(mono ? .system(.footnote, design: .monospaced) : .subheadline)
                .textSelection(.enabled)
        }
    }

    private var transcriptBlock: some View {
        ScrollViewReader { proxy in
            ScrollView {
                Text(uhp.transcript.isEmpty ? "No Transcript yet." : uhp.transcript)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding()
                Color.clear.frame(height: 1).id("transcript-end")
            }
            .onChange(of: uhp.transcript) { _, _ in
                proxy.scrollTo("transcript-end", anchor: .bottom)
            }
            .onAppear {
                proxy.scrollTo("transcript-end", anchor: .bottom)
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 10) {
            if uhp.caps.agentKeys {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(QuickAgentKey.allCases) { key in
                            Button(key.title) { request(key) }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                                .disabled(uhp.isSending)
                        }
                    }
                }
            }
            if uhp.caps.agentPrompt {
                HStack(spacing: 8) {
                    TextField("Agent prompt", text: $model.uhp.composerText, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(1...5)
                    Button("Send") {
                        if isBlocked {
                            confirmPrompt = true
                        } else {
                            _Concurrency.Task { await model.sendAgentPrompt() }
                        }
                    }
                    .disabled(uhp.composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || uhp.isSending)
                }
            }
        }
        .padding()
        .background(.bar)
    }

    private func request(_ key: QuickAgentKey) {
        if isBlocked {
            pendingKey = key
        } else {
            _Concurrency.Task { await perform(key) }
        }
    }

    private func perform(_ key: QuickAgentKey) async {
        switch key {
        case .yes, .no:
            await model.promptAgent(target: agentID, text: key == .yes ? "y" : "n")
        default:
            await model.sendAgentKeys(key.agentKeys)
        }
    }
}
enum QuickAgentKey: String, CaseIterable, Identifiable {
    case enter
    case esc
    case up
    case down
    case tab
    case yes
    case no

    var id: String { rawValue }

    var title: String {
        switch self {
        case .enter: "Enter"
        case .esc: "Esc"
        case .up: "Up"
        case .down: "Down"
        case .tab: "Tab"
        case .yes: "y+Enter"
        case .no: "n+Enter"
        }
    }

    var agentKeys: [AgentKey] {
        switch self {
        case .enter: [AgentKey.ENTER.shared]
        case .esc: [AgentKey.ESC.shared]
        case .up: [AgentKey.UP.shared]
        case .down: [AgentKey.DOWN.shared]
        case .tab: [AgentKey.TAB.shared]
        case .yes: [AgentKey.Char(c: 0x79), AgentKey.ENTER.shared]
        case .no: [AgentKey.Char(c: 0x6E), AgentKey.ENTER.shared]
        }
    }
}

struct StatusChip: View {
    let status: String
    var isBlocked: Bool

    var body: some View {
        Text(status)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(isBlocked ? Color.white : Color.primary)
            .background(isBlocked ? Color.orange : Color.secondary.opacity(0.16), in: Capsule())
            .overlay {
                if isBlocked {
                    Capsule().strokeBorder(Color.red.opacity(0.85))
                }
            }
            .accessibilityLabel(isBlocked ? "Blocked" : status)
    }
}

struct UnconfirmedBanner: View {
    let action: UnconfirmedAction
    let onCheck: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(action.title)
                    .font(.headline)
                Text(action.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Button("Check", action: onCheck)
                .buttonStyle(.bordered)
        }
        .padding(12)
        .background(Color.orange.opacity(0.16), in: RoundedRectangle(cornerRadius: 12))
    }
}

#Preview("Agents list") {
    NavigationStack {
        AgentsListView(
            agents: [
                AgentViewState(
                    id: "7",
                    name: "Codex",
                    status: "Blocked",
                    detail: "/src",
                    statusKind: .blocked,
                    kind: "codex",
                    workspace: "luvia",
                    branch: "main",
                    cwd: "/Users/dev/luvia"
                ),
                AgentViewState(
                    id: "8",
                    name: "Grok",
                    status: "Working",
                    detail: "/src",
                    statusKind: .working,
                    kind: "grok",
                    workspace: "luvia",
                    branch: "feature/uhp",
                    cwd: "/Users/dev/luvia"
                ),
            ]
        )
    }
}

#Preview("Agent detail Blocked") {
    AgentDetailPreview()
}

private struct AgentDetailPreview: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    StatusChip(status: "Blocked", isBlocked: true)
                    Text("Workspace")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("luvia")
                    Text("Branch")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("main")
                    Text("cwd")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("/Users/dev/luvia")
                        .font(.system(.footnote, design: .monospaced))
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                ScrollView {
                    Text("Approve this change? (y/n)")
                        .font(.system(.footnote, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
            }
            .navigationTitle("Codex")
            .safeAreaInset(edge: .bottom) {
                HStack {
                    TextField("Agent prompt", text: .constant(""))
                    Button("Send") {}
                }
                .padding()
                .background(.bar)
            }
        }
    }
}
