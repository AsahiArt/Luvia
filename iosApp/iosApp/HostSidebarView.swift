import SwiftUI
import UIKit

struct HostSidebarView: View {
    let hosts: [HostViewState]
    @Binding var selection: HostViewState.ID?
    let addHost: () -> Void
    let onUnpair: (String) -> Void
    var onDisconnect: (String) -> Void = { _ in }
    var onRefreshAll: () async -> Void = {}

    @State private var pendingUnpair: HostViewState?
    @State private var query = ""
    @State private var didCopyInstall = false

    private static let installCommand =
        "curl -fsSL https://raw.githubusercontent.com/AsahiArt/Luvia/main/scripts/install-host.sh | sh"

    private var visibleHosts: [HostViewState] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return hosts }
        return hosts.filter {
            $0.name.localizedCaseInsensitiveContains(trimmed)
                || $0.address.localizedCaseInsensitiveContains(trimmed)
        }
    }

    var body: some View {
        Group {
            if hosts.isEmpty {
                emptyHosts
            } else {
                hostList
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

    private var hostList: some View {
        List(visibleHosts, selection: $selection) { host in
            HostRow(host: host)
                .tag(host.id)
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    if host.connection == .live || host.connection == .connecting {
                        Button {
                            onDisconnect(host.id)
                        } label: {
                            Label("Disconnect", systemImage: "pause.circle")
                        }
                        .tint(.orange)
                    }
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) {
                        pendingUnpair = host
                    } label: {
                        Label("Unpair", systemImage: "trash")
                    }
                }
        }
        .refreshable { await onRefreshAll() }
        .modifier(ConditionalSearchable(text: $query, enabled: hosts.count >= 8, prompt: "Hosts"))
    }

    private var emptyHosts: some View {
        ContentUnavailableView {
            Label("No Hosts", systemImage: "server.rack")
        } description: {
            VStack(alignment: .leading, spacing: 10) {
                Text("Pair a Luvus host to begin.")
                Text("1. Install luvia-host on the machine.")
                Text("2. Run the pair command and scan the pairing code.")
                Text("3. Connect and work with Agents.")
                Text(Self.installCommand)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } actions: {
            Button("Add Host", action: addHost)
            Button {
                UIPasteboard.general.string = Self.installCommand
                didCopyInstall = true
            } label: {
                Label(
                    didCopyInstall ? "Copied" : "Copy install command",
                    systemImage: didCopyInstall ? "checkmark" : "doc.on.doc"
                )
            }
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
                HStack(spacing: 8) {
                    Text(host.name)
                        .font(.headline)
                    if host.blockedAgents > 0 {
                        Text("\(host.blockedAgents)")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .foregroundStyle(.white)
                            .background(Color.orange, in: Capsule())
                            .accessibilityLabel("\(host.blockedAgents) blocked")
                    }
                }
                Text(statusLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(host.address)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if let failure = host.failureMessage, !failure.isEmpty {
                    Text(failure)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lineLimit(2)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var statusLine: String {
        if let freshness = host.freshnessLabel {
            return "\(host.connection.rawValue) · \(freshness)"
        }
        return host.connection.rawValue
    }

    private var accessibilityText: String {
        var parts = [host.name, host.connection.rawValue]
        if host.blockedAgents > 0 {
            parts.append("\(host.blockedAgents) blocked")
        }
        if let freshness = host.freshnessLabel {
            parts.append(freshness)
        }
        return parts.joined(separator: ", ")
    }
}

struct ConditionalSearchable: ViewModifier {
    @Binding var text: String
    var enabled: Bool
    var prompt: String

    func body(content: Content) -> some View {
        if enabled {
            content.searchable(text: $text, prompt: prompt)
        } else {
            content
        }
    }
}
