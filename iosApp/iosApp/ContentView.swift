import LuviaShared
import SwiftUI

struct ContentView: View {
    @Bindable var model: AppModel
    @Namespace private var glass
    @State private var stage = "sessions"
    @State private var terminalReturn = "sessions"
    @State private var threadID: String?
    @State private var filter: SessionFilter = .all
    @State private var showSettings = false
    @State private var showNewSession = false
    @State private var cwd = ""
    @State private var confirmUnpair = false

    var body: some View {
        LuviaGlassContainer {
            ZStack {
                underlying
                    .opacity(stage == "terminal" ? 0.45 : 1)
                    .allowsHitTesting(stage != "terminal")
                if stage == "terminal" {
                    TerminalPageView(
                        model: model,
                        panes: panes,
                        selectedPaneID: model.uhp.selectedAgentID,
                        pendingDecisions: pending.count,
                        onBack: closeTerminal,
                        onSelectPane: { model.openTerminal(paneId: $0) },
                        glass: glass
                    )
                    .transition(.scale(scale: 0.9, anchor: .top).combined(with: .opacity))
                }
            }
        }
        .animation(.spring(response: 0.42, dampingFraction: 0.86), value: stage)
        .tint(ChatPalette.seed)
        .onAppear {
            model.connectHostsOnce()
            if model.hosts.isEmpty { stage = "pair" }
        }
        .onChange(of: model.hosts.map(\.id)) { _, ids in
            if ids.isEmpty, stage == "sessions" { stage = "pair" }
            if model.selectedHostID == nil || !ids.contains(model.selectedHostID ?? "") {
                if let first = model.hosts.first {
                    model.select(first)
                }
            }
        }
        .onChange(of: model.pendingOpenAgentID) { _, id in
            guard let id else { return }
            if let thread = allThreads.first(where: { $0.id == id || $0.paneId == id }) {
                open(thread)
                model.pendingOpenAgentID = nil
            }
        }
        .sheet(isPresented: $showSettings) { settings }
        .sheet(isPresented: $showNewSession) { newSession }
        .confirmationDialog("Unpair this computer?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) {
                guard let id = model.selectedHostID else { return }
                _Concurrency.Task { await model.unpair(id) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder
    private var underlying: some View {
        switch stage == "terminal" ? terminalReturn : stage {
        case "pair":
            PairingView(
                model: model,
                onPaired: { stage = "sessions" },
                onClose: model.hosts.isEmpty ? nil : { stage = "sessions" }
            )
        case "chat":
            if let thread = openThread {
                ChatView(
                    model: model,
                    thread: thread,
                    items: timeline(thread),
                    queueIndex: queueIndex(thread),
                    queueTotal: max(pending.count, thread.ask.map { Int($0.total) } ?? 1),
                    onBack: { stage = "sessions" },
                    onTerminal: { openTerminal(thread.paneId) },
                    glass: glass
                )
            } else {
                ContentUnavailableView("Session missing", systemImage: "bubble.left", description: Text("Back to the list."))
                    .onTapGesture { stage = "sessions" }
            }
        default:
            SessionListView(
                model: model,
                threads: visibleThreads,
                pending: pending,
                filter: filter,
                onFilter: { filter = $0 },
                onOpen: open,
                onAnswer: { thread, option in
                    model.uhpRegistry.answer(thread: thread, option: option)
                },
                onPair: { stage = "pair" },
                onNewSession: {
                    model.hostUhp()?.loadAcpAgents()
                    showNewSession = true
                },
                onTerminal: { openTerminal(openThread?.paneId) },
                onSettings: { showSettings = true },
                glass: glass
            )
        }
    }

    private var allThreads: [AgentThread] {
        guard let id = model.selectedHostID, let state = model.hostStates[id] else { return [] }
        let raw: [AgentThread] = KotlinLists.array(state.threads(hostId: id) as Any)
        return KotlinLists.array(SessionListKt.sortSessions(threads: raw) as Any)
    }

    private var visibleThreads: [AgentThread] {
        KotlinLists.array(SessionListKt.filterSessions(threads: allThreads, filter: filter) as Any)
    }

    private var pending: [AgentThread] {
        KotlinLists.array(SessionListKt.filterSessions(threads: allThreads, filter: .needsYou) as Any)
    }

    private var openThread: AgentThread? {
        allThreads.first { $0.id == threadID }
    }

    private var panes: [(id: String, title: String)] {
        guard let host = model.selectedHost else { return [] }
        return host.paneTerminalIds.keys.sorted().map { id in
            (id, host.agents.first { $0.id == id }?.name ?? id)
        }
    }

    private func timeline(_ thread: AgentThread) -> [TimelineItem] {
        KotlinLists.array(model.hostUhp()?.timeline(thread: thread) as Any)
    }

    private func queueIndex(_ thread: AgentThread) -> Int {
        if let index = pending.firstIndex(where: { $0.id == thread.id }) {
            return index + 1
        }
        if let ask = thread.ask {
            return Int(ask.index)
        }
        return 1
    }

    private func open(_ thread: AgentThread) {
        if let host = model.hosts.first(where: { $0.id == thread.hostId }) {
            model.select(host)
        }
        model.uhpRegistry.markViewed(thread: thread)
        if thread.kind == .acp {
            model.hostUhp()?.viewAcp()
        } else if let pane = thread.paneId {
            model.hostUhp()?.openAgent(paneId: pane)
        }
        threadID = thread.id
        stage = "chat"
    }

    private func openTerminal(_ paneId: String?) {
        if stage != "terminal" { terminalReturn = stage }
        model.openTerminal(paneId: paneId)
        stage = "terminal"
    }

    private func closeTerminal() {
        model.setTerminalVisible(false)
        stage = terminalReturn
    }

    private var settings: some View {
        NavigationStack {
            SettingsForm(model: model, onUnpair: { confirmUnpair = true })
        }
    }

    private var newSession: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Directory", text: $cwd)
                } footer: {
                    Text("Starts an ACP agent. Pane agents are still created on the computer. An empty directory uses the open workspace.")
                }
                let agents: [AcpAgentKind] = KotlinLists.array(model.hostStates[model.selectedHostID ?? ""]?.acp.agents as Any)
                if agents.isEmpty {
                    Text("No ACP agents reported yet.")
                }
                ForEach(agents, id: \.id) { agent in
                    Button(agent.available ? agent.name : "\(agent.name) unavailable") {
                        model.hostUhp()?.setLaunchAcpCwd(cwd: cwd)
                        model.hostUhp()?.setLaunchAcpAgent(id: agent.id)
                        model.hostUhp()?.launchAcp()
                        showNewSession = false
                    }
                    .disabled(!agent.available)
                }
            }
            .navigationTitle("New session")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { showNewSession = false }
                }
            }
        }
    }
}

private struct SettingsForm: View {
    @Bindable var model: AppModel
    var onUnpair: () -> Void
    @State private var alias = ""
    @State private var addresses = ""
    @State private var port = ""
    @State private var user = ""

    var body: some View {
        Form {
            if let host = model.selectedHost {
                Section(host.connection.rawValue) {
                    TextField("Name", text: $alias)
                    TextField("Addresses", text: $addresses, axis: .vertical)
                    TextField("SSH port", text: $port)
                    TextField("User", text: $user)
                    Button("Save connection") {
                        let id = host.id
                        let alias = alias
                        let addresses = addresses
                        let port = port
                        let user = user
                        _Concurrency.Task {
                            _ = await model.updateConnection(
                                hostID: id,
                                alias: alias,
                                hosts: addresses,
                                port: port,
                                username: user
                            )
                        }
                    }
                }
                Section {
                    Button("Refresh") { _Concurrency.Task { await model.refresh(host.id) } }
                    Button("Connect") { model.connect(host.id) }
                    Button("Disconnect") { model.disconnect(host.id) }
                    Toggle("Wake this phone", isOn: Binding(
                        get: { model.isPushEnabled },
                        set: { model.setWakeForApprovals($0) }
                    ))
                }
                Section {
                    Button("Unpair", role: .destructive, action: onUnpair)
                }
            }
        }
        .navigationTitle(model.selectedHost?.name ?? "Computer")
        .onAppear(perform: load)
    }

    private func load() {
        guard let host = model.selectedHost else { return }
        alias = host.name
        addresses = host.addresses.joined(separator: "\n")
        port = String(host.sshPort)
        user = host.username
    }
}
