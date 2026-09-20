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



struct AgentDetailView: View {
    @Bindable var model: AppModel
    let agentID: String

    private enum Surface: String, CaseIterable {
        case transcript = "Transcript"
        case terminal = "Terminal"
    }

    @State private var seenTranscript = ""
    @State private var highlightSuffix = ""
    @State private var highlightVisible = false
    @State private var surface: Surface = .transcript

    private var uhp: UhpSurfaceState { model.uhp }
    private var header: AgentHeaderState? { uhp.header }
    private var canMutate: Bool { uhp.isController && uhp.unconfirmed == nil }
    private var isBlocked: Bool { header?.isBlocked == true }
    private var prefersKeys: Bool { transcriptLooksLikeYesNo(uhp.transcript) }
    private var observer: Bool { !uhp.isController }

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
            Picker("Surface", selection: $surface) {
                ForEach(Surface.allCases, id: \.self) { item in
                    Text(item.rawValue).tag(item)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.bottom, 8)
            if surface == .transcript {
                transcriptBlock
            } else if let host = model.selectedHost {
                TerminalPane(
                    host: host,
                    text: model.terminalText,
                    status: model.terminalStatus,
                    holdsControl: model.holdsTerminalControl,
                    onSend: { text in _Concurrency.Task { await model.sendTerminal(text) } },
                    onSendKey: { key in _Concurrency.Task { await model.sendTerminalKey(key) } },
                    onRequestControl: { model.requestTerminalControl() }
                )
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text(header?.name ?? "Agent")
                        .font(.headline)
                        .foregroundStyle(DesignTokens.ink)
                    if let workspace = header?.workspace, !workspace.isEmpty {
                        Button(workspace) { model.showProjectReview() }
                            .font(.caption)
                            .foregroundStyle(DesignTokens.inkMuted)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    if canMutate && uhp.caps.agentName {
                        Button("Name") { model.beginNameAgent() }
                    }
                    if canMutate && uhp.caps.agentFork {
                        Button("Fork") { model.beginForkAgent() }
                    }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
                .disabled(!(canMutate && (uhp.caps.agentName || uhp.caps.agentFork)))
            }
        }
        .safeAreaInset(edge: .bottom) {
            if surface == .transcript {
                VStack(spacing: DesignTokens.Space.s) {
                    if isBlocked {
                        BlockedCard(
                            title: "Blocked — answer",
                            message: prefersKeys ? lastQuestion : "This Agent is waiting.",
                            observer: observer,
                            yesNo: prefersKeys && uhp.caps.agentPrompt,
                            onYes: { _Concurrency.Task { await model.promptAgent(target: agentID, text: "y") } },
                            onNo: { _Concurrency.Task { await model.promptAgent(target: agentID, text: "n") } },
                            onEnter: { _Concurrency.Task { await model.sendAgentKeys(QuickAgentKey.enter.agentKeys) } },
                            onEsc: { _Concurrency.Task { await model.sendAgentKeys(QuickAgentKey.esc.agentKeys) } }
                        )
                        .padding(.horizontal, DesignTokens.Space.m)
                    }
                    if uhp.caps.agentPrompt {
                        composer
                    }
                }
                .padding(.bottom, DesignTokens.Space.s)
            }
        }
        .sheet(isPresented: $model.uhp.isNameAgentPresented) {
            NameAgentSheet(model: model)
        }
        .sheet(isPresented: $model.uhp.isForkAgentPresented) {
            ForkAgentSheet(model: model)
        }
        .onChange(of: uhp.transcript) { _, newValue in
            noteNewTranscript(newValue)
        }
        .onChange(of: surface) { _, newValue in
            model.setTerminalVisible(newValue == .terminal)
        }
        .onAppear {
            seenTranscript = uhp.transcript
            model.setTerminalVisible(surface == .terminal)
        }
        .onDisappear {
            model.setTerminalVisible(false)
            model.closeOpenAgent()
        }
    }

    private var lastQuestion: String {
        uhp.transcript
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .last { !$0.isEmpty } ?? "This Agent is waiting."
    }

    @ViewBuilder
    private var headerBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                StatusPill.agent(header.map { AgentStatusKind(rawValue: $0.status.lowercased()) ?? .unknown } ?? .unknown)
                if let branch = header?.branch, !branch.isEmpty {
                    Text(branch)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(1)
                }
                if let usage = header?.missionUsage, !usage.isEmpty {
                    Text(usage)
                        .font(.caption)
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            if let cwd = header?.cwd, !cwd.isEmpty {
                Text(cwd)
                    .font(DesignTokens.Typography.mono)
                    .foregroundStyle(DesignTokens.inkMuted)
                    .textSelection(.enabled)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var transcriptBlock: some View {
        JumpToLatestScroll(token: uhp.transcript, wrap: true) {
            VStack(alignment: .leading, spacing: 0) {
                if uhp.transcript.isEmpty {
                    Text("No Transcript yet.")
                        .font(DesignTokens.Typography.mono)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(transcriptSegments(text: uhp.transcript).enumerated()), id: \.offset) { _, segment in
                        switch onEnum(of: segment) {
                        case .text(let value):
                            transcriptText(value.text)
                        case .rule:
                            Divider()
                                .padding(.vertical, 8)
                        case .gap:
                            Color.clear.frame(height: 12)
                        }
                    }
                }
            }
            .padding()
        }
        .refreshable {
            await model.refreshOpenAgent()
        }
    }

    @ViewBuilder
    private func transcriptText(_ text: String) -> some View {
        if highlightVisible, !highlightSuffix.isEmpty, text.hasSuffix(highlightSuffix) {
            let stable = String(text.dropLast(highlightSuffix.count))
            VStack(alignment: .leading, spacing: 0) {
                if !stable.isEmpty {
                    ansiLine(stable)
                }
                ansiLine(highlightSuffix)
                    .background(DesignTokens.linkStale.opacity(0.28))
            }
        } else {
            ansiLine(text)
        }
    }

    private func ansiLine(_ text: String) -> some View {
        Text(
            ansiAttributedString(
                text,
                defaultForeground: DesignTokens.ink,
                defaultBackground: DesignTokens.canvas
            )
        )
        .font(DesignTokens.Typography.mono)
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    private var composer: some View {
        let canSend = !uhp.composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !uhp.isSending
            && canMutate
            && !observer
        return HStack(alignment: .center, spacing: 10) {
            TextField("Agent prompt", text: $model.uhp.composerText, axis: .vertical)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .lineLimit(1...5)
                .disabled(!canMutate || observer || uhp.unconfirmed != nil)
            Button {
                _Concurrency.Task { await model.sendAgentPrompt() }
            } label: {
                Image(systemName: "arrow.up")
                    .font(.body.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(canSend ? DesignTokens.accent : Color.secondary.opacity(0.35), in: Circle())
            }
            .disabled(!canSend)
            .buttonStyle(.plain)
            .accessibilityLabel("Send")
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, DesignTokens.Space.m)
    }

    private func noteNewTranscript(_ newValue: String) {
        let previous = seenTranscript
        seenTranscript = newValue
        guard !previous.isEmpty, newValue.hasPrefix(previous), newValue.count > previous.count else {
            highlightVisible = false
            highlightSuffix = ""
            return
        }
        highlightSuffix = String(newValue.dropFirst(previous.count))
        highlightVisible = true
        _Concurrency.Task { @MainActor in
            try? await _Concurrency.Task.sleep(for: .seconds(1.6))
            highlightVisible = false
        }
    }
}

private func transcriptLooksLikeYesNo(_ text: String) -> Bool {
    let tail = text.split(whereSeparator: \.isNewline).suffix(12).joined(separator: "\n").lowercased()
    if tail.contains("y/n") || tail.contains("yes/no") { return true }
    if tail.contains("(y)") && tail.contains("(n)") { return true }
    return false
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

#Preview("Agent detail Blocked") {
    AgentDetailPreview()
}

private struct AgentDetailPreview: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                StatusPill.agent(.blocked)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                ScrollView {
                    Text("Approve this change? (y/n)")
                        .font(DesignTokens.Typography.mono)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
            }
            .navigationTitle("Codex")
            .safeAreaInset(edge: .bottom) {
                BlockedCard(
                    title: "Blocked — answer",
                    message: "Approve this change? (y/n)",
                    yesNo: true
                )
                .padding(.horizontal, DesignTokens.Space.m)
                .padding(.bottom, DesignTokens.Space.s)
            }
        }
    }
}
