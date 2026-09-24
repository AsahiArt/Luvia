import SwiftUI
import LuviaShared

/// Home: who needs you, who is working, what just finished — across every Host.
struct NowView: View {
    @Bindable var model: AppModel
    let onOpenThread: (AgentThread) -> Void
    let onOpenHosts: () -> Void

    private var now: NowState? { model.now }

    var body: some View {
        Group {
            if model.hosts.isEmpty {
                EmptyState(
                    title: "No Hosts",
                    message: "Pair a machine running Luvus to see its Agents here.",
                    serif: true,
                    actionTitle: "Add host",
                    action: { model.isPairingPresented = true }
                )
            } else if let now, !now.isQuiet {
                list(now)
            } else {
                VStack(spacing: DesignTokens.Space.s) {
                    Text("All clear").font(DesignTokens.Typography.title).foregroundStyle(DesignTokens.ink)
                    Text("No Agent needs you right now.").font(.subheadline).foregroundStyle(DesignTokens.inkMuted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(DesignTokens.canvas.ignoresSafeArea())
        .navigationTitle("Now")
        .navigationBarTitleDisplayMode(.large)
        .luviaSerifLargeTitle()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(action: onOpenHosts) {
                    Image(systemName: "person.crop.circle")
                }
                .accessibilityLabel("Hosts and settings")
            }
        }
        .refreshable { await model.refreshAll() }
    }

    private func list(_ now: NowState) -> some View {
        List {
            section("Needs you", groups(now.needsYou)) { group, thread in
                VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                    ThreadLine(thread: thread) { onOpenThread(thread) }
                    if let ask = thread.ask {
                        AttentionCard(
                            ask: ask,
                            canAnswer: !group.isObserver && group.freshness != .offline,
                            observer: group.isObserver,
                            onAnswer: { model.uhpRegistry.answer(thread: thread, option: $0) }
                        )
                    }
                }
                .padding(.bottom, DesignTokens.Space.s)
            }
            section("In progress", groups(now.inProgress)) { _, thread in
                ThreadLine(thread: thread) { onOpenThread(thread) }
            }
            section("Just finished", groups(now.recentlyDone)) { _, thread in
                ThreadLine(thread: thread) { onOpenThread(thread) }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func groups(_ value: Any) -> [NowGroup] { KotlinLists.array(value) }

    @ViewBuilder
    private func section(
        _ title: String,
        _ groups: [NowGroup],
        @ViewBuilder row: @escaping (NowGroup, AgentThread) -> some View
    ) -> some View {
        if !groups.isEmpty {
            let count = groups.reduce(0) { total, group in
                let threads: [AgentThread] = KotlinLists.array(group.threads as Any)
                return total + threads.count
            }
            Section {
                ForEach(groups, id: \.hostId) { group in
                    HostGroupHeader(group: group)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    let threads: [AgentThread] = KotlinLists.array(group.threads as Any)
                    ForEach(threads, id: \.id) { thread in
                        row(group, thread)
                            .listRowBackground(Color.clear)
                    }
                }
            } header: {
                Text("\(title)  \(count)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(DesignTokens.inkMuted)
            }
        }
    }
}

struct HostGroupHeader: View {
    let group: NowGroup

    var body: some View {
        HStack(spacing: 6) {
            Text(group.hostName).font(.footnote.weight(.semibold)).foregroundStyle(DesignTokens.ink)
            Text(glyph).font(.footnote).foregroundStyle(color)
            if group.isObserver {
                Text("Observer").font(.caption).foregroundStyle(DesignTokens.inkMuted)
            }
        }
    }

    private var glyph: String {
        switch group.freshness {
        case .live: "●"
        case .stale: "◌ stale"
        default: "○ offline"
        }
    }

    private var color: Color {
        switch group.freshness {
        case .live: DesignTokens.linkLive
        case .stale: DesignTokens.linkStale
        default: DesignTokens.linkOffline
        }
    }
}

struct ThreadLine: View {
    let thread: AgentThread
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: DesignTokens.Space.s) {
                    Text([thread.title, thread.projectLabel].compactMap { $0 }.joined(separator: " · "))
                        .font(.body.weight(.medium))
                        .foregroundStyle(DesignTokens.ink)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    StatusGlyph(status: thread.status)
                }
                if thread.ask == nil, let summary = thread.summary {
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Hosts and pairing, reached from the avatar on Now.
struct HostsSheet: View {
    @Bindable var model: AppModel
    let onOpenHost: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            HostSidebarView(
                hosts: model.hosts,
                selection: .constant(model.selectedHostID),
                boundHostID: model.selectedHostID,
                boundAttentionCount: model.uhp.attentionCount,
                addHost: {
                    dismiss()
                    model.isPairingPresented = true
                },
                onUnpair: { id in _Concurrency.Task { await model.unpair(id) } },
                onDisconnect: { id in model.disconnect(id) },
                onRefreshAll: { await model.refreshAll() },
                onOpenHost: { id in
                    dismiss()
                    onOpenHost(id)
                }
            )
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
