import Foundation

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

struct HostViewState: Identifiable, Hashable, Sendable {
    let id: UUID
    var name: String
    var address: String
    var sessionName: String?
    var connection: HostConnectionBadge
    var workingAgents: Int
    var blockedAgents: Int
    var completedAgents: Int
    var activeTask: String?
    var lastUpdated: Date?

    init(
        id: UUID = UUID(),
        name: String,
        address: String,
        sessionName: String? = nil,
        connection: HostConnectionBadge = .offline,
        workingAgents: Int = 0,
        blockedAgents: Int = 0,
        completedAgents: Int = 0,
        activeTask: String? = nil,
        lastUpdated: Date? = nil
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
