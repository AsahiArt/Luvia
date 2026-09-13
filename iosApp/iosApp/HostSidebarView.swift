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
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
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
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(DesignTokens.surface)
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    if host.connection == .live || host.connection == .connecting {
                        Button {
                            onDisconnect(host.id)
                        } label: {
                            Label("Disconnect", systemImage: "pause.circle")
                        }
                        .tint(DesignTokens.stale)
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
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .listRowBackground(Color.clear)
        .background(DesignTokens.canvas)
        .refreshable { await onRefreshAll() }
        .modifier(ConditionalSearchable(text: $query, enabled: hosts.count >= 8, prompt: "Hosts"))
    }

    private var emptyHosts: some View {
        ContentUnavailableView {
            Label {
                Text("No Hosts")
                    .font(DesignTokens.Typography.title)
                    .foregroundStyle(DesignTokens.ink)
            } icon: {
                Image(systemName: "laptopcomputer.and.iphone")
                    .foregroundStyle(DesignTokens.accent)
            }
        } description: {
            Text("Install luvia-host on your computer, then pair this phone.")
        } actions: {
            VStack(alignment: .leading, spacing: DesignTokens.Space.m) {
                EmptyHostStep(number: 1, text: "Install luvia-host on the machine that runs Luvus.")
                EmptyHostStep(number: 2, text: "Pair this Device from the app.")
                EmptyHostStep(number: 3, text: "Scan the pairing code the Host prints.")
                Text(Self.installCommand)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(DesignTokens.ink)
                    .textSelection(.enabled)
                    .padding(DesignTokens.Space.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .luviaGlass(in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
            }
            .padding(.top, DesignTokens.Space.s)
            Button("Add Host", action: addHost)
                .font(.headline)
                .padding(.horizontal, DesignTokens.Space.l)
                .padding(.vertical, DesignTokens.Space.s)
                .luviaGlass(in: Capsule())
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DesignTokens.canvas)
    }
}

private struct EmptyHostStep: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 22, height: 22)
                .background(.fill.tertiary, in: Circle())
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct HostRow: View {
    let host: HostViewState

    var body: some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Text(String(host.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1)).uppercased())
                    .font(.headline)
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(DesignTokens.accent, in: Circle())
                Circle()
                    .fill(statusColor)
                    .frame(width: 11, height: 11)
                    .overlay(Circle().stroke(DesignTokens.surface, lineWidth: 2))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(host.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(DesignTokens.ink)
                Text(statusLine)
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.inkMuted)
                    .lineLimit(1)
                if let failure = host.failureMessage, !failure.isEmpty {
                    Text(failure)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            if host.blockedAgents > 0 {
                Text("\(host.blockedAgents)")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .foregroundStyle(.white)
                    .background(DesignTokens.accent, in: Capsule())
                    .accessibilityLabel("\(host.blockedAgents) blocked")
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var statusColor: Color {
        switch host.connection {
        case .live: DesignTokens.live
        case .connecting: DesignTokens.connecting
        case .stale: DesignTokens.stale
        case .offline: DesignTokens.offline
        }
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
