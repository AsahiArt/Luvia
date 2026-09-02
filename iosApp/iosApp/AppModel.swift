import ActivityKit
import Foundation
import Observation
import LuviaShared

@MainActor
@Observable
final class AppModel {
    private nonisolated(unsafe) let manager: HostManager
    @ObservationIgnored private nonisolated(unsafe) var hostsTask: _Concurrency.Task<Void, Never>?
    @ObservationIgnored private nonisolated(unsafe) var terminalTask: _Concurrency.Task<Void, Never>?
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
        manager.close()
    }

    func select(_ host: HostViewState) {
        selectedHostID = host.id
        selectedSection = .agents
        stopTerminal()
        uhp.reset(hostID: host.id)
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
                isPairingPresented = false
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
                terminalStatus = FailureText.describe(err.failure)
            }
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    private func replaceHosts(_ states: [HostViewState]) {
        let previousAgents = selectedHost?.agents ?? []
        let openID = uhp.selectedAgentID
        hosts = states
        if let selectedHostID, !states.contains(where: { $0.id == selectedHostID }) {
            self.selectedHostID = nil
        }
        if selectedHostID == nil {
            selectedHostID = states.first?.id
        }
        hasLiveSession = selectedHostID.flatMap { manager.session(hostId: $0) } != nil
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
        if host.isController {
            _Concurrency.Task { [weak self] in
                do {
                    let outcome = try await managerRef.openTerminal(hostId: hostId, identity: identity)
                    await MainActor.run {
                        switch onEnum(of: outcome) {
                        case .ok(let ok):
                            self?.terminalControl = ok.value
                        case .err(let err):
                            self?.terminalStatus = FailureText.describe(err.failure)
                        }
                    }
                } catch {
                    await MainActor.run {
                        self?.terminalStatus = error.localizedDescription
                    }
                }
            }
        }
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
    }

    private func applyTerminal(_ update: TerminalUpdate) {
        switch onEnum(of: update) {
        case .frame(let wrapped):
            terminalText = wrapped.frame.text
            terminalStatus = wrapped.frame.truncated ? "Output truncated." : nil
        case .resyncing(_):
            terminalStatus = "Resyncing…"
        case .failed(let wrapped):
            terminalStatus = FailureText.describe(wrapped.failure)
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

    func reset(hostID: String?) {
        self.hostID = hostID
        caps = UhpCaps()
        errorMessage = nil
        agents = []
        selectedAgentID = nil
        header = nil
        transcript = ""
        transcriptRevision = nil
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
    }
}

extension AppModel {
    private func liveSession() -> LuviaSession? {
        guard let id = selectedHostID else { return nil }
        return manager.session(hostId: id)
    }

    func loadSelectedSection() async {
        refreshCaps()
        guard hasLiveSession, liveSession() != nil else { return }
        switch selectedSection {
        case .agents:
            await loadAgents()
        case .review:
            await loadDiff()
        case .tasks:
            await loadTasks()
        case .terminal:
            break
        }
    }

    private func refreshCaps() {
        guard let session = liveSession() else {
            hasLiveSession = false
            uhp.caps = UhpCaps()
            return
        }
        hasLiveSession = true
        let methods = UhpMethods.shared
        uhp.caps = UhpCaps(
            agentRead: session.supports(method: methods.AGENT_READ),
            agentPrompt: session.supports(method: methods.AGENT_PROMPT),
            agentKeys: session.supports(method: methods.AGENT_KEYS),
            missionSnapshot: session.supports(method: methods.MISSION_SNAPSHOT),
            diffList: session.supports(method: methods.DIFF_LIST),
            diffGet: session.supports(method: methods.DIFF_GET),
            diffNoteList: session.supports(method: methods.DIFF_NOTE_LIST),
            diffNoteAdd: session.supports(method: methods.DIFF_NOTE_ADD),
            diffNoteSend: session.supports(method: methods.DIFF_NOTE_SEND),
            taskList: session.supports(method: methods.TASK_LIST),
            taskAdd: session.supports(method: methods.TASK_ADD),
            taskDone: session.supports(method: methods.TASK_DONE)
        )
        uhp.isController = selectedHost?.isController ?? false
    }

    func loadAgents() async {
        if uhp.agents.isEmpty, let host = selectedHost {
            uhp.agents = host.agents
        }
        guard let session = liveSession() else { return }
        do {
            let outcome = try await session.listAgents()
            switch onEnum(of: outcome) {
            case .ok(let ok):
                let summaries: [AgentSummary] = KotlinLists.array(ok.value as Any)
                if !summaries.isEmpty {
                    uhp.agents = summaries.map(AgentViewState.init)
                }
                uhp.errorMessage = nil
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func openAgent(_ id: String) async {
        uhp.selectedAgentID = id
        if let agent = uhp.agents.first(where: { $0.id == id }) ?? selectedHost?.agents.first(where: { $0.id == id }) {
            uhp.header = AgentHeaderState(
                paneId: agent.id,
                name: agent.name,
                kind: agent.kind,
                status: agent.status,
                isBlocked: agent.isBlocked,
                workspace: agent.workspace,
                branch: agent.branch,
                cwd: agent.cwd,
                missionUsage: uhp.header?.paneId == id ? uhp.header?.missionUsage : nil
            )
        }
        await refreshOpenAgent()
    }

    func refreshOpenAgent() async {
        guard let id = uhp.selectedAgentID, let session = liveSession() else { return }
        do {
            let outcome = try await session.getAgent(target: id)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    applyAgentGet(result)
                }
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
        await readTranscript(for: id)
        await loadMissionUsage(for: id)
    }

    func readTranscript(for target: String) async {
        guard uhp.caps.agentRead, let session = liveSession() else { return }
        do {
            let outcome = try await session.readAgent(target: target, lines: 200, source: .recent)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    uhp.transcript = result.text
                    uhp.transcriptRevision = kotlinInt64(result.revision)
                }
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func sendAgentPrompt() async {
        let text = uhp.composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let target = uhp.selectedAgentID else { return }
        await promptAgent(target: target, text: text)
        if uhp.unconfirmed == nil, uhp.errorMessage == nil {
            uhp.composerText = ""
        }
    }

    func promptAgent(target: String, text: String) async {
        guard uhp.caps.agentPrompt, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.promptAgent(
                target: target,
                text: text,
                wait: false,
                until: nil,
                timeoutSeconds: nil
            )
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await readTranscript(for: target)
            case .err(let err):
                handleMutationFailure(err.failure, action: .agentPrompt)
            }
        } catch {
            markUnconfirmed(.agentPrompt, error.localizedDescription)
        }
    }

    func sendAgentKeys(_ keys: [AgentKey]) async {
        guard uhp.caps.agentKeys, let target = uhp.selectedAgentID, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.sendAgentKeys(target: target, keys: keys)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await readTranscript(for: target)
            case .err(let err):
                handleMutationFailure(err.failure, action: .agentKeys)
            }
        } catch {
            markUnconfirmed(.agentKeys, error.localizedDescription)
        }
    }

    func checkUnconfirmed() async {
        switch uhp.unconfirmed {
        case .agentPrompt, .agentKeys:
            await refreshOpenAgent()
        case .sendNotes:
            await loadNotes()
        case .addTask, .completeTask:
            await loadTasks()
            if let id = uhp.unconfirmedTaskID {
                await refreshTaskRevision(id)
            }
        case nil:
            break
        }
        uhp.unconfirmed = nil
        uhp.unconfirmedTaskID = nil
    }

    func loadDiff() async {
        guard uhp.caps.diffList, let session = liveSession() else { return }
        do {
            let outcome = try await session.listDiff(layer: nil)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    uhp.diffBranch = result.branch
                    uhp.diffFiles = KotlinLists.array(result.files as Any).map { (file: DiffFile) in
                        DiffFileItem(
                            path: file.path,
                            layer: diffLayerLabel(file.layer),
                            additions: Int(kotlinInt64(file.additions) ?? 0),
                            deletions: Int(kotlinInt64(file.deletions) ?? 0)
                        )
                    }
                }
                uhp.errorMessage = nil
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
        if uhp.caps.diffNoteList {
            await loadNotes()
        }
    }

    func openDiffFile(_ item: DiffFileItem) async {
        guard uhp.caps.diffGet, let session = liveSession() else { return }
        do {
            let outcome = try await session.getDiff(
                path: item.path,
                layer: diffLayer(from: item.layer),
                includePatch: true
            )
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let file = ok.value {
                    uhp.selectedDiff = mapDiffDetail(file, fallback: item)
                }
                uhp.errorMessage = nil
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func beginAddNote(file: DiffFileItem, line: DiffLineItem) {
        uhp.addNote = AddNoteDraft(
            file: file.path,
            layer: file.layer,
            usesNewLine: line.newLine != nil,
            line: line.newLine ?? line.oldLine ?? 1,
            body: ""
        )
        uhp.isAddNotePresented = true
    }

    func addReviewNote() async {
        let draft = uhp.addNote
        let body = draft.body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, uhp.caps.diffNoteAdd, let session = liveSession() else { return }
        let line: ReviewLine = draft.usesNewLine
            ? ReviewLine.New(line: Int32(draft.line))
            : ReviewLine.Old(line: Int32(draft.line))
        do {
            let outcome = try await session.addReviewNote(
                file: draft.file,
                line: line,
                endLine: nil,
                body: body,
                kind: .issue,
                layer: diffLayer(from: draft.layer ?? "")
            )
            switch onEnum(of: outcome) {
            case .ok:
                uhp.isAddNotePresented = false
                uhp.addNote = AddNoteDraft()
                uhp.errorMessage = nil
                await loadNotes()
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func resolveNote(_ id: String) async {
        guard let session = liveSession() else { return }
        do {
            let outcome = try await session.resolveReviewNote(id: id)
            if case .err(let err) = onEnum(of: outcome) {
                uhp.errorMessage = FailureText.describe(err.failure)
            } else {
                await loadNotes()
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func reopenNote(_ id: String) async {
        guard let session = liveSession() else { return }
        do {
            let outcome = try await session.reopenReviewNote(id: id)
            if case .err(let err) = onEnum(of: outcome) {
                uhp.errorMessage = FailureText.describe(err.failure)
            } else {
                await loadNotes()
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func removeNote(_ id: String) async {
        guard let session = liveSession() else { return }
        do {
            let outcome = try await session.removeReviewNote(id: id)
            if case .err(let err) = onEnum(of: outcome) {
                uhp.errorMessage = FailureText.describe(err.failure)
            } else {
                await loadNotes()
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func sendReviewNotes(to target: String) async {
        guard uhp.caps.diffNoteSend, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.sendReviewNotes(to: target, ids: nil, allOpen: true)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    let destination = result.target ?? result.pane ?? target
                    uhp.sendNotesMessage = "Sent \(result.count) Review notes to \(destination)."
                }
                uhp.isSendNotesPresented = false
                uhp.errorMessage = nil
                await loadNotes()
            case .err(let err):
                handleMutationFailure(err.failure, action: .sendNotes)
            }
        } catch {
            markUnconfirmed(.sendNotes, error.localizedDescription)
        }
    }

    func loadTasks() async {
        guard uhp.caps.taskList, let session = liveSession() else {
            if let host = selectedHost {
                uhp.tasks = host.tasks
            }
            return
        }
        do {
            let outcome = try await session.listTasks()
            switch onEnum(of: outcome) {
            case .ok(let ok):
                let summaries: [TaskSummary] = KotlinLists.array(ok.value as Any)
                uhp.tasks = summaries.map(TaskViewState.init)
                uhp.errorMessage = nil
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    func addTask() async {
        let title = uhp.addTaskTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, uhp.caps.taskAdd, let session = liveSession() else { return }
        let paths = uhp.addTaskPaths
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.addTask(
                title: title,
                paths: paths,
                deps: [],
                gate: nil,
                ifRevision: nil
            )
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    storeTaskRevision(result.task.id, result.revision)
                }
                uhp.isAddTaskPresented = false
                uhp.addTaskTitle = ""
                uhp.addTaskPaths = ""
                uhp.boardChangedMessage = nil
                uhp.errorMessage = nil
                await loadTasks()
            case .err(let err):
                handleTaskMutationFailure(err.failure, action: .addTask, taskID: nil)
            }
        } catch {
            markUnconfirmed(.addTask, error.localizedDescription)
        }
    }

    func completeTask(_ id: String) async {
        guard uhp.caps.taskDone, let session = liveSession() else { return }
        if uhp.taskRevisions[id] == nil {
            await refreshTaskRevision(id)
        }
        let revision = uhp.taskRevisions[id].map { KotlinLong(longLong: $0) }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.completeTask(id: id, ifRevision: revision)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    storeTaskRevision(result.task.id, result.revision)
                }
                uhp.boardChangedMessage = nil
                uhp.errorMessage = nil
                await loadTasks()
            case .err(let err):
                handleTaskMutationFailure(err.failure, action: .completeTask, taskID: id)
            }
        } catch {
            uhp.unconfirmedTaskID = id
            markUnconfirmed(.completeTask, error.localizedDescription)
        }
    }

    private func loadNotes() async {
        guard uhp.caps.diffNoteList, let session = liveSession() else { return }
        do {
            let outcome = try await session.listReviewNotes(state: nil, file: nil)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                let notes: [ReviewNote] = KotlinLists.array(ok.value as Any)
                uhp.notes = notes.map(mapNote)
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
        }
    }

    private func loadMissionUsage(for paneId: String) async {
        guard uhp.caps.missionSnapshot, let session = liveSession() else { return }
        do {
            let outcome = try await session.missionSnapshot(scope: .all)
            if case .ok(let ok) = onEnum(of: outcome), let snapshot = ok.value {
                let rows: [MissionRow] = KotlinLists.array(snapshot.rows as Any)
                if let row = rows.first(where: { $0.pane == paneId }), let usage = row.usage {
                    uhp.header?.missionUsage = formatMissionUsage(usage)
                }
            }
        } catch {
            return
        }
    }

    private func refreshTaskRevision(_ id: String) async {
        guard let session = liveSession() else { return }
        do {
            let outcome = try await session.getTask(id: id)
            if case .ok(let ok) = onEnum(of: outcome), let result = ok.value {
                storeTaskRevision(result.task.id, result.revision)
            }
        } catch {
            return
        }
    }

    private func storeTaskRevision(_ id: String, _ revision: Any?) {
        if let value = kotlinInt64(revision) {
            uhp.taskRevisions[id] = value
        }
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

    private func applyAgentGet(_ result: AgentGetResult) {
        let kind = AgentStatusKind(result.status)
        if uhp.header == nil {
            uhp.header = AgentHeaderState(
                paneId: result.pane,
                name: result.name ?? result.agent ?? result.pane,
                kind: result.agent,
                status: kind.label,
                isBlocked: kind == .blocked,
                workspace: nil,
                branch: nil,
                cwd: result.cwd,
                missionUsage: nil
            )
        } else {
            uhp.header?.status = kind.label
            uhp.header?.isBlocked = kind == .blocked
            if let cwd = result.cwd { uhp.header?.cwd = cwd }
            if let name = result.name { uhp.header?.name = name }
            if let agent = result.agent { uhp.header?.kind = agent }
        }
    }

    private func handleMutationFailure(_ failure: Failure, action: UnconfirmedAction) {
        if isLostMutation(failure) {
            markUnconfirmed(action, nil)
            return
        }
        uhp.errorMessage = FailureText.describe(failure)
    }

    private func handleTaskMutationFailure(_ failure: Failure, action: UnconfirmedAction, taskID: String?) {
        switch onEnum(of: failure) {
        case .revisionConflict:
            uhp.boardChangedMessage = "Board changed, review and try again"
            _Concurrency.Task { await self.loadTasks() }
        case .indeterminateMutation, .transport, .bridge, .closed:
            uhp.unconfirmedTaskID = taskID
            markUnconfirmed(action, nil)
        default:
            uhp.errorMessage = FailureText.describe(failure)
        }
    }

    private func markUnconfirmed(_ action: UnconfirmedAction, _ fallback: String?) {
        uhp.unconfirmed = action
        uhp.errorMessage = fallback
    }

    private func isLostMutation(_ failure: Failure) -> Bool {
        switch onEnum(of: failure) {
        case .indeterminateMutation, .transport, .bridge, .closed:
            true
        default:
            false
        }
    }

    private func mapDiffDetail(_ file: DiffFile, fallback: DiffFileItem) -> DiffFileDetail {
        let item = DiffFileItem(
            path: file.path,
            layer: file.layer.map(diffLayerLabel) ?? fallback.layer,
            additions: Int(kotlinInt64(file.additions) ?? Int64(fallback.additions)),
            deletions: Int(kotlinInt64(file.deletions) ?? Int64(fallback.deletions))
        )
        let hunks: [DiffHunk] = KotlinLists.array(file.hunks as Any)
        return DiffFileDetail(
            item: item,
            hunks: hunks.enumerated().map { index, hunk in
                let lines: [DiffLine] = KotlinLists.array(hunk.lines as Any)
                return DiffHunkItem(
                    id: hunk.id.isEmpty ? "hunk-\(index)" : hunk.id,
                    header: hunk.header,
                    lines: lines.enumerated().map { lineIndex, line in
                        DiffLineItem(
                            id: "\(hunk.id)-\(lineIndex)",
                            kind: line.kind,
                            oldLine: kotlinInt64(line.oldLine).map(Int.init),
                            newLine: kotlinInt64(line.newLine).map(Int.init),
                            text: line.text
                        )
                    }
                )
            }
        )
    }

    private func mapNote(_ note: ReviewNote) -> ReviewNoteItem {
        let deliveries: [ReviewNoteDelivery] = KotlinLists.array(note.deliveries as Any)
        let deliveryText = deliveries.isEmpty
            ? nil
            : deliveries.map(\.target).joined(separator: ", ")
        let state = note.state
        return ReviewNoteItem(
            id: note.id,
            body: note.body,
            stateLabel: reviewStateLabel(state),
            isOpen: state == .open || state == nil,
            isResolved: state == .resolved,
            path: note.path,
            line: kotlinInt64(note.startLine).map(Int.init),
            deliveries: deliveryText
        )
    }

    private func reviewStateLabel(_ state: ReviewNoteState?) -> String {
        guard let state else { return "Open" }
        switch state {
        case .open: return "Open"
        case .resolved: return "Resolved"
        case .outdated: return "Outdated"
        case .orphaned: return "Orphaned"
        default: return "Open"
        }
    }

    private func formatMissionUsage(_ usage: MissionUsage) -> String {
        var parts: [String] = []
        if let model = usage.model, !model.isEmpty { parts.append(model) }
        let tokensIn = kotlinInt64(usage.tokensIn)
        let tokensOut = kotlinInt64(usage.tokensOut)
        if tokensIn != nil || tokensOut != nil {
            parts.append("\(tokensIn ?? 0) in / \(tokensOut ?? 0) out")
        }
        if let cost = usage.costUsd?.doubleValue {
            parts.append(String(format: "$%.2f", cost))
        }
        return parts.joined(separator: " · ")
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

