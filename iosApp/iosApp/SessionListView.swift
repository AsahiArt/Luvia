import LuviaShared
import SwiftUI

struct SessionListView: View {
    @Bindable var model: AppModel
    var threads: [AgentThread]
    var pending: [AgentThread]
    var filter: SessionFilter
    var onFilter: (SessionFilter) -> Void
    var onOpen: (AgentThread) -> Void
    var onAnswer: (AgentThread, AskOption) -> Void
    var onPair: () -> Void
    var onNewSession: () -> Void
    var onTerminal: () -> Void
    var onSettings: () -> Void
    var glass: Namespace.ID

    var body: some View {
        let host = model.selectedHost
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(model.hosts) { item in
                        Button(item.name) { model.select(item) }
                    }
                    Button("Pair a computer", action: onPair)
                    if host != nil {
                        Button("Computer settings", action: onSettings)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(host?.name ?? "No computer")
                            .font(.headline)
                        Text(host?.address ?? "Pair a computer")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(">_")
                    .font(.system(.body, design: .monospaced).weight(.bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .terminalGlass(namespace: glass)
                    .onTapGesture(perform: onTerminal)
                Button(action: onNewSession) {
                    Image(systemName: "plus")
                        .font(.title3.weight(.semibold))
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel("New session")
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            if !pending.isEmpty {
                Button(action: { onFilter(.needsYou) }) {
                    HStack {
                        Text("\(pending.count) waiting for a reply")
                        Spacer()
                        Text("Show")
                    }
                    .padding(14)
                    .luviaGlass(in: RoundedRectangle(cornerRadius: 18, style: .continuous), tint: ChatPalette.tertiary)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    filterChip("All", .all)
                    filterChip("Needs you", .needsYou)
                    filterChip("Running", .running)
                    filterChip("Idle", .idle)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            if !pending.isEmpty && filter != .running && filter != .idle {
                stackedDecisions
            }
            if threads.isEmpty {
                ContentUnavailableView(
                    host == nil ? "Pair a computer" : "No sessions",
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text("New sessions use +. Pane agents still start on the computer.")
                )
            } else {
                List(threads, id: \.id) { thread in
                    Button {
                        onOpen(thread)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(thread.title).font(.headline)
                                Spacer()
                                Text(sessionLabel(thread.status))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            let subtitle = [thread.projectLabel, thread.agentType].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                            if !subtitle.isEmpty {
                                Text(subtitle).font(.caption).foregroundStyle(.secondary)
                            }
                            if let summary = thread.summary, !summary.isEmpty {
                                Text(summary).font(.subheadline).lineLimit(2)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private var stackedDecisions: some View {
        ZStack {
            if pending.count > 1 {
                decisionCard(pending[1], index: 2, total: pending.count)
                    .padding(.horizontal, 28)
                    .offset(y: 14)
                    .opacity(0.55)
            }
            decisionCard(pending[0], index: 1, total: pending.count)
                .padding(.horizontal, 16)
        }
        .padding(.bottom, pending.count > 1 ? 24 : 8)
    }

    private func decisionCard(_ thread: AgentThread, index: Int, total: Int) -> some View {
        let ask = thread.ask
        let options: [AskOption] = KotlinLists.array(ask?.options as Any)
        return VStack(alignment: .leading, spacing: 8) {
            Text("\(index) / \(total)").font(.caption.weight(.semibold))
            Text(ask?.question ?? thread.title).font(.headline).lineLimit(3)
            HStack {
                ForEach(Array(options.prefix(2).enumerated()), id: \.offset) { _, option in
                    Button(option.label) { onAnswer(thread, option) }
                        .buttonStyle(.bordered)
                        .disabled(model.selectedHost?.isController != true)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous), tint: ChatPalette.tertiary)
    }

    private func filterChip(_ title: String, _ value: SessionFilter) -> some View {
        Button(title) { onFilter(value) }
            .buttonStyle(.bordered)
            .tint(filter == value ? ChatPalette.seed : .secondary)
    }
}

func sessionLabel(_ status: AgentStatus) -> String {
    switch status {
    case .idle: "Idle"
    case .working: "Running"
    case .blocked: "Needs you"
    case .done: "Done"
    default: "Unknown"
    }
}
