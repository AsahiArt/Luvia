import ActivityKit
import Foundation

@MainActor
final class LiveActivityController {
    private var activity: Activity<LuviaActivityAttributes>?

    func start(
        hostName: String,
        sessionName: String,
        state: LuviaActivityAttributes.ContentState
    ) throws {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(30))
        activity = try Activity.request(
            attributes: LuviaActivityAttributes(hostName: hostName, sessionName: sessionName),
            content: content,
            pushType: nil
        )
    }

    func update(_ state: LuviaActivityAttributes.ContentState) async {
        await activity?.update(ActivityContent(state: state, staleDate: Date().addingTimeInterval(30)))
    }

    func markStale(_ state: LuviaActivityAttributes.ContentState) async {
        await activity?.update(ActivityContent(state: state, staleDate: Date()))
    }

    func end(_ state: LuviaActivityAttributes.ContentState?) async {
        let finalContent = state.map { ActivityContent(state: $0, staleDate: Date()) }
        await activity?.end(finalContent, dismissalPolicy: .immediate)
        activity = nil
    }
}
