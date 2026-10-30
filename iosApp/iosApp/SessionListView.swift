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
                    Button("配对电脑", action: onPair)
                    if host != nil {
                        Button("电脑设置", action: onSettings)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(host?.name ?? "没有电脑")
                            .font(.headline)
                        Text(host?.address ?? "配对一台电脑")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
                .tint(.blue)
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
                .tint(.blue)
                .accessibilityLabel("新会话")
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            if !pending.isEmpty {
                Button(action: { onFilter(.needsYou) }) {
                    HStack {
                        Text("等你决定 · \(pending.count)")
                        Spacer()
                        Text("查看")
                    }
                    .padding(14)
                    .luviaGlass(in: RoundedRectangle(cornerRadius: 18, style: .continuous), tint: .blue)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    filterChip("全部", .all)
                    filterChip("等你决定", .needsYou)
                    filterChip("运行中", .running)
                    filterChip("空闲", .idle)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            if !pending.isEmpty && filter != .running && filter != .idle {
                stackedDecisions
            }
            if threads.isEmpty {
                ContentUnavailableView(
                    host == nil ? "配对一台电脑" : "没有会话",
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text("新会话用顶栏 +。电脑上的 agent 仍由主机创建。")
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
                        .tint(.blue)
                        .disabled(model.selectedHost?.isController != true)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous), tint: .blue)
    }

    private func filterChip(_ title: String, _ value: SessionFilter) -> some View {
        Button(title) { onFilter(value) }
            .buttonStyle(.bordered)
            .tint(filter == value ? .blue : .secondary)
    }
}

func sessionLabel(_ status: AgentStatus) -> String {
    switch status {
    case .idle: "空闲"
    case .working: "运行中"
    case .blocked: "等你决定"
    case .done: "已完成"
    default: "未知"
    }
}

func connectionLabel(_ badge: HostConnectionBadge) -> String {
    switch badge {
    case .live: "在线"
    case .connecting: "连接中"
    case .stale: "重连"
    case .offline: "离线"
    }
}
