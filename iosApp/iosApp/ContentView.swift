import LuviaShared
import SwiftUI
import UIKit

struct ContentView: View {
    @Bindable var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var lastBlockedTotal = 0
    @State private var preferredCompactColumn = NavigationSplitViewColumn.sidebar
    @State private var pairingLandsOnHost = false
    @State private var hostChromeNonce = 0
    @State private var isHostsPresented = false

    var body: some View {
        @Bindable var model = model

        NavigationSplitView(preferredCompactColumn: $preferredCompactColumn) {
            HomeView(
                model: model,
                onOpenThread: openThread,
                onOpenProject: openProject,
                onOpenHosts: { isHostsPresented = true }
            )
            .toolbar(.hidden, for: .navigationBar)
        } detail: {
            if let host = model.selectedHost {
                HostDetailView(
                    host: host,
                    section: $model.selectedSection,
                    model: model,
                    compactColumn: preferredCompactColumn,
                    chromeNonce: hostChromeNonce
                )
                .id(host.id)
            } else {
                ContentUnavailableView(
                    "Nothing selected",
                    systemImage: "sparkles",
                    description: Text("Pick an Agent from Now.")
                )
            }
        }
        .onChange(of: model.selectedSection) { _, _ in
            model.handleSectionChange()
        }
        .onChange(of: model.selectedHostID) { _, _ in
            model.handleSectionChange()
        }
        .onChange(of: model.hosts) { _, hosts in
            let total = hosts.reduce(0) { $0 + $1.blockedAgents }
            if scenePhase == .active, total > lastBlockedTotal {
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
            }
            lastBlockedTotal = total
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                lastBlockedTotal = model.hosts.reduce(0) { $0 + $1.blockedAgents }
            }
        }
        .sheet(isPresented: $isHostsPresented) {
            HostsSheet(model: model) { id in
                model.selectedHostID = id
                preferredCompactColumn = .detail
                hostChromeNonce += 1
            }
        }
        .sheet(isPresented: $model.isPairingPresented, onDismiss: {
            if pairingLandsOnHost {
                preferredCompactColumn = .detail
                pairingLandsOnHost = false
            } else {
                // Same-value .sidebar writes are ignored; bounce so compact
                // actually shows Hosts after Cancel from the Hosts list.
                preferredCompactColumn = .detail
                _Concurrency.Task { @MainActor in
                    await _Concurrency.Task.yield()
                    preferredCompactColumn = .sidebar
                }
            }
        }) {
            PairHostView(model: model, onPaired: { pairingLandsOnHost = true })
        }
    }
}

extension ContentView {
    private func openThread(_ thread: AgentThread) {
        model.uhpRegistry.markViewed(thread: thread)
        model.selectedHostID = thread.hostId
        if thread.kind == .acp {
            model.uhpRegistry.workspace(hostId: thread.hostId).viewAcp()
        } else if let pane = thread.paneId {
            model.pendingOpenAgentID = pane
        }
        preferredCompactColumn = .detail
    }

    private func openProject(_ hostID: String, _ workspaceID: String?) {
        if let workspaceID {
            model.uhpRegistry.workspace(hostId: hostID).setSelectedWorkspace(id: workspaceID)
        }
        model.selectedHostID = hostID
        model.selectedSection = .agents
        preferredCompactColumn = .detail
        hostChromeNonce += 1
    }
}

#Preview {
    ContentView(model: AppModel())
}
