import ActivityKit
import Foundation

struct LuviaActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var connection: String
        var workingAgents: Int
        var blockedAgents: Int
        var completedAgents: Int
        var activeTask: String?
        var sensitiveSnippet: String?
        var updatedAt: Date
    }

    var hostName: String
    var sessionName: String
}
