import SwiftUI
import LuviaShared

/// Host → Project. A Host's projects come from its workspaces and Agents; never merged across Hosts.
struct ProjectsView: View {
    @Bindable var model: AppModel
    let onOpenProject: (_ hostID: String, _ workspaceID: String?) -> Void

    var body: some View {
        Group {
            if model.hosts.isEmpty {
                EmptyState(title: "No Hosts", message: "Pair a Host to see its projects.", serif: true)
            } else {
                List {
                    ForEach(model.hosts) { host in
                        Section {
                            rows(for: host)
                        } header: {
                            HStack(spacing: 6) {
                                Text(host.name)
                                StatusPill.link(host.connection)
                                if !host.isController {
                                    Text("Observer").foregroundStyle(DesignTokens.inkMuted)
                                }
                            }
                            .font(.footnote.weight(.semibold))
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .background(DesignTokens.canvas.ignoresSafeArea())
        .navigationTitle("Projects")
        .navigationBarTitleDisplayMode(.large)
        .luviaSerifLargeTitle()
    }

    @ViewBuilder
    private func rows(for host: HostViewState) -> some View {
        let state = model.hostStates[host.id]
        let threads: [AgentThread] = KotlinLists.array(state?.threads(hostId: host.id) as Any)
        let choices: [ProjectChoice] = KotlinLists.array(state?.projectChoices() as Any)
        if choices.isEmpty {
            ProjectLine(title: "All threads", threads: threads) { onOpenProject(host.id, nil) }
        } else {
            ForEach(choices, id: \.id) { choice in
                ProjectLine(title: choice.label, threads: threads.filter { $0.projectKey == choice.id }) {
                    onOpenProject(host.id, choice.id)
                }
            }
        }
    }
}

private struct ProjectLine: View {
    let title: String
    let threads: [AgentThread]
    let action: () -> Void

    private var needsYou: Int { threads.filter(\.needsYou).count }

    var body: some View {
        Button(action: action) {
            HStack(spacing: DesignTokens.Space.s) {
                Text(title).font(.body.weight(.medium)).foregroundStyle(DesignTokens.ink)
                Spacer(minLength: 0)
                if needsYou > 0 {
                    Text("● \(needsYou)").font(.caption.weight(.semibold)).foregroundStyle(DesignTokens.agentBlocked)
                }
                Text("\(threads.count) threads").font(.caption).foregroundStyle(DesignTokens.inkMuted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
    }
}

/// Now and Projects, the two top-level destinations.
struct HomeView: View {
    @Bindable var model: AppModel
    let onOpenThread: (AgentThread) -> Void
    let onOpenProject: (_ hostID: String, _ workspaceID: String?) -> Void
    let onOpenHosts: () -> Void

    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack {
                NowView(model: model, onOpenThread: onOpenThread, onOpenHosts: onOpenHosts)
            }
                .tabItem { Label("Now", systemImage: "bolt.circle") }
                .badge(Int(model.now?.needsYouCount ?? 0))
                .tag(0)
            NavigationStack {
                ProjectsView(model: model, onOpenProject: onOpenProject)
            }
                .tabItem { Label("Projects", systemImage: "square.stack") }
                .tag(1)
        }
        .tint(DesignTokens.accent)
    }
}
