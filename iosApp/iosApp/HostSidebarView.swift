import LuviaShared
import SwiftUI

struct HostSidebarView: View {
    let hosts: [HostViewState]
    @Binding var selection: HostViewState.ID?
    var boundHostID: String? = nil
    var boundAttentionCount = 0
    let addHost: () -> Void
    let onUnpair: (String) -> Void
    var onDisconnect: (String) -> Void = { _ in }
    var onRefreshAll: () async -> Void = {}
    var onOpenHost: (String) -> Void = { _ in }

    @State private var pendingUnpair: HostViewState?
    @State private var query = ""

    private var visibleHosts: [HostViewState] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = trimmed.isEmpty
            ? hosts
            : hosts.filter {
                $0.name.localizedCaseInsensitiveContains(trimmed)
                    || $0.address.localizedCaseInsensitiveContains(trimmed)
            }
        return filtered.enumerated()
            .sorted { lhs, rhs in
                let left = sortGroup(lhs.element)
                let right = sortGroup(rhs.element)
                if left != right { return left < right }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    private func attention(for host: HostViewState) -> Int {
        if host.id == boundHostID {
            return max(host.blockedAgents, boundAttentionCount)
        }
        return host.blockedAgents
    }

    private func sortGroup(_ host: HostViewState) -> Int {
        if attention(for: host) > 0 { return 0 }
        if host.connection == .live { return 1 }
        return 2
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
        .navigationBarTitleDisplayMode(.large)
        .luviaSerifLargeTitle()
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Add Host", systemImage: "plus", action: addHost)
            }
        }
        .modifier(ConditionalSearchable(text: $query, enabled: hosts.count >= 8, prompt: "Hosts"))
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
            HostRow(host: host, attentionCount: attention(for: host))
                .tag(host.id)
                .contentShape(Rectangle())
                .simultaneousGesture(TapGesture().onEnded { onOpenHost(host.id) })
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
            VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                EmptyHostStep(number: 1, text: "Install luvia-host on the machine that runs Luvus.")
                EmptyHostStep(number: 2, text: "Pair this Device from the app.")
                EmptyHostStep(number: 3, text: "Scan the pairing code the Host prints.")
            }
            .padding(.top, DesignTokens.Space.s)
            Button("Add Host", action: addHost)
                .font(.headline)
                .padding(.horizontal, DesignTokens.Space.l)
                .padding(.vertical, DesignTokens.Space.s)
                .luviaGlass(in: Capsule())
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
    var attentionCount = 0


    var body: some View {
        HStack(spacing: 12) {
            HostAvatar(name: host.name, connection: host.connection)
            VStack(alignment: .leading, spacing: 4) {
                Text(host.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(DesignTokens.ink)
                HStack(spacing: 6) {
                    Text(statusLine)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(1)
                    if ConnectKt.isTailnetAddress(address: host.address) {
                        TypeBadge(kind: .tailnet)
                    }
                    if host.backend == "herdr" {
                        TypeBadge(kind: .herdr)
                    }
                }
                if let failure = host.failureMessage, !failure.isEmpty {
                    Text(failure)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            if attentionCount > 0 {
                Text("Blocked \(attentionCount)")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .foregroundStyle(.white)
                    .background(DesignTokens.accent, in: Capsule())
                    .accessibilityLabel("\(attentionCount) blocked")
            }
        }
        .padding(.vertical, 4)
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var statusLine: String {
        var parts = [host.connection.statusLabel]
        if let freshness = host.freshnessLabel {
            parts.append(freshness)
        }
        if !host.address.isEmpty {
            parts.append(host.address)
        }
        return parts.joined(separator: " · ")
    }

    private var accessibilityText: String {
        var parts = [host.name, host.connection.statusLabel]
        if host.backend == "herdr" {
            parts.append("Herdr")
        }
        if attentionCount > 0 {
            parts.append("\(attentionCount) blocked")
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
            content.searchable(
                text: $text,
                placement: .navigationBarDrawer(displayMode: .automatic),
                prompt: prompt
            )
        } else {
            content
        }
    }
}
