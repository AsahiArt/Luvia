import LuviaShared
import SwiftUI

struct ContentView: View {
    @Bindable var model: AppModel
    @Namespace private var glass
    @State private var stage = "sessions"
    @State private var terminalReturn = "sessions"
    @State private var threadID: String?
    @State private var filterRevision = 0
    @State private var filtersHighlighted = false
    @State private var highlightTask: _Concurrency.Task<Void, Never>?
    @State private var restoredHosts: Set<String> = []
    @State private var restoreTask: _Concurrency.Task<Void, Never>?
    @State private var showSettings = false
    @State private var showNewSession = false
    @State private var cwd = ""
    @State private var confirmUnpair = false
    @State private var userOpenedPairing = false
    #if DEBUG
    @State private var fixturePaneID = ""
    #endif
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                        selectedPaneID: shownPaneID,
                        pendingDecisions: decisionCount,
                        waitingTitle: waitingTitle,
                        waitingDetail: waitingDetailText,
                        canAskFix: canHandFix,
                        textOverride: terminalTextOverride,
                        fixtureOfflineChrome: usingTerminalFixture,
                        onBack: closeTerminal,
                        onSelectPane: selectPane,
                        onAskFix: handFix,
                        glass: glass
                    )
                    .transition(.scale(scale: 0.9, anchor: .top).combined(with: .opacity))
                }
            }
        }
        .animation(.spring(response: 0.42, dampingFraction: 0.86), value: stage)
        .onAppear {
            model.autoConnectIfNeeded()
            applyPairingGate()
            scheduleRestore()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.autoConnectIfNeeded(force: true) }
        }
        .onChange(of: model.hostsReady) { _, _ in
            applyPairingGate()
        }
        .onChange(of: model.hosts.map(\.id)) { _, _ in
            applyPairingGate()
        }
        .onChange(of: model.pendingOpenAgentID) { _, id in
            guard let id else { return }
            if let thread = catalog.first(where: { $0.id == id || $0.paneId == id }) {
                open(thread)
                model.pendingOpenAgentID = nil
            }
        }
        .onChange(of: restoreToken) { _, _ in
            scheduleRestore()
        }
        .sheet(isPresented: $showSettings) { settings }
        .sheet(isPresented: $showNewSession) { newSession }
        .confirmationDialog("取消配对这台电脑？", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("取消配对", role: .destructive) {
                guard let id = model.selectedHostID else { return }
                _Concurrency.Task { await model.unpair(id) }
            }
            Button("取消", role: .cancel) {}
        }
    }

    @ViewBuilder
    private var underlying: some View {
        switch stage == "terminal" ? terminalReturn : stage {
        case "pair":
            PairingView(
                model: model,
                onPaired: {
                    if let id = model.selectedHostID {
                        noteHostSwitch(id)
                    }
                    userOpenedPairing = false
                    stage = "sessions"
                },
                onClose: model.hosts.isEmpty ? nil : {
                    userOpenedPairing = false
                    stage = "sessions"
                }
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
                    showsChrome: stage != "terminal",
                    glass: glass,
                    nextQuestion: nextQuestion(after: thread),
                    nextAgent: nextAgentName(after: thread)
                )
            } else {
                ContentUnavailableView("会话不在了", systemImage: "bubble.left", description: Text("回到列表。"))
                    .onTapGesture { stage = "sessions" }
            }
        default:
            if !model.hostsReady && model.hosts.isEmpty && !userOpenedPairing {
                #if DEBUG
                sessionList
                #else
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                #endif
            } else {
                sessionList
            }
        }
    }

    private var sessionList: some View {
        SessionListView(
                    model: model,
                    threads: visibleThreads,
                    pending: pending,
                    filter: currentFilter,
                    roster: SessionListKt.sessionRoster(threads: scopeThreads),
                    scopePaths: HostFilterBridge.includingSelected(
                        HostFilterBridge.paths(threads: realCatalog, hostId: model.selectedHostID),
                        scope: currentFilter.scope
                    ),
                    homeDir: selectedHome,
                    scopeSubtitle: scopeSubtitle,
                    filtersHighlighted: filtersHighlighted,
                    catalog: realCatalog,
                    onlineHostIDs: onlineHostIDs,
                    trails: threadTrails,
                    onScope: rememberScope,
                    onStatus: rememberStatus,
                    onSelectHost: selectHost,
                    onFocusFilter: focusFilter,
                    onOpen: open,
                    onAnswer: { thread, option in
                        #if DEBUG
                        if PlaytestChatKt.isPlaytestThread(id: thread.id) { return }
                        #endif
                        model.uhpRegistry.answer(thread: thread, option: option)
                    },
                    onPair: {
                        userOpenedPairing = true
                        stage = "pair"
                    },
                    onNewSession: beginNewSession,
                    onTerminal: { openTerminal(openThread?.paneId) },
                    onSettings: { showSettings = true },
                    showsChrome: stage != "terminal",
                    glass: glass
                )
                .task(id: acpLoadKey) {
                    guard let id = model.selectedHostID else { return }
                    guard model.hostStates[id]?.connected == true else { return }
                    model.loadAcpAgents()
                }
    }

    private var acpLoadKey: String {
        let id = model.selectedHostID ?? ""
        let connected = model.hostStates[id]?.connected == true
        return "\(id)-\(connected)"
    }

    private func applyPairingGate() {
        #if DEBUG
        if model.hosts.isEmpty && !userOpenedPairing {
            if stage == "pair" { stage = "sessions" }
            return
        }
        #endif
        let present = HostStartupKt.shouldPresentPairing(
            catalogReady: model.hostsReady,
            pairedHostCount: Int32(model.hosts.count),
            userOpenedPairing: userOpenedPairing,
            pairingInProgress: false
        )
        if !model.hostsReady && !userOpenedPairing {
            if stage == "pair" { stage = "sessions" }
            return
        }
        if model.hosts.isEmpty && present {
            stage = "pair"
        } else if present && userOpenedPairing && stage == "sessions" {
            stage = "pair"
        } else if !present && stage == "pair" {
            userOpenedPairing = false
            stage = "sessions"
        }
    }

    private var realCatalog: [AgentThread] {
        var raw: [AgentThread] = []
        for host in model.hosts {
            guard let state = model.hostStates[host.id] else { continue }
            let rows: [AgentThread] = KotlinLists.array(state.threads(hostId: host.id) as Any)
            raw.append(contentsOf: rows)
        }
        return raw
    }

    private var catalog: [AgentThread] {
        #if DEBUG
        let raw = realCatalog + debugSamples
        #else
        let raw = realCatalog
        #endif
        if raw.isEmpty { return [] }
        return KotlinLists.array(SessionListKt.sortSessions(threads: raw) as Any)
    }

    private var onlineHostIDs: Set<String> {
        Set(model.hosts.filter { $0.connection == .live }.map(\.id))
    }

    private var currentFilter: HostSessionFilter {
        _ = filterRevision
        return HostFilterBridge.stored(for: model.selectedHostID)
    }

    private var scopeThreads: [AgentThread] {
        let scoped = HostFilterBridge.sessions(
            threads: realCatalog,
            hostId: model.selectedHostID,
            scope: currentFilter.scope,
            status: .all,
            online: onlineHostIDs
        )
        #if DEBUG
        return scoped + debugSamples
        #else
        return scoped
        #endif
    }

    private var visibleThreads: [AgentThread] {
        KotlinLists.array(SessionListKt.filterSessions(threads: scopeThreads, filter: currentFilter.status) as Any)
    }

    private var pending: [AgentThread] {
        KotlinLists.array(SessionListKt.filterSessions(threads: scopeThreads, filter: .needsYou) as Any)
    }

    private var scopeSubtitle: String {
        switch onEnum(of: currentFilter.scope) {
        case .allOnline:
            return "全部在线主机"
        case .currentHost:
            return model.selectedHost?.name ?? "这台电脑"
        case .path(let path):
            return HostFilterBridge.relative(path.cwd, home: selectedHome)
        }
    }

    private var threadTrails: [String: TrailThumb] {
        var map: [String: TrailThumb] = [:]
        for host in model.hosts {
            guard let state = model.hostStates[host.id] else { continue }
            let owned = realCatalog.filter { $0.hostId == host.id }
            let rows: [ThreadTrail] = KotlinLists.array(
                SessionListKt.threadTrails(state: state, threads: owned) as Any
            )
            for row in rows {
                map[row.threadId] = row.thumb
            }
        }
        #if DEBUG
        if !hostIsLive {
            let samples: [ThreadTrail] = KotlinLists.array(PlaytestListKt.playtestListTrails() as Any)
            for row in samples where map[row.threadId] == nil {
                map[row.threadId] = row.thumb
            }
        }
        #endif
        return map
    }

    private var openThread: AgentThread? {
        catalog.first { $0.id == threadID }
    }

    private var selectedHome: String? {
        guard let host = model.selectedHost else { return nil }
        let paths = realCatalog.compactMap { $0.hostId == host.id ? $0.cwd : nil }
        return HostFilterBridge.homeDirectory(username: host.username, paths: paths)
    }

    private var restoreToken: String {
        let id = model.selectedHostID ?? ""
        let count = realCatalog.filter { $0.hostId == id }.count
        let known = sessionListKnown(hostId: id, count: count)
        return "\(id)#\(known)#\(count)"
    }

    private func sessionListKnown(hostId: String, count: Int) -> Bool {
        guard model.hostStates[hostId] != nil else { return false }
        let live = model.selectedHost?.connection == .live
        return live || count > 0
    }

    private func noteHostSwitch(_ hostId: String) {
        _ = HostFilterBridge.switched(to: hostId)
        restoredHosts.insert(hostId)
        filterRevision += 1
    }

    /// Wait until the session count stops moving, then restore once.
    private func scheduleRestore() {
        restoreTask?.cancel()
        restoreTask = _Concurrency.Task { @MainActor in
            try? await _Concurrency.Task.sleep(nanoseconds: 2_000_000_000)
            guard !_Concurrency.Task.isCancelled else { return }
            restoreSelectedFilter()
        }
    }

    /// Once per host, after that host's session list is known. Not on every list change.
    private func restoreSelectedFilter() {
        guard let id = model.selectedHostID, !restoredHosts.contains(id) else { return }
        let threads = realCatalog.filter { $0.hostId == id }
        let known = sessionListKnown(hostId: id, count: threads.count)
        guard known else { return }
        restoredHosts.insert(id)
        let stored = HostFilterBridge.stored(for: id)
        let next = HostFilterBridge.restore(stored, threads: threads)
        let before = HostSessionFilterKt.encodeHostSessionFilter(filter: stored)
        let after = HostSessionFilterKt.encodeHostSessionFilter(filter: next)
        guard before != after else { return }
        HostFilterBridge.remember(next, hostId: id)
        filterRevision += 1
    }

    private func rememberScope(_ scope: SessionScope) {
        guard let id = model.selectedHostID else { return }
        HostFilterBridge.remember(
            HostFilterBridge.make(scope: scope, status: currentFilter.status),
            hostId: id
        )
        filterRevision += 1
    }

    private func rememberStatus(_ status: SessionFilter) {
        guard let id = model.selectedHostID else { return }
        HostFilterBridge.remember(
            HostFilterBridge.make(scope: currentFilter.scope, status: status),
            hostId: id
        )
        filterRevision += 1
    }

    private func selectHost(_ host: HostViewState) {
        guard host.id != model.selectedHostID else { return }
        noteHostSwitch(host.id)
        model.select(host)
    }

    private func focusFilter(_ host: HostViewState, _ scope: SessionScope, _ status: SessionFilter) {
        if host.id != model.selectedHostID {
            model.select(host)
        }
        HostFilterBridge.remember(HostFilterBridge.make(scope: scope, status: status), hostId: host.id)
        restoredHosts.insert(host.id)
        filterRevision += 1
        flashFilters()
    }

    private func flashFilters() {
        guard !reduceMotion else { return }
        filtersHighlighted = true
        highlightTask?.cancel()
        highlightTask = _Concurrency.Task { @MainActor in
            try? await _Concurrency.Task.sleep(nanoseconds: 600_000_000)
            guard !_Concurrency.Task.isCancelled else { return }
            filtersHighlighted = false
        }
    }

    private func beginNewSession() {
        model.hostUhp()?.loadAcpAgents()
        if case .path(let path) = onEnum(of: currentFilter.scope) {
            cwd = path.cwd
        } else {
            cwd = ""
        }
        showNewSession = true
    }

    #if DEBUG
    private var hostIsLive: Bool {
        model.selectedHost?.connection == .live
    }

    /// Fixtures only when the selected host is not live, so a live host cannot
    /// show sample sessions as its own.
    private var debugSamples: [AgentThread] {
        guard !hostIsLive else { return [] }
        let samples: [AgentThread] = KotlinLists.array(PlaytestChatKt.playtestThreads() as Any)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let listSamples: [AgentThread] = KotlinLists.array(
            PlaytestListKt.playtestListThreads(nowEpochMs: now) as Any
        )
        return samples + listSamples
    }
    #endif

    /// Sample panes and a failed capture, only when this build is DEBUG and
    /// the selected host is not live. Release never reads the fixture.
    private var usingTerminalFixture: Bool {
        #if DEBUG
        return !hostIsLive
        #else
        return false
        #endif
    }

    private var livePanes: [(id: String, title: String)] {
        guard let host = model.selectedHost else { return [] }
        if !host.terminalPanes.isEmpty {
            return host.terminalPanes.map { ($0.id, $0.title) }
        }
        return host.paneTerminalIds.keys.sorted().map { id in
            (id, host.agents.first { $0.id == id }?.name ?? id)
        }
    }

    private var panes: [(id: String, title: String)] {
        if !livePanes.isEmpty { return livePanes }
        #if DEBUG
        if usingTerminalFixture { return fixturePanes }
        #endif
        return []
    }

    #if DEBUG
    private var fixturePanes: [(id: String, title: String)] {
        let rows: [PlaytestPane] = KotlinLists.array(PlaytestTerminalKt.playtestTerminalPanes() as Any)
        return rows.map { ($0.id, $0.title) }
    }
    #endif

    private var shownPaneID: String? {
        #if DEBUG
        if usingTerminalFixture && livePanes.isEmpty {
            return fixturePaneID.isEmpty ? PlaytestTerminalKt.PLAYTEST_TERMINAL_FAILURE_PANE : fixturePaneID
        }
        #endif
        return model.uhp.selectedAgentID
    }

    private var terminalTextOverride: String? {
        #if DEBUG
        guard usingTerminalFixture, model.terminalText.isEmpty else { return nil }
        let pane = shownPaneID ?? PlaytestTerminalKt.PLAYTEST_TERMINAL_FAILURE_PANE
        return PlaytestTerminalKt.playtestTerminalText(paneId: pane)
        #else
        return nil
        #endif
    }

    private var decisionCount: Int {
        #if DEBUG
        if hostIsLive {
            let real = pending.filter { !isPlaytestSample($0.id) }
            if !real.isEmpty { return real.count }
        }
        #endif
        return pending.count
    }

    private var canHandFix: Bool {
        if model.hostUhp()?.canAskFix(paneId: model.uhp.selectedAgentID) == true { return true }
        #if DEBUG
        if usingTerminalFixture { return true }
        #endif
        return false
    }

    /// The decision tied to this terminal, else the first real waiting session.
    /// Debug builds with no live host use the TokenStore sample so the capsule can be shot.
    private var waitingThread: AgentThread? {
        if let linked = linkedPending() { return linked }
        #if DEBUG
        let real = pending.filter { !isPlaytestSample($0.id) }
        if hostIsLive, let first = real.first { return first }
        if let sample = pending.first(where: { $0.id == PlaytestChatKt.PLAYTEST_DECIDE_ID }) {
            return sample
        }
        return real.first ?? pending.first
        #else
        return pending.first
        #endif
    }

    private var waitingTitle: String? {
        guard let thread = waitingThread else { return nil }
        let agent = thread.agentType?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !agent.isEmpty { return agent }
        let title = thread.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? nil : title
    }

    private var waitingDetailText: String? {
        guard let thread = waitingThread else { return nil }
        let summary = thread.summary?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !summary.isEmpty { return summary }
        let question = thread.ask?.question?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return question.isEmpty ? nil : question
    }

    private func linkedPending() -> AgentThread? {
        let byPane = shownPaneID.flatMap { pane in
            pending.first { $0.paneId == pane || $0.id == pane }
        }
        let byThread = threadID.flatMap { id in
            pending.first { $0.id == id }
        }
        let match = byPane ?? byThread
        #if DEBUG
        if hostIsLive, let match, isPlaytestSample(match.id) { return nil }
        #endif
        return match
    }

    #if DEBUG
    private func isPlaytestSample(_ id: String) -> Bool {
        if PlaytestChatKt.isPlaytestThread(id: id) { return true }
        if PlaytestListKt.isPlaytestListThread(id: id) { return true }
        if PlaytestTerminalKt.isPlaytestPane(id: id) { return true }
        return false
    }
    #endif

    private func selectPane(_ id: String) {
        #if DEBUG
        if usingTerminalFixture && livePanes.isEmpty {
            fixturePaneID = id
            return
        }
        #endif
        model.openTerminal(paneId: id)
    }

    /// Live hosts go through HostUhp.askFix. A debug fixture has no session to
    /// prompt, so the button opens the related chat instead.
    private func handFix(_ excerpt: String) -> Bool {
        if model.hostUhp()?.askFix(excerpt: excerpt, paneId: model.uhp.selectedAgentID) == true {
            return true
        }
        #if DEBUG
        if usingTerminalFixture {
            if openThread == nil {
                threadID = PlaytestChatKt.PLAYTEST_DIFF_ID
            }
            terminalReturn = "chat"
            closeTerminal()
            return true
        }
        #endif
        return false
    }

    private func timeline(_ thread: AgentThread) -> [TimelineItem] {
        #if DEBUG
        if PlaytestChatKt.isPlaytestThread(id: thread.id) {
            if let sample = PlaytestChatKt.playtestTimeline(id: thread.id) {
                return KotlinLists.array(sample as Any)
            }
            return []
        }
        #endif
        return KotlinLists.array(model.hostUhp()?.timeline(thread: thread) as Any)
    }

    private func nextQuestion(after thread: AgentThread) -> String? {
        guard let index = pending.firstIndex(where: { $0.id == thread.id }) else { return nil }
        let next = index + 1
        guard pending.indices.contains(next) else { return nil }
        return pending[next].ask?.question
    }

    private func nextAgentName(after thread: AgentThread) -> String? {
        guard let index = pending.firstIndex(where: { $0.id == thread.id }) else { return nil }
        let next = index + 1
        guard pending.indices.contains(next) else { return nil }
        let agent = pending[next].agentType?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let agent, !agent.isEmpty { return agent }
        let title = pending[next].title.trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? nil : title
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
        #if DEBUG
        if PlaytestChatKt.isPlaytestThread(id: thread.id) {
            threadID = thread.id
            stage = "chat"
            return
        }
        #endif
        if let host = model.hosts.first(where: { $0.id == thread.hostId }) {
            if model.selectedHostID != host.id {
                noteHostSwitch(host.id)
            }
            model.select(host)
        }
        model.uhpRegistry.markViewed(thread: thread)
        if thread.acpHistory,
           let agent = thread.acpAgentId,
           let cwd = thread.cwd,
           let session = thread.acpSessionId {
            model.hostUhp()?.resumeAcp(agentId: agent, cwd: cwd, sessionId: session)
        } else if thread.kind == .acp {
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
                    TextField("目录", text: $cwd)
                } footer: {
                    Text("在这台电脑上启动 ACP agent。主机上的 agent 仍在电脑上创建。留空则用当前工作区。")
                }
                let agents: [AcpAgentKind] = KotlinLists.array(model.hostStates[model.selectedHostID ?? ""]?.acp.agents as Any)
                if agents.isEmpty {
                    Text("还没有 ACP agent")
                }
                ForEach(agents, id: \.id) { agent in
                    Button(agent.available ? agent.name : "\(agent.name) 不可用") {
                        model.hostUhp()?.setLaunchAcpCwd(cwd: cwd)
                        model.hostUhp()?.setLaunchAcpAgent(id: agent.id)
                        model.hostUhp()?.launchAcp()
                        showNewSession = false
                    }
                    .disabled(!agent.available)
                }
            }
            .navigationTitle("新会话")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { showNewSession = false }
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
                Section(connectionLabel(host.connection)) {
                    TextField("名称", text: $alias)
                    TextField("地址", text: $addresses, axis: .vertical)
                    TextField("SSH 端口", text: $port)
                    TextField("用户", text: $user)
                    Button("保存连接") {
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
                    Button("刷新") { _Concurrency.Task { await model.refresh(host.id) } }
                    Button("连接") { model.connect(host.id) }
                    Button("断开") { model.disconnect(host.id) }
                    Toggle("唤醒这台手机", isOn: Binding(
                        get: { model.isPushEnabled },
                        set: { model.setWakeForApprovals($0) }
                    ))
                }
                Section {
                    Button("取消配对", role: .destructive, action: onUnpair)
                }
            }
        }
        .navigationTitle(model.selectedHost?.name ?? "电脑")
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
