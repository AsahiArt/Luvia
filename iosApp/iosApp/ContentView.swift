import SwiftUI
import LuviaShared

struct ContentView: View {
    @State private var model = AppModel()

    var body: some View {
        @Bindable var model = model

        NavigationSplitView {
            HostSidebarView(
                hosts: model.hosts,
                selection: $model.selectedHostID,
                addHost: { model.isPairingPresented = true }
            )
        } detail: {
            if let host = model.selectedHost {
                HostDetailView(host: host, section: $model.selectedSection)
            } else {
                ContentUnavailableView(
                    "Select a Host",
                    systemImage: "server.rack",
                    description: Text("Choose a paired host from the sidebar.")
                )
            }
        }
        .sheet(isPresented: $model.isPairingPresented) {
            PairHostView { host, privateKey in
                model.addPairedHost(host, privateKey: privateKey)
            }
        }
    }
}

#Preview {
    ContentView()
}
