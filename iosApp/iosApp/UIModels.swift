import Foundation
import LuviaShared


struct UserFacingError: Error, Equatable {
    let message: String
}

enum HostConnectionBadge: String, Sendable {
    case live = "Live"
    case connecting = "Connecting"
    case stale = "Reconnect"
    case offline = "Offline"

    var symbol: String {
        switch self {
        case .live: "bolt.horizontal.circle.fill"
        case .connecting: "arrow.trianglehead.2.clockwise.rotate.90.circle"
        case .stale: "clock.badge.exclamationmark"
        case .offline: "circle.dashed"
        }
    }
}

struct AgentViewState: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var status: String
    var detail: String?
    var statusKind: AgentStatusKind
    var kind: String?
    var workspace: String?
    var branch: String?
    var cwd: String?
    var isBlocked: Bool { statusKind == .blocked }
}

enum AgentStatusKind: String, Hashable, Sendable {
    case idle
    case working
    case blocked
    case done
    case unknown

    init(_ status: AgentStatus) {
        switch status {
        case .idle: self = .idle
        case .working: self = .working
        case .blocked: self = .blocked
        case .done: self = .done
        case .unknown: self = .unknown
        default: self = .unknown
        }
    }

    var label: String {
        switch self {
        case .idle: "Idle"
        case .working: "Working"
        case .blocked: "Blocked"
        case .done: "Done"
        case .unknown: "Unknown"
        }
    }
}

struct TaskViewState: Identifiable, Hashable, Sendable {
    let id: String
    var title: String
    var status: String
}

enum UnconfirmedAction: Hashable, Sendable {
    case agentPrompt
    case agentKeys
    case resumeAgent
    case forkAgent
    case nameAgent
    case sendNotes
    case addNote
    case resolveNote
    case reopenNote
    case removeNote
    case addTask
    case completeTask
    case claimTask
    case deleteTask
    case openFile
    case revealFile
    case activateSearch
    case createWorktree
    case openWorktree
    case removeWorktree
    case enableAutomation
    case disableAutomation
    case runAutomation
    case focusPane
    case closePane
    case closeWorkspace

    var title: String { "Unconfirmed" }

    var detail: String {
        switch self {
        case .agentPrompt:
            "This Agent prompt may have been delivered. Check the Transcript."
        case .agentKeys:
            "These Agent keys may have been delivered. Check the Transcript."
        case .resumeAgent:
            "This Agent session may have been resumed. Check Agents."
        case .forkAgent:
            "This Agent may have been forked. Check Agents."
        case .nameAgent:
            "This Agent may have been named. Check Agents."
        case .sendNotes:
            "Send notes may have reached the Agent. Check Review notes."
        case .addNote:
            "This Review note may have been added. Check Review notes."
        case .resolveNote:
            "This Review note may have been resolved. Check Review notes."
        case .reopenNote:
            "This Review note may have been reopened. Check Review notes."
        case .removeNote:
            "This Review note may have been removed. Check Review notes."
        case .addTask:
            "The Task may have been added. Check the board."
        case .completeTask:
            "The Task may have been completed. Check the board."
        case .claimTask:
            "The Task may have been claimed. Check the board."
        case .deleteTask:
            "The Task may have been deleted. Check the board."
        case .openFile:
            "This file may have been opened on the Host. Check Files."
        case .revealFile:
            "This file may have been revealed on the Host. Check Files."
        case .activateSearch:
            "This search result may have been activated. Check Search."
        case .createWorktree:
            "The Worktree may have been created. Check Worktrees."
        case .openWorktree:
            "The Worktree may have been opened. Check Worktrees."
        case .removeWorktree:
            "The Worktree may have been removed. Check Worktrees."
        case .enableAutomation:
            "The Automation may have been enabled. Check Automations."
        case .disableAutomation:
            "The Automation may have been disabled. Check Automations."
        case .runAutomation:
            "The Automation may have been run. Check Automations."
        case .focusPane:
            "The Pane may have been focused. Check Layout."
        case .closePane:
            "The Pane may have been closed. Check Layout."
        case .closeWorkspace:
            "The workspace may have been closed. Check Layout."
        }
    }
}

struct UhpCaps: Equatable, Sendable {
    var agentRead = false
    var agentPrompt = false
    var agentKeys = false
    var agentSessions = false
    var agentResume = false
    var agentFork = false
    var agentName = false
    var missionSnapshot = false
    var diffList = false
    var diffGet = false
    var diffNoteList = false
    var diffNoteAdd = false
    var diffNoteSend = false
    var diffNoteResolve = false
    var diffNoteReopen = false
    var diffNoteRemove = false
    var taskList = false
    var taskAdd = false
    var taskDone = false
    var taskClaim = false
    var taskDelete = false
    var filesTree = false
    var filesOpen = false
    var filesReveal = false
    var searchQuery = false
    var searchActivate = false
    var worktreeList = false
    var worktreeCreate = false
    var worktreeOpen = false
    var worktreeRemove = false
    var automationList = false
    var automationEnable = false
    var automationDisable = false
    var automationRun = false
    var automationHealth = false
    var paneList = false
    var paneFocus = false
    var paneClose = false
    var workspaceList = false
    var workspaceClose = false
}

enum MoreSurface: String, Identifiable, Hashable, Sendable {
    case files
    case search
    case worktrees
    case automations
    case layout

    var id: String { rawValue }

    var title: String {
        switch self {
        case .files: "Files"
        case .search: "Search"
        case .worktrees: "Worktrees"
        case .automations: "Automations"
        case .layout: "Layout"
        }
    }

    var symbol: String {
        switch self {
        case .files: "folder"
        case .search: "magnifyingglass"
        case .worktrees: "arrow.triangle.branch"
        case .automations: "clock.arrow.2.circlepath"
        case .layout: "rectangle.split.3x1"
        }
    }
}

struct FileTreeRowItem: Identifiable, Hashable, Sendable {
    var id: String { path }
    var path: String
    var name: String
    var depth: Int
    var isDirectory: Bool
    var isExpanded: Bool
}

struct SearchMatchItem: Identifiable {
    let id: String
    var kind: String
    var label: String
    var detail: String?
    let match: SearchMatch
}

struct WorktreeItem: Identifiable, Hashable, Sendable {
    var id: String { path }
    var path: String
    var branch: String?
    var head: String?
    var isMain: Bool
}

struct AutomationItem: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var enabled: Bool
    var state: String?
    var nextRun: String?
    var latestStatus: String?
    var latestError: String?
}

struct WorkspaceItem: Identifiable, Hashable, Sendable {
    var id: String { "ws-\(index)" }
    var index: Int
    var name: String
    var isActive: Bool
    var isPinned: Bool
    var cwd: String?
    var branch: String?
    var tabCount: Int
}

struct PaneItem: Identifiable, Hashable, Sendable {
    var id: String { pane }
    var pane: String
    var agent: String?
    var status: String
    var isFocused: Bool
    var cwd: String?
}

struct AgentSessionItem: Identifiable, Hashable, Sendable {
    var id: String { sessionId }
    var agent: String
    var sessionId: String
    var cwd: String
}

struct DiffFileItem: Identifiable, Hashable, Sendable {
    var id: String { "\(layer)|\(path)" }
    var path: String
    var layer: String
    var additions: Int
    var deletions: Int
    var isDirectory: Bool { path.hasSuffix("/") || path.hasSuffix("\\") }
}

struct DiffLineItem: Identifiable, Hashable, Sendable {
    let id: String
    var kind: String
    var oldLine: Int?
    var newLine: Int?
    var text: String
}

struct DiffHunkItem: Identifiable, Hashable, Sendable {
    let id: String
    var header: String
    var lines: [DiffLineItem]
}

struct DiffFileDetail: Identifiable, Hashable, Sendable {
    var id: String { item.id }
    var item: DiffFileItem
    var hunks: [DiffHunkItem]
}

struct ReviewNoteItem: Identifiable, Hashable, Sendable {
    let id: String
    var body: String
    var stateLabel: String
    var isOpen: Bool
    var isResolved: Bool
    var path: String?
    var line: Int?
    var deliveries: String?
}

struct AddNoteDraft: Hashable, Sendable {
    var file = ""
    var layer: String?
    var usesNewLine = true
    var line = 1
    var body = ""
    var anchoredText = ""
    var contextBefore: [String] = []
    var contextAfter: [String] = []
}

struct AgentHeaderState: Hashable, Sendable {
    var paneId: String
    var name: String
    var kind: String?
    var status: String
    var isBlocked: Bool
    var workspace: String?
    var branch: String?
    var cwd: String?
    var missionUsage: String?
}

struct TerminalLocator: Hashable, Sendable {
    var serverGeneration: String
    var terminalId: String
    var paneId: String

    func identity() -> TerminalIdentity {
        TerminalIdentity(serverGeneration: serverGeneration, terminalId: terminalId, paneId: paneId)
    }
}

struct HostViewState: Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var address: String
    var addresses: [String]
    var sshPort: Int32
    var username: String
    var sessionName: String?
    var connection: HostConnectionBadge
    var workingAgents: Int
    var blockedAgents: Int
    var completedAgents: Int
    var activeTask: String?
    var lastUpdated: Date?
    var isController: Bool
    var failureMessage: String?
    var agents: [AgentViewState]
    var tasks: [TaskViewState]
    var terminalLocator: TerminalLocator?

    init(
        id: String,
        name: String,
        address: String,
        addresses: [String] = [],
        sshPort: Int32 = 22,
        username: String = "",
        sessionName: String? = nil,
        connection: HostConnectionBadge = .offline,
        workingAgents: Int = 0,
        blockedAgents: Int = 0,
        completedAgents: Int = 0,
        activeTask: String? = nil,
        lastUpdated: Date? = nil,
        isController: Bool = false,
        failureMessage: String? = nil,
        agents: [AgentViewState] = [],
        tasks: [TaskViewState] = [],
        terminalLocator: TerminalLocator? = nil
    ) {
        self.id = id
        self.name = name
        self.address = address
        self.addresses = addresses
        self.sshPort = sshPort
        self.username = username
        self.sessionName = sessionName
        self.connection = connection
        self.workingAgents = workingAgents
        self.blockedAgents = blockedAgents
        self.completedAgents = completedAgents
        self.activeTask = activeTask
        self.lastUpdated = lastUpdated
        self.isController = isController
        self.failureMessage = failureMessage
        self.agents = agents
        self.tasks = tasks
        self.terminalLocator = terminalLocator
    }

    init(_ runtime: HostRuntime) {
        let profile = runtime.profile
        let snapshot = runtime.snapshot
        let agentSummaries: [AgentSummary]
        let taskSummaries: [TaskSummary]
        if let snapshot {
            agentSummaries = KotlinLists.array(snapshot.agents)
            taskSummaries = KotlinLists.array(runtime.tasks)
        } else if let topology = profile.topology {
            agentSummaries = KotlinLists.array(topology.agents)
            taskSummaries = KotlinLists.array(topology.tasks)
        } else {
            agentSummaries = []
            taskSummaries = KotlinLists.array(runtime.tasks)
        }

        let link = HostViewState.linkPresentation(runtime)
        let agents = agentSummaries.map { AgentViewState($0) }
        let tasks = taskSummaries.map { TaskViewState($0) }
        let locator = HostViewState.locator(from: snapshot)

        self.init(
            id: profile.id,
            name: profile.alias,
            address: link.address,
            addresses: KotlinLists.array(profile.addresses as Any),
            sshPort: profile.sshPort,
            username: profile.username,
            sessionName: link.sessionName,
            connection: link.badge,
            workingAgents: agentSummaries.filter { isStatus($0.status, .working) }.count,
            blockedAgents: agentSummaries.filter { isStatus($0.status, .blocked) }.count,
            completedAgents: agentSummaries.filter { isStatus($0.status, .done) }.count,
            activeTask: tasks.first { $0.status.lowercased() != "done" }?.title,
            lastUpdated: profile.lastUpdatedEpochMs > 0
                ? Date(timeIntervalSince1970: TimeInterval(profile.lastUpdatedEpochMs) / 1000)
                : nil,
            isController: profile.role == HostRole.controller,
            failureMessage: link.failure,
            agents: agents,
            tasks: tasks,
            terminalLocator: locator
        )
    }

    func activityState() -> LuviaActivityAttributes.ContentState {
        LuviaActivityAttributes.ContentState(
            connection: connection.rawValue,
            workingAgents: workingAgents,
            blockedAgents: blockedAgents,
            completedAgents: completedAgents,
            activeTask: activeTask,
            sensitiveSnippet: nil,
            updatedAt: lastUpdated ?? Date()
        )
    }

    private struct LinkPresentation {
        var badge: HostConnectionBadge
        var address: String
        var sessionName: String?
        var failure: String?
    }

    private static func linkPresentation(_ runtime: HostRuntime) -> LinkPresentation {
        let profile = runtime.profile
        let fallbackAddress = profile.lastConnectedAddress
            ?? KotlinLists.array(profile.addresses).first
            ?? ""
        switch onEnum(of: runtime.link) {
        case .idle(_):
            return LinkPresentation(
                badge: runtime.freshness == ConnectionFreshness.stale ? .stale : .offline,
                address: fallbackAddress,
                sessionName: runtime.snapshot?.sessionName ?? profile.topology?.sessionName,
                failure: nil
            )
        case .connecting(_):
            return LinkPresentation(
                badge: .connecting,
                address: fallbackAddress,
                sessionName: runtime.snapshot?.sessionName ?? profile.topology?.sessionName,
                failure: nil
            )
        case .online(let online):
            let badge: HostConnectionBadge = runtime.freshness == ConnectionFreshness.stale ? .stale : .live
            return LinkPresentation(
                badge: badge,
                address: online.address,
                sessionName: online.sessionName,
                failure: nil
            )
        case .failed(let failed):
            return LinkPresentation(
                badge: .offline,
                address: fallbackAddress,
                sessionName: runtime.snapshot?.sessionName ?? profile.topology?.sessionName,
                failure: FailureText.connectActionable(failed.failure)
            )
        }
    }

    private static func locator(from snapshot: SessionSnapshot?) -> TerminalLocator? {
        guard let snapshot else { return nil }
        let panes: [PaneSummary] = KotlinLists.array(snapshot.panes)
        let pane = panes.first { $0.focused && $0.terminalId != nil }
            ?? panes.first { $0.terminalId != nil }
        guard let pane, let terminalId = pane.terminalId else { return nil }
        return TerminalLocator(
            serverGeneration: snapshot.serverGeneration,
            terminalId: terminalId,
            paneId: pane.paneId
        )
    }
}

enum HostSection: String, CaseIterable, Identifiable, Sendable {
    case agents = "Agents"
    case review = "Review"
    case tasks = "Tasks"
    case terminal = "Terminal"

    var id: Self { self }

    var symbol: String {
        switch self {
        case .agents: "person.2"
        case .review: "plus.forwardslash.minus"
        case .tasks: "checklist"
        case .terminal: "terminal"
        }
    }
}

private func isStatus(_ status: AgentStatus, _ expected: AgentStatus) -> Bool {
    status == expected
}

func agentStatusLabel(_ status: AgentStatus) -> String {
    AgentStatusKind(status).label
}

func diffLayerLabel(_ layer: DiffLayer?) -> String {
    guard let layer else { return "Other" }
    switch layer {
    case .staged: return "Staged"
    case .worktree: return "Worktree"
    case .untracked: return "Untracked"
    case .conflict: return "Conflict"
    default: return "Other"
    }
}

func diffLayer(from label: String) -> DiffLayer? {
    switch label {
    case "Staged": .staged
    case "Worktree": .worktree
    case "Untracked": .untracked
    case "Conflict": .conflict
    default: nil
    }
}

func kotlinInt64(_ value: Any?) -> Int64? {
    if let value = value as? Int64 { return value }
    if let value = value as? Int32 { return Int64(value) }
    if let value = value as? Int { return Int64(value) }
    if let value = value as? NSNumber { return value.int64Value }
    return nil
}

enum FailureText {
    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        let text = error.localizedDescription
        return text.localizedCaseInsensitiveContains("cancelled")
            || text.localizedCaseInsensitiveContains("canceled")
            || text.localizedCaseInsensitiveContains("CancellationException")
    }

    static func describe(_ failure: Failure) -> String {
        switch onEnum(of: failure) {
        case .frame(let value):
            return value.reason
        case .protocolError(let value):
            return pairingAware(value.reason)
        case .unknownMajor(let value):
            return "Unsupported protocol \(value.name) \(value.major)"
        case .capabilityMissing(let value):
            return "Host does not support \(value.method)"
        case .remote(let value):
            return value.message
        case .indeterminateMutation(let value):
            return "Unconfirmed change (\(value.method))"
        case .bridge(let value):
            return value.reason
        case .transport(let value):
            return value.reason
        case .closed(_):
            return "Connection closed"
        case .revisionConflict(let value):
            return value.message
        case .contentRevisionConflict(_):
            return "The agent screen changed. Refresh and send the keys again."
        case .agentPromptBusy(_):
            return "The agent is still handling a previous message. Wait for it to finish."
        case .forbidden(let value):
            return value.message
        case .notFound(let value):
            return value.message
        case .invalidParams(let value):
            return value.message
        case .invalidRequest(let value):
            return value.message
        case .staleServer(let value):
            return value.message
        case .staleRoute(let value):
            return value.message
        case .terminalGone(let value):
            return value.message
        case .resyncRequired(let value):
            return value.message
        case .controlConflict(let value):
            let message = value.message
            if message.localizedCaseInsensitiveContains("observe") {
                return message
            }
            return "\(message) Observe still works."
        case .frameTooLarge(let value):
            return value.message
        case .serverBusy(let value):
            return value.message
        }
    }

    static func pairingAware(_ reason: String) -> String {
        let draftNote = " The draft is still valid."
        if reason == "pairing code is for a different device key" {
            return "This pairing code is for a different Device key. Run the command from the previous step on the Host, then scan the QR it prints." + draftNote
        }
        if reason == "pairing code must start with luvia1:" {
            return "This is not a luvia1: pairing code. Scan or paste a luvia1: code." + draftNote
        }
        if reason == "pairing code has no host key fingerprints" {
            return "This pairing code has an empty host key (hk) set. Generate a new pairing code on the Host." + draftNote
        }
        if reason.hasPrefix("pairing code") {
            return "This pairing code is malformed. Scan again or paste a different luvia1: code." + draftNote
        }
        return reason
    }

    static func connectActionable(_ failure: Failure) -> String {
        let raw: String
        switch onEnum(of: failure) {
        case .protocolError(let value):
            raw = value.reason
        case .transport(let value):
            raw = value.reason
        case .bridge(let value):
            raw = value.reason
        default:
            raw = describe(failure)
        }
        let lower = raw.lowercased()
        if lower.contains("host key") || lower.contains("re-pair") {
            return "Host key changed. Re-pair this Device."
        }
        if lower.contains("authentication")
            || lower.contains("public-key")
            || lower.contains("permission denied")
        {
            return "SSH refused. Check this Device's Grant on the Host."
        }
        if lower.contains("timed out")
            || lower.contains("timeout")
            || lower.contains("unreachable")
            || lower.contains("no route")
            || lower.contains("network is down")
            || lower.contains("could not connect")
            || lower.contains("connection refused")
            || lower.contains("no address")
            || lower.contains("failed to resolve")
            || lower.contains("unknown host")
            || lower.contains("host is down")
        {
            return "No address reachable. Check network or Tailscale."
        }
        return raw
    }
}

enum KotlinLists {
    static func array<T>(_ value: Any?) -> [T] {
        if let typed = value as? [T] {
            return typed
        }
        if let array = value as? NSArray {
            return array.compactMap { $0 as? T }
        }
        return []
    }
}

extension AgentViewState {
    init(_ summary: AgentSummary) {
        let kind = AgentStatusKind(summary.status)
        let label = summary.name
            ?? summary.agent
            ?? summary.workspaceName
            ?? summary.paneId
        self.init(
            id: summary.paneId,
            name: label,
            status: kind.label,
            detail: summary.cwd ?? summary.workspace,
            statusKind: kind,
            kind: summary.agent,
            workspace: summary.workspaceName ?? summary.workspace,
            branch: summary.branch,
            cwd: summary.cwd
        )
    }
}

extension TaskViewState {
    init(_ summary: TaskSummary) {
        self.init(id: summary.id, title: summary.title, status: summary.status)
    }
}

extension HostViewState {
    var freshnessLabel: String? {
        guard let lastUpdated else { return nil }
        let seconds = max(0, Int(Date().timeIntervalSince(lastUpdated)))
        let value: String
        if seconds < 60 {
            value = "\(seconds)s"
        } else if seconds < 3600 {
            value = "\(seconds / 60)m"
        } else if seconds < 86400 {
            value = "\(seconds / 3600)h"
        } else {
            value = "\(seconds / 86400)d"
        }
        return "synced \(value) ago"
    }
}

