import SwiftUI
import UIKit

struct ContentView: View {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var lastBlockedTotal = 0

    var body: some View {
        @Bindable var model = model

        NavigationSplitView {
            HostSidebarView(
                hosts: model.hosts,
                selection: $model.selectedHostID,
                addHost: { model.isPairingPresented = true },
                onUnpair: { id in _Concurrency.Task { await model.unpair(id) } },
                onDisconnect: { id in model.disconnect(id) },
                onRefreshAll: { await model.refreshAll() }
            )
        } detail: {
            if let host = model.selectedHost {
                HostDetailView(
                    host: host,
                    section: $model.selectedSection,
                    model: model,
                    terminalText: model.terminalText,
                    terminalStatus: model.terminalStatus,
                    holdsTerminalControl: model.holdsTerminalControl,
                    onSendTerminal: { text in _Concurrency.Task { await model.sendTerminal(text) } },
                    onSendTerminalKey: { key in _Concurrency.Task { await model.sendTerminalKey(key) } },
                    onRequestControl: { model.requestTerminalControl() }
                )
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
        .sheet(isPresented: $model.isPairingPresented) {
            PairHostView(model: model)
        }
    }
}

#Preview {
    ContentView()
}
