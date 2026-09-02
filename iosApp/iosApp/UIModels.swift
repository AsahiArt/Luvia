import Foundation
import LuviaShared


struct UserFacingError: Error, Equatable {
    let message: String
}

enum HostConnectionBadge: String, Sendable {
    case live = "Live"
    case connecting = "Connecting"
    case stale = "Stale"
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
}

struct TaskViewState: Identifiable, Hashable, Sendable {
    let id: String
    var title: String
    var status: String
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
                failure: FailureText.describe(failed.failure)
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
    case overview = "Overview"
    case agents = "Agents"
    case tasks = "Tasks"
    case terminal = "Terminal"

    var id: Self { self }

    var symbol: String {
        switch self {
        case .overview: "rectangle.3.group"
        case .agents: "person.2"
        case .tasks: "checklist"
        case .terminal: "terminal"
        }
    }
}

private func isStatus(_ status: AgentStatus, _ expected: AgentStatus) -> Bool {
    status == expected
}

private func agentStatusLabel(_ status: AgentStatus) -> String {
    switch status {
    case .idle: "Idle"
    case .working: "Working"
    case .blocked: "Blocked"
    case .done: "Done"
    case .unknown: "Unknown"
    }
}

enum FailureText {
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
            return value.message
        case .frameTooLarge(let value):
            return value.message
        case .serverBusy(let value):
            return value.message
        }
    }

    static func pairingAware(_ reason: String) -> String {
        if reason == "pairing code is for a different device key" {
            return "This pairing code belongs to a different device key. Run the command from the previous step on the host, then scan the QR it prints for this device."
        }
        return reason
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

private extension AgentViewState {
    init(_ summary: AgentSummary) {
        let label = summary.name
            ?? summary.agent
            ?? summary.workspaceName
            ?? summary.paneId
        self.init(
            id: summary.paneId,
            name: label,
            status: agentStatusLabel(summary.status),
            detail: summary.cwd ?? summary.workspace
        )
    }
}

private extension TaskViewState {
    init(_ summary: TaskSummary) {
        self.init(id: summary.id, title: summary.title, status: summary.status)
    }
}

