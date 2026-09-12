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
                .toolbar {
                    if model.uhp.caps.agentSessions {
                        ToolbarItem(placement: .primaryAction) {
                            Button("Sessions") {
                                model.uhp.isSessionsPresented = true
                            }
                        }
                    }
                }
                .sheet(isPresented: $model.uhp.isSessionsPresented) {
                    AgentSessionsSheet(model: model)
                }
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
        .toolbar {
            if canMutate && uhp.caps.agentName {
                ToolbarItem(placement: .primaryAction) {
                    Button("Name") { model.beginNameAgent() }
                }
            }
            if canMutate && uhp.caps.agentFork {
                ToolbarItem(placement: .secondaryAction) {
                    Button("Fork") { model.beginForkAgent() }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if canMutate {
                composer
            }
        }
        .refreshable {
            await model.refreshOpenAgent()
        }
        .sheet(isPresented: $model.uhp.isNameAgentPresented) {
            NameAgentSheet(model: model)
        }
        .sheet(isPresented: $model.uhp.isForkAgentPresented) {
            ForkAgentSheet(model: model)
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
            ScrollView([.horizontal, .vertical]) {
                Text(
                    uhp.transcript.isEmpty
                        ? AttributedString("No Transcript yet.")
                        : ansiAttributedString(
                            uhp.transcript,
                            defaultForeground: .primary,
                            defaultBackground: Color(uiColor: .systemBackground)
                        )
                )
                    .font(.system(.footnote, design: .monospaced))
                    .fixedSize(horizontal: true, vertical: false)
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
    case yes
    case no
    case enter
    case esc
    case up
    case down
    case tab

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
                            VStack(alignment: .leading, spacing: 4) {
                                Text(session.agent)
                                    .font(.headline)
                                Text(session.sessionId)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                if !session.cwd.isEmpty {
                                    Text(session.cwd)
                                        .font(.system(.caption, design: .monospaced))
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
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
