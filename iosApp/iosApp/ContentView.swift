import SwiftUI


struct ContentView: View {
    @State private var model = AppModel()

    var body: some View {
        @Bindable var model = model

        NavigationSplitView {
            HostSidebarView(
                hosts: model.hosts,
                selection: $model.selectedHostID,
                addHost: { model.isPairingPresented = true },
                onUnpair: { id in _Concurrency.Task { await model.unpair(id) } }
            )
        } detail: {
            if let host = model.selectedHost {
                HostDetailView(
                    host: host,
                    section: $model.selectedSection,
                    terminalText: model.terminalText,
                    terminalStatus: model.terminalStatus,
                    onConnect: { model.connect(host.id) },
                    onDisconnect: { model.disconnect(host.id) },
                    onRefresh: { _Concurrency.Task { await model.refresh(host.id) } },
                    onSendTerminal: { text in _Concurrency.Task { await model.sendTerminal(text) } }
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
        .sheet(isPresented: $model.isPairingPresented) {
            PairHostView(model: model)
        }
    }
}

#Preview {
    ContentView()
}
