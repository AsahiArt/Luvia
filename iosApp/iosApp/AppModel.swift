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
            uhp.isController = host.isController
            syncAgentsFromHost(host)
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
    var isController = false
    var caps = UhpCaps()
    var errorMessage: String?

    var agents: [AgentViewState] = []
    var selectedAgentID: String?
    var header: AgentHeaderState?
    var transcript = ""
    var transcriptRevision: Int64?
    var transcriptContentRevision: Int64?
    var transcriptTerminalID: String?
    var composerText = ""
    var isSending = false
    var unconfirmed: UnconfirmedAction?
    var unconfirmedTaskID: String?

    var diffFiles: [DiffFileItem] = []
    var diffBranch: String?
    var selectedDiff: DiffFileDetail?
    var notes: [ReviewNoteItem] = []
    var isNotesPresented = false
    var isAddNotePresented = false
    var addNote = AddNoteDraft()
    var isSendNotesPresented = false
    var sendNotesTarget: String?
    var sendNotesMessage: String?

    var tasks: [TaskViewState] = []
    var taskRevisions: [String: Int64] = [:]
    var isAddTaskPresented = false
    var addTaskTitle = ""
    var addTaskPaths = ""
    var boardChangedMessage: String?

    var moreSurface: MoreSurface?
    var fileRoot: String?
    var fileRows: [FileTreeRowItem] = []
    var searchQuery = ""
    var searchMatches: [SearchMatchItem] = []
    var searchTotal: Int64 = 0
    var searchShown: Int64 = 0
    var searchPartial = false
    var worktrees: [WorktreeItem] = []
    var isCreateWorktreePresented = false
    var createWorktreeBranch = ""
    var automations: [AutomationItem] = []
    var automationHealthSummary: String?
    var workspaces: [WorkspaceItem] = []
    var panes: [PaneItem] = []
    var agentSessions: [AgentSessionItem] = []
    var isSessionsPresented = false
    var isNameAgentPresented = false
    var nameAgentText = ""
    var isForkAgentPresented = false
    var forkAgentName = ""

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
        caps = UhpCaps()
        errorMessage = nil
        agents = []
        selectedAgentID = nil
        header = nil
        transcript = ""
        transcriptRevision = nil
        transcriptContentRevision = nil
        transcriptTerminalID = nil
        composerText = ""
        isSending = false
        unconfirmed = nil
        unconfirmedTaskID = nil
        diffFiles = []
        diffBranch = nil
        selectedDiff = nil
        notes = []
        isNotesPresented = false
        isAddNotePresented = false
        addNote = AddNoteDraft()
        isSendNotesPresented = false
        sendNotesTarget = nil
        sendNotesMessage = nil
        tasks = []
        taskRevisions = [:]
        isAddTaskPresented = false
        addTaskTitle = ""
        addTaskPaths = ""
        boardChangedMessage = nil
        moreSurface = nil
        fileRoot = nil
        fileRows = []
        searchQuery = ""
        searchMatches = []
        searchTotal = 0
        searchShown = 0
        searchPartial = false
        worktrees = []
        isCreateWorktreePresented = false
        createWorktreeBranch = ""
        automations = []
        automationHealthSummary = nil
        workspaces = []
        panes = []
        agentSessions = []
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
            uhp.errorMessage = "No Agent is selected."
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

    private func syncAgentsFromHost(_ host: HostViewState) {
        if uhp.agents.isEmpty {
            uhp.agents = host.agents
            return
        }
        for index in uhp.agents.indices {
            if let fresh = host.agents.first(where: { $0.id == uhp.agents[index].id }) {
                uhp.agents[index].status = fresh.status
                uhp.agents[index].statusKind = fresh.statusKind
                uhp.agents[index].workspace = fresh.workspace ?? uhp.agents[index].workspace
                uhp.agents[index].branch = fresh.branch ?? uhp.agents[index].branch
                uhp.agents[index].cwd = fresh.cwd ?? uhp.agents[index].cwd
            }
        }
        if let id = uhp.selectedAgentID, let agent = uhp.agents.first(where: { $0.id == id }) {
            uhp.header?.status = agent.status
            uhp.header?.isBlocked = agent.isBlocked
            uhp.header?.workspace = agent.workspace
            uhp.header?.branch = agent.branch
            uhp.header?.cwd = agent.cwd
        }
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

