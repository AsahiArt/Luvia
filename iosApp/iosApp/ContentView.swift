import SwiftUI
import UIKit

struct ContentView: View {
    @Bindable var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var lastBlockedTotal = 0
    @State private var preferredCompactColumn = NavigationSplitViewColumn.detail
    @State private var pairingLandsOnHost = false
    @State private var hostChromeNonce = 0

    var body: some View {
        @Bindable var model = model

        NavigationSplitView(preferredCompactColumn: $preferredCompactColumn) {
            HostSidebarView(
                hosts: model.hosts,
                selection: $model.selectedHostID,
                boundHostID: model.selectedHostID,
                boundAttentionCount: model.uhp.attentionCount,
                addHost: { model.isPairingPresented = true },
                onUnpair: { id in _Concurrency.Task { await model.unpair(id) } },
                onDisconnect: { id in model.disconnect(id) },
                onRefreshAll: { await model.refreshAll() },
                onOpenHost: { _ in
                    preferredCompactColumn = .detail
                    hostChromeNonce += 1
                }
            )
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
                    "Select a Host",
                    systemImage: "server.rack",
                    description: Text("Choose a paired host from the sidebar.")
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

#Preview {
    ContentView(model: AppModel())
}
