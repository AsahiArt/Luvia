import ActivityKit
import Foundation

@MainActor
final class LiveActivityController {
    private var activity: Activity<LuviaActivityAttributes>?
    var isActive: Bool { activity != nil }


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

    /// An alert turns a silent refresh into a Lock Screen banner, an expanded
    /// Dynamic Island presentation, and a wrist alert. ActivityKit grants no
    /// background runtime, so this only reaches the user when the app already has
    /// execution time.
    func update(
        _ state: LuviaActivityAttributes.ContentState,
        alert: AlertConfiguration? = nil
    ) async {
        await activity?.update(
            ActivityContent(state: state, staleDate: Date().addingTimeInterval(30)),
            alertConfiguration: alert
        )
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
