import ActivityKit
import Foundation
import Observation
import LuviaShared

@MainActor
@Observable
final class AppModel {
    private nonisolated(unsafe) let manager: HostManager
    nonisolated(unsafe) let uhpRegistry: HostUhpRegistry
    @ObservationIgnored private nonisolated(unsafe) var hostsTask: _Concurrency.Task<Void, Never>?
    @ObservationIgnored private nonisolated(unsafe) var terminalTask: _Concurrency.Task<Void, Never>?
    @ObservationIgnored nonisolated(unsafe) var uhpTask: _Concurrency.Task<Void, Never>?
    @ObservationIgnored var boundUhpHostID: String?
    private var terminalControl: TerminalControl?
    private let liveActivity = LiveActivityController()
    private var liveActivityHostID: String?
    private var liveActivityBlockedAgents = 0

    private(set) var hosts: [HostViewState] = []
    var selectedHostID: String?
    var selectedSection: HostSection = .agents
    var isPairingPresented = false
    var terminalText = ""
    var terminalStatus: String?
    private(set) var holdsTerminalControl = false
    var hasLiveSession = false
    var uhp = UhpSurfaceState()

    var selectedHost: HostViewState? {
        hosts.first { $0.id == selectedHostID }
    }

    init() {
        let store = HostStore(filePath: Self.hostStorePath())
        let vault = DeviceKeyVault(service: "tech.asahiart.luvia.device-keys")
        let scope = HostManagerScope()
        manager = HostManager(store: store, vault: vault, scope: scope)
        uhpRegistry = HostUhpRegistry(manager: manager, scope: scope)
        let managerRef = manager
        hostsTask = _Concurrency.Task { [weak self] in
            for await runtimes in managerRef.hosts {
                let states = KotlinLists.array(runtimes as Any).map(HostViewState.init)
                await MainActor.run {
                    self?.replaceHosts(states)
                }
            }
        }
    }

    deinit {
        // hosts is a StateFlow and never completes, so the collecting task has to be
        // cancelled explicitly or it keeps the manager alive past deinit.
        hostsTask?.cancel()
        terminalTask?.cancel()
        uhpTask?.cancel()
        uhpRegistry.close()
        manager.close()
    }

    func select(_ host: HostViewState) {
        selectedHostID = host.id
        selectedSection = .agents
        stopTerminal()
        uhp.reset(hostID: host.id)
        bindUhp(hostID: host.id)
        hostUhp()?.shown()
    }

    func beginPairing(deviceLabel: String, role: HostRole) -> Result<PairingDraft, UserFacingError> {
        switch onEnum(of: manager.beginPairing(deviceLabel: deviceLabel, role: role)) {
        case .ok(let ok):
            guard let draft = ok.value else {
                return .failure(UserFacingError(message: "Could not start pairing."))
            }
            return .success(draft)
        case .err(let err):
            return .failure(UserFacingError(message: FailureText.describe(err.failure)))
        }
    }

    func completePairing(draft: PairingDraft, rawCode: String) async -> Result<HostProfile, UserFacingError> {
        do {
            let outcome = try await manager.completePairing(draft: draft, rawCode: rawCode)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                guard let profile = ok.value else {
                    return .failure(UserFacingError(message: "Pairing did not return a host."))
                }
                selectedHostID = profile.id
                selectedSection = .agents
                bindUhp(hostID: profile.id)
                hostUhp()?.shown()
                return .success(profile)
            case .err(let err):
                return .failure(UserFacingError(message: FailureText.describe(err.failure)))
            }
        } catch {
            return .failure(UserFacingError(message: error.localizedDescription))
        }
    }

    func connect(_ hostId: String) {
        manager.connect(hostId: hostId)
    }

    func disconnect(_ hostId: String) {
        stopTerminal()
        manager.disconnect(hostId: hostId)
    }

    func refresh(_ hostId: String) async {
        do {
            let outcome = try await manager.refresh(hostId: hostId)
            if case .err(let err) = onEnum(of: outcome) {
                terminalStatus = FailureText.describe(err.failure)
            }
        } catch {
            terminalStatus = error.localizedDescription
        }
        await loadSelectedSection()
        if let surface = uhp.moreSurface {
            await loadMoreSurface(surface)
        }
    }

    func unpair(_ hostId: String) async {
        if selectedHostID == hostId {
            selectedHostID = nil
            selectedSection = .agents
            uhp.reset(hostID: nil)
        }
        if liveActivityHostID == hostId {
            await liveActivity.end(nil)
            liveActivityHostID = nil
            liveActivityBlockedAgents = 0
        }
        stopTerminal()
        do {
            try await manager.unpair(hostId: hostId)
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    func handleSectionChange() {
        if uhp.hostID != selectedHostID {
            uhp.reset(hostID: selectedHostID)
        }
        if selectedSection == .terminal {
            startTerminal()
        } else {
            stopTerminal()
        }
        _Concurrency.Task { await self.loadSelectedSection() }
    }

    func sendTerminal(_ text: String) async {
        guard let terminalControl else { return }
        do {
            let outcome = try await terminalControl.submitText(text: text)
            if case .err(let err) = onEnum(of: outcome) {
                applyControlFailure(err.failure)
            }
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    func sendTerminalKey(_ key: TerminalKey) async {
        guard let terminalControl else { return }
        do {
            let outcome = try await terminalControl.sendKey(key: key)
            if case .err(let err) = onEnum(of: outcome) {
                applyControlFailure(err.failure)
            }
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    func requestTerminalControl() {
        guard let host = selectedHost, host.isController else { return }
        guard let locator = host.terminalLocator else { return }
        let hostId = host.id
        let identity = locator.identity()
        let managerRef = manager
        _Concurrency.Task { [weak self] in
            do {
                let outcome = try await managerRef.openTerminal(hostId: hostId, identity: identity)
                await MainActor.run {
                    switch onEnum(of: outcome) {
                    case .ok(let ok):
                        self?.terminalControl?.close()
                        self?.terminalControl = ok.value
                        self?.holdsTerminalControl = true
                        if self?.terminalStatus?.contains("control") == true {
                            self?.terminalStatus = nil
                        }
                    case .err(let err):
                        self?.applyControlFailure(err.failure)
                    }
                }
            } catch {
                await MainActor.run {
                    self?.terminalStatus = error.localizedDescription
                }
            }
        }
    }

    func refreshAll() async {
        let ids = hosts.map(\.id)
        for id in ids {
            do {
                _ = try await manager.refresh(hostId: id)
            } catch {
                continue
            }
        }
        await loadSelectedSection()
    }

    private func replaceHosts(_ states: [HostViewState]) {
        let previousAgents = selectedHost?.agents ?? []
        let openID = uhp.selectedAgentID
        let wasLive = hasLiveSession
        hosts = Self.sortedHosts(states)
        if let selectedHostID, !states.contains(where: { $0.id == selectedHostID }) {
            self.selectedHostID = nil
        }
        if selectedHostID == nil {
            selectedHostID = states.first?.id
        }
        refreshCaps()
        if let id = selectedHostID {
            bindUhp(hostID: id)
        }
        if let host = selectedHost {
            uhp.hostIsController = host.isController
            uhp.hostAgents = host.agents
            if let openID {
                let oldStatus = previousAgents.first { $0.id == openID }?.status
                let newStatus = host.agents.first { $0.id == openID }?.status
                if oldStatus != newStatus {
                    _Concurrency.Task { await self.readTranscript(for: openID) }
                }
            }
        }
        if selectedSection == .terminal {
            startTerminal()
        }
        syncLiveActivity()
        if !wasLive && hasLiveSession {
            _Concurrency.Task { await self.loadSelectedSection() }
        }
    }

    private func startTerminal() {
        guard selectedSection == .terminal, let host = selectedHost else {
            stopTerminal()
            return
        }
        guard let locator = host.terminalLocator else {
            stopTerminal()
            terminalText = ""
            terminalStatus = "Connect to load a live pane."
            return
        }
        stopTerminal()
        terminalStatus = nil
        let hostId = host.id
        let identity = locator.identity()
        let managerRef = manager
        terminalTask = _Concurrency.Task { [weak self] in
            for await update in managerRef.observeTerminal(hostId: hostId, identity: identity) {
                await MainActor.run {
                    self?.applyTerminal(update)
                }
            }
        }
    }

    private func stopTerminal() {
        terminalTask?.cancel()
        terminalTask = nil
        terminalControl?.close()
        terminalControl = nil
        holdsTerminalControl = false
    }

    private func applyControlFailure(_ failure: Failure) {
        if case .controlConflict = onEnum(of: failure) {
            terminalControl?.close()
            terminalControl = nil
            holdsTerminalControl = false
            terminalStatus = "\(FailureText.describe(failure)) Observe still works."
        } else {
            terminalStatus = FailureText.describe(failure)
        }
    }


    private func applyTerminal(_ update: TerminalUpdate) {
        switch onEnum(of: update) {
        case .frame(let wrapped):
            terminalText = wrapped.frame.text
            if holdsTerminalControl {
                terminalStatus = wrapped.frame.truncated ? "Output truncated." : nil
            } else if wrapped.frame.truncated, terminalStatus == nil {
                terminalStatus = "Output truncated."
            }
        case .resyncing(_):
            if holdsTerminalControl {
                terminalStatus = "Resyncing…"
            }
        case .failed(let wrapped):
            applyControlFailure(wrapped.failure)
        }
    }

    private func syncLiveActivity() {
        let host = selectedHost ?? hosts.first { $0.connection == .live || $0.connection == .connecting }
        guard let host else {
            _Concurrency.Task { await liveActivity.end(nil) }
            liveActivityHostID = nil
            liveActivityBlockedAgents = 0
            return
        }
        let state = host.activityState()
        if liveActivityHostID != host.id || !liveActivity.isActive {
            liveActivityHostID = host.id
            liveActivityBlockedAgents = host.blockedAgents
            try? liveActivity.start(
                hostName: host.name,
                sessionName: host.sessionName ?? host.address,
                state: state
            )
            return
        }
        let alert = blockedAgentAlert(for: host)
        liveActivityBlockedAgents = host.blockedAgents
        if host.connection == .stale {
            _Concurrency.Task { await liveActivity.markStale(state) }
        } else {
            _Concurrency.Task { await liveActivity.update(state, alert: alert) }
        }
    }

    /// An agent blocking on input is the only state worth interrupting for. Alerting
    /// on a rising count keeps a steady backlog silent while still announcing each
    /// newly blocked agent. Switching hosts reseeds the baseline instead of alerting,
    /// so adopting an already-blocked host stays quiet.
    private func blockedAgentAlert(for host: HostViewState) -> AlertConfiguration? {
        guard host.blockedAgents > liveActivityBlockedAgents else { return nil }
        let count = host.blockedAgents
        let subject = count == 1 ? "1 agent needs input" : "\(count) agents need input"
        return AlertConfiguration(
            title: "Agent needs you",
            body: LocalizedStringResource(stringLiteral: "\(host.name): \(subject)"),
            sound: .default
        )
    }

    private static func hostStorePath() -> String {
        let fileManager = FileManager.default
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let directory = root.appendingPathComponent("Luvia", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("hosts.json").path
    }

    private static func sortedHosts(_ states: [HostViewState]) -> [HostViewState] {
        states.enumerated()
            .sorted { lhs, rhs in
                let left = sortGroup(lhs.element)
                let right = sortGroup(rhs.element)
                if left != right { return left < right }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    private static func sortGroup(_ host: HostViewState) -> Int {
        if host.blockedAgents > 0 { return 0 }
        if host.connection == .live { return 1 }
        return 2
    }
}

@MainActor
@Observable
final class UhpSurfaceState {
    var hostID: String?
    var snapshot: HostUhpState?
    var hostIsController = false
    var hostAgents: [AgentViewState] = []
    var localError: String?
    var selectedAgentID: String?

    var composerText = ""
    var isNotesPresented = false
    var isAddNotePresented = false
    var addNote = AddNoteDraft()
    var isSendNotesPresented = false
    var sendNotesTarget: String?
    var sendNotesMessage: String?
    var isAddTaskPresented = false
    var addTaskTitle = ""
    var addTaskPaths = ""
    var moreSurface: MoreSurface?
    var searchQuery = ""
    var isCreateWorktreePresented = false
    var createWorktreeBranch = ""
    var isSessionsPresented = false
    var isNameAgentPresented = false
    var nameAgentText = ""
    var isForkAgentPresented = false
    var forkAgentName = ""

    var isController: Bool {
        if let snapshot, snapshot.connected {
            return snapshot.canMutate || !snapshot.isObserver
        }
        return hostIsController
    }

    var caps: UhpCaps {
        snapshot.map { UhpCaps($0.capabilities) } ?? UhpCaps()
    }

    var errorMessage: String? {
        if let localError { return localError }
        guard let snapshot else { return nil }
        return snapshot.agentDetail.errorText
            ?? snapshot.review.errorText
            ?? snapshot.tasks.errorText
            ?? snapshot.files.errorText
            ?? snapshot.search.errorText
            ?? snapshot.worktrees.errorText
            ?? snapshot.automations.errorText
            ?? snapshot.layout.errorText
            ?? snapshot.errorText
    }

    var agents: [AgentViewState] {
        let live: [AgentSummary] = KotlinLists.array(snapshot?.agents as Any)
        let mapped = live.map(AgentViewState.init)
        return mapped.isEmpty ? hostAgents : mapped
    }

    var header: AgentHeaderState? {
        guard let summary = snapshot?.agentDetail.summary else { return nil }
        return AgentHeaderState(
            paneId: summary.paneId,
            name: summary.name ?? summary.agent ?? summary.paneId,
            kind: summary.agent,
            status: agentStatusLabel(summary.status),
            isBlocked: AgentStatusKind(summary.status) == .blocked,
            workspace: summary.workspaceName ?? summary.workspace,
            branch: summary.branch,
            cwd: summary.cwd,
            missionUsage: nil
        )
    }

    var transcript: String {
        snapshot?.agentDetail.transcript?.text ?? ""
    }

    var isSending: Bool {
        guard let snapshot else { return false }
        return snapshot.agentDetail.sending
            || snapshot.review.sending
            || snapshot.tasks.mutating
            || snapshot.files.mutating
            || snapshot.worktrees.mutating
            || snapshot.automations.mutating
            || snapshot.layout.mutating
    }

    var unconfirmed: UnconfirmedAction? {
        snapshot.flatMap(UnconfirmedAction.init)
    }

    var unconfirmedTaskID: String? {
        snapshot?.tasks.unconfirmedTaskId
    }

    var diffFiles: [DiffFileItem] {
        guard let list = snapshot?.review.list else { return [] }
        let files: [DiffFile] = KotlinLists.array(list.files as Any)
        return files.map { file in
            DiffFileItem(
                path: file.path,
                layer: diffLayerLabel(file.layer),
                additions: Int(kotlinInt64(file.additions) ?? 0),
                deletions: Int(kotlinInt64(file.deletions) ?? 0)
            )
        }
    }

    var diffBranch: String? {
        snapshot?.review.list?.branch
    }

    var selectedDiff: DiffFileDetail? {
        guard let selected = snapshot?.review.selectedFile else { return nil }
        return DiffFileDetail(
            item: DiffFileItem(
                path: selected.path,
                layer: diffLayerLabel(selected.layer),
                additions: Int(kotlinInt64(selected.additions) ?? 0),
                deletions: Int(kotlinInt64(selected.deletions) ?? 0)
            ),
            hunks: []
        )
    }

    var notes: [ReviewNoteItem] {
        let notes: [ReviewNote] = KotlinLists.array(snapshot?.review.notes as Any)
        return notes.map { note in
            ReviewNoteItem(
                id: note.id,
                body: note.body,
                stateLabel: note.state == .resolved ? "resolved" : "open",
                isOpen: note.state == .open,
                isResolved: note.state == .resolved,
                path: note.path,
                line: kotlinInt64(note.startLine).map { Int($0) },
                deliveries: nil
            )
        }
    }

    var tasks: [TaskViewState] {
        let tasks: [TaskSummary] = KotlinLists.array(snapshot?.tasks.tasks as Any)
        return tasks.map(TaskViewState.init)
    }

    var boardChangedMessage: String? {
        snapshot?.tasks.boardChanged == true ? "Updated by someone else. Showing latest." : nil
    }

    var fileRoot: String? {
        guard let root = snapshot?.files.root, !root.isEmpty else { return nil }
        return root
    }

    var fileRows: [FileTreeRowItem] {
        let rows: [FileTreeRow] = KotlinLists.array(snapshot?.files.rows as Any)
        return rows.map { row in
            FileTreeRowItem(
                path: row.path,
                name: row.name,
                depth: Int(row.depth),
                isDirectory: row.dir,
                isExpanded: row.expanded
            )
        }
    }

    var searchMatches: [SearchMatchItem] {
        let matches: [SearchMatch] = KotlinLists.array(snapshot?.search.matches as Any)
        return matches.map { match in
            SearchMatchItem(id: match.id, kind: match.kind, label: match.label, detail: match.detail, match: match)
        }
    }

    var searchTotal: Int64 {
        kotlinInt64(snapshot?.search.result?.total) ?? 0
    }

    var searchShown: Int64 {
        kotlinInt64(snapshot?.search.result?.shown) ?? 0
    }

    var searchPartial: Bool {
        snapshot?.search.result?.partial ?? false
    }

    var worktrees: [WorktreeItem] {
        let worktrees: [WorktreeEntry] = KotlinLists.array(snapshot?.worktrees.worktrees as Any)
        return worktrees.map { tree in
            WorktreeItem(path: tree.path, branch: tree.branch, head: tree.head, isMain: tree.main)
        }
    }

    var automations: [AutomationItem] {
        let automations: [Automation] = KotlinLists.array(snapshot?.automations.automations as Any)
        return automations.map { item in
            AutomationItem(
                id: item.id,
                name: item.name,
                enabled: item.enabled,
                state: item.targetState,
                nextRun: nil,
                latestStatus: nil,
                latestError: nil
            )
        }
    }

    var automationHealthSummary: String? { nil }

    var workspaces: [WorkspaceItem] {
        let workspaces: [WorkspaceSummary] = KotlinLists.array(snapshot?.layout.workspaces as Any)
        return workspaces.map { workspace in
            WorkspaceItem(
                index: Int(workspace.index),
                name: workspace.name,
                isActive: workspace.active,
                isPinned: workspace.pinned,
                cwd: workspace.cwd,
                branch: workspace.branch,
                tabCount: Int(workspace.tabCount)
            )
        }
    }

    var panes: [PaneItem] {
        let panes: [PaneListEntry] = KotlinLists.array(snapshot?.layout.panes as Any)
        return panes.map { pane in
            PaneItem(
                pane: pane.pane,
                agent: pane.agent,
                status: agentStatusLabel(pane.status),
                isFocused: pane.focused,
                cwd: pane.cwd
            )
        }
    }

    var agentSessions: [AgentSessionItem] {
        let sessions: [AgentSessionEntry] = KotlinLists.array(snapshot?.agentSessions as Any)
        return sessions.map { session in
            AgentSessionItem(agent: session.agent, sessionId: session.sessionId, cwd: session.cwd)
        }
    }

    var allowsMutation: Bool {
        isController && !isSending && unconfirmed == nil
    }

    var canAddNote: Bool {
        isController && caps.diffNoteAdd && !isSending && unconfirmed == nil
    }

    var canResolveNote: Bool {
        isController && caps.diffNoteResolve && !isSending && unconfirmed == nil
    }

    var canReopenNote: Bool {
        isController && caps.diffNoteReopen && !isSending && unconfirmed == nil
    }

    var canRemoveNote: Bool {
        isController && caps.diffNoteRemove && !isSending && unconfirmed == nil
    }

    var canSendNotes: Bool {
        isController && caps.diffNoteSend && !isSending && unconfirmed == nil
    }

    func reset(hostID: String?) {
        self.hostID = hostID
        snapshot = nil
        hostIsController = false
        hostAgents = []
        localError = nil
        selectedAgentID = nil
        composerText = ""
        isNotesPresented = false
        isAddNotePresented = false
        addNote = AddNoteDraft()
        isSendNotesPresented = false
        sendNotesTarget = nil
        sendNotesMessage = nil
        isAddTaskPresented = false
        addTaskTitle = ""
        addTaskPaths = ""
        moreSurface = nil
        searchQuery = ""
        isCreateWorktreePresented = false
        createWorktreeBranch = ""
        isSessionsPresented = false
        isNameAgentPresented = false
        nameAgentText = ""
        isForkAgentPresented = false
        forkAgentName = ""
    }
}

extension AppModel {
    func liveSession() -> LuviaSession? {
        guard let id = selectedHostID else { return nil }
        return manager.session(hostId: id)
    }

    func loadSelectedSection() async {
        guard let workspace = hostUhp() else { return }
        switch selectedSection {
        case .agents:
            workspace.show(section: LuviaShared.HostSection.agents)
        case .review:
            workspace.show(section: LuviaShared.HostSection.review)
        case .tasks:
            workspace.show(section: LuviaShared.HostSection.tasks)
        case .terminal:
            break
        }
    }

    func refreshCaps() {
        hasLiveSession = liveSession() != nil
    }

    func loadAgents() async {
        hostUhp()?.shown()
    }

    func openAgent(_ id: String) async {
        uhp.selectedAgentID = id
        hostUhp()?.openAgent(paneId: id)
    }

    @discardableResult
    func refreshOpenAgent() async -> Bool {
        guard let id = uhp.selectedAgentID else {
            uhp.localError = "No Agent is selected."
            return false
        }
        hostUhp()?.openAgent(paneId: id)
        return true
    }

    @discardableResult
    func readTranscript(for target: String) async -> Bool {
        hostUhp()?.openAgent(paneId: target)
        return true
    }

    @discardableResult
    func promptAgent(target: String, text: String) async -> Bool {
        hostUhp()?.promptAgent(text: text)
        return true
    }

    func sendAgentPrompt() async {
        let text = uhp.composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        hostUhp()?.setAgentDraft(text: text)
        hostUhp()?.promptAgent(text: text)
        if uhp.composerText.trimmingCharacters(in: .whitespacesAndNewlines) == text {
            uhp.composerText = ""
        }
    }

    func sendAgentKeys(_ keys: [AgentKey]) async {
        hostUhp()?.sendAgentKeys(keys: keys)
    }

    func checkUnconfirmed() async {
        hostUhp()?.checkAgent()
        hostUhp()?.checkNotes()
        hostUhp()?.checkTasks()
    }

    func loadDiff() async {
        hostUhp()?.show(section: LuviaShared.HostSection.review)
    }

    func openDiffFile(_ item: DiffFileItem) async {
        hostUhp()?.openDiffFile(path: item.path, layer: diffLayer(from: item.layer))
    }

    func beginAddNote(file: DiffFileItem, hunk: DiffHunkItem, line: DiffLineItem) {
        guard uhp.canAddNote else { return }
        let index = hunk.lines.firstIndex(where: { $0.id == line.id }) ?? 0
        let before = Array(hunk.lines.prefix(index).suffix(2).map(\.text))
        let after = Array(hunk.lines.dropFirst(index + 1).prefix(2).map(\.text))
        uhp.addNote = AddNoteDraft(
            file: file.path,
            layer: file.layer,
            usesNewLine: line.newLine != nil,
            line: line.newLine ?? line.oldLine ?? 1,
            body: "",
            anchoredText: line.text,
            contextBefore: before,
            contextAfter: after
        )
        uhp.isAddNotePresented = true
    }

    func addReviewNote() async {
        let draft = uhp.addNote
        let body = draft.body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let line: ReviewLine = draft.usesNewLine
            ? ReviewLine.New(line: Int32(draft.line))
            : ReviewLine.Old(line: Int32(draft.line))
        hostUhp()?.addReviewNote(file: draft.file, line: line, body: body, layer: diffLayer(from: draft.layer ?? ""))
        uhp.isAddNotePresented = false
        uhp.addNote = AddNoteDraft()
    }

    func resolveNote(_ id: String) async {
        hostUhp()?.resolveReviewNote(id: id)
    }

    func reopenNote(_ id: String) async {
        hostUhp()?.reopenReviewNote(id: id)
    }

    func removeNote(_ id: String) async {
        hostUhp()?.removeReviewNote(id: id)
    }

    func sendReviewNotes(to target: String) async {
        hostUhp()?.sendReviewNotes(to: target)
        uhp.isSendNotesPresented = false
    }

    @discardableResult
    func loadTasks() async -> Bool {
        hostUhp()?.show(section: LuviaShared.HostSection.tasks)
        return true
    }

    func addTask() async {
        let title = uhp.addTaskTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { return }
        let paths = uhp.addTaskPaths
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        hostUhp()?.addTask(title: title, paths: paths)
        uhp.isAddTaskPresented = false
        uhp.addTaskTitle = ""
        uhp.addTaskPaths = ""
    }

    func completeTask(_ id: String) async {
        hostUhp()?.completeTask(taskId: id)
    }

}

private final class HostManagerScope: NSObject, Kotlinx_coroutines_coreCoroutineScope {
    let coroutineContext: KotlinCoroutineContext = EmptyKotlinCoroutineContext()
}

private final class EmptyKotlinCoroutineContext: NSObject, KotlinCoroutineContext {
    func fold(initial: Any?, operation: @escaping (Any?, KotlinCoroutineContextElement) -> Any?) -> Any? {
        initial
    }

    func get(key: KotlinCoroutineContextKey) -> KotlinCoroutineContextElement? {
        nil
    }

    func minusKey(key: KotlinCoroutineContextKey) -> KotlinCoroutineContext {
        self
    }

    func plus(context: KotlinCoroutineContext) -> KotlinCoroutineContext {
        context
    }
}

