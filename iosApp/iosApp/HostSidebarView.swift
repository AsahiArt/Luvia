import SwiftUI

struct HostSidebarView: View {
    let hosts: [HostViewState]
    @Binding var selection: HostViewState.ID?
    let addHost: () -> Void
    let onUnpair: (String) -> Void

    @State private var pendingUnpair: HostViewState?

    var body: some View {
        Group {
            if hosts.isEmpty {
                ContentUnavailableView(
                    "No Hosts",
                    systemImage: "server.rack",
                    description: Text("Pair a Luvus host to begin.")
                )
            } else {
                List(hosts, selection: $selection) { host in
                    HostRow(host: host)
                        .tag(host.id)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                pendingUnpair = host
                            } label: {
                                Label("Unpair", systemImage: "trash")
                            }
                        }
                }
            }
        }
        .navigationTitle("Luvia")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Add Host", systemImage: "plus", action: addHost)
            }
        }
        .confirmationDialog(
            "Unpair \(pendingUnpair?.name ?? "host")?",
            isPresented: Binding(
                get: { pendingUnpair != nil },
                set: { if !$0 { pendingUnpair = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Unpair", role: .destructive) {
                if let id = pendingUnpair?.id {
                    onUnpair(id)
                }
                pendingUnpair = nil
            }
            Button("Cancel", role: .cancel) {
                pendingUnpair = nil
            }
        } message: {
            Text("This device will no longer be able to connect until you pair again.")
        }
    }
}

private struct HostRow: View {
    let host: HostViewState

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: host.connection.symbol)
                .foregroundStyle(host.connection == .live ? Color.green : Color.secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(host.name)
                    .font(.headline)
                Text(host.address)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(host.name), \(host.connection.rawValue)")
    }
}
