import ActivityKit
import SwiftUI
import WidgetKit

@main
struct LuviaWidgets: WidgetBundle {
    var body: some Widget {
        LuviaLiveActivity()
    }
}

struct LuviaLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LuviaActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label(context.attributes.hostName, systemImage: "server.rack")
                        .font(.headline)
                    Spacer()
                    Text(context.state.connection)
                        .font(.caption.weight(.semibold))
                }
                Text(context.attributes.sessionName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let snippet = context.state.sensitiveSnippet {
                    Text(snippet)
                        .font(.system(.caption, design: .monospaced))
                        .lineLimit(1)
                        .privacySensitive()
                } else if let task = context.state.activeTask {
                    Text(task).font(.caption).lineLimit(1)
                }
                HStack(spacing: 14) {
                    StatusCount(label: "Working", value: context.state.workingAgents)
                    StatusCount(label: "Blocked", value: context.state.blockedAgents)
                    StatusCount(label: "Done", value: context.state.completedAgents)
                }
            }
            .padding()
            .activityBackgroundTint(Color.black.opacity(0.88))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.hostName, systemImage: "server.rack")
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.connection).font(.caption.weight(.semibold))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if let snippet = context.state.sensitiveSnippet {
                        Text(snippet)
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)
                            .privacySensitive()
                    } else {
                        HStack {
                            StatusCount(label: "Working", value: context.state.workingAgents)
                            StatusCount(label: "Blocked", value: context.state.blockedAgents)
                        }
                    }
                }
            } compactLeading: {
                Image(systemName: "server.rack")
            } compactTrailing: {
                Text(context.state.workingAgents, format: .number)
                    .monospacedDigit()
            } minimal: {
                Image(systemName: context.state.blockedAgents > 0 ? "exclamationmark.circle.fill" : "bolt.horizontal.circle.fill")
            }
        }
    }
}

private struct StatusCount: View {
    let label: String
    let value: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value, format: .number).font(.headline).monospacedDigit()
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }
}
