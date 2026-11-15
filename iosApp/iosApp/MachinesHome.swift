import LuviaShared
import SwiftUI

#if DEBUG
enum MachinesPageFixture: String {
    static let key = "debug.machines-page-fixture"
    case off
    case empty
    case currentOffline
}
#endif

private func machineRouteWord(_ route: HostRoute) -> String {
    switch route {
    case .tailnet: "Tailnet"
    case .lan: "局域网"
    case .unknown: "未知"
    }
}

struct MachinesHome: View {
    @Bindable var model: AppModel
    var catalog: [AgentThread]
    var onlineHostIDs: Set<String>
    var latencyMs: Int?
    var pairError: String?
    var onPair: () -> Void
    var onScan: () -> Void
    var onFocus: (HostViewState, SessionScope, SessionFilter) -> Void
    /// List owner switches to the session tab and starts the session. Default does nothing.
    var onCreateSession: (HostViewState, String?) -> Void = { _, _ in }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #if DEBUG
    @AppStorage(MachinesPageFixture.key) private var machinesFixture = MachinesPageFixture.off.rawValue
    #endif
    @State private var detailHostID: String?
    @State private var expandedHosts: Set<String> = []
    @State private var reconnecting = false
    @State private var reconnectError: String?
    @State private var pendingRemovalID: String?
    @State private var orderCaptured = false
    @State private var hostOrder: [String] = []
    @State private var pathOrder: [String: [String]] = [:]

    private var needsYouColor: Color {
        colorScheme == .dark ? Color(hex: "FFB340") : Color(hex: "C2410C")
    }

    private var currentHost: HostViewState? {
        guard let id = model.selectedHostID else { return nil }
        return model.hosts.first { $0.id == id }
    }

    private var rankedOtherIDs: [String] {
        let others = model.hosts.filter { $0.id != currentHost?.id }
        let online = others.filter { !dimsOtherHost($0) }.sorted(by: newerHost)
        let offline = others.filter { dimsOtherHost($0) }.sorted(by: newerHost)
        return (online + offline).map(\.id)
    }

    private var otherHosts: [HostViewState] {
        let byID = Dictionary(model.hosts.map { ($0.id, $0) }, uniquingKeysWith: { current, _ in current })
        if !orderCaptured {
            return rankedOtherIDs.compactMap { byID[$0] }
        }
        var rows = hostOrder.compactMap { byID[$0] }.filter { $0.id != currentHost?.id }
        let seen = Set(rows.map(\.id))
        rows.append(contentsOf: model.hosts.filter { host in
            host.id != currentHost?.id && !seen.contains(host.id)
        })
        return rows
    }

    var body: some View {
        NavigationStack {
            Group {
                if showsEmpty {
                    empty
                } else {
                    list
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("机器")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                if !showsEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("扫码配对", systemImage: "qrcode.viewfinder", action: onScan)
                            Button("输入配对码", systemImage: "keyboard", action: onPair)
                        } label: {
                            Image(systemName: "plus")
                                .font(.body.weight(.semibold))
                                .frame(width: 36, height: 36)
                                .luviaGlass(in: Circle())
                        }
                        .accessibilityLabel("配对新电脑")
                    }
                }
            }
            .navigationDestination(isPresented: detailPresented) {
                if let detailHostID {
                    MachineDetail(hostId: detailHostID, model: model, onPair: onPair)
                }
            }
            .onAppear(perform: captureOrder)
        }
    }

    private var showsEmpty: Bool {
        if model.hosts.isEmpty { return true }
        #if DEBUG
        if machinesFixture == MachinesPageFixture.empty.rawValue { return true }
        #endif
        return false
    }

    private var detailPresented: Binding<Bool> {
        Binding(
            get: { detailHostID != nil },
            set: { if !$0 { detailHostID = nil } }
        )
    }

    private var removePresented: Binding<Bool> {
        Binding(
            get: { pendingRemovalID != nil },
            set: { if !$0 { pendingRemovalID = nil } }
        )
    }

    private var empty: some View {
        VStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.accentColor.opacity(colorScheme == .dark ? 0.20 : 0.15))
                .frame(width: 96, height: 96)
                .overlay {
                    Image(systemName: "laptopcomputer")
                        .font(.system(size: 44))
                        .foregroundStyle(Color.accentColor)
                }
                .accessibilityHidden(true)
            Text("还没有配对的电脑")
                .font(.title3.weight(.semibold))
                .padding(.top, 24)
            (
                Text("在电脑终端运行 ")
                    .font(.subheadline)
                + Text("luvia-host pair")
                    .font(.subheadline.monospaced())
            )
            .foregroundStyle(.secondary)
            .textSelection(.enabled)
            .accessibilityLabel("在电脑终端运行 luvia-host pair")
            .padding(.top, 8)
            Button(action: onPair) {
                Text("+ 配对新电脑")
                    .font(.headline)
                    .frame(width: 240, height: 50)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .padding(.top, 24)
            if let pairError {
                Text(pairError)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.top, 12)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .offset(y: -40)
    }

    private var list: some View {
        List {
            if let pairError {
                Text(pairError)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if let host = currentHost {
                Section {
                    mainCard(host)
                        .machineRow()
                        .machineActions(host: host, main: true, offline: false, model: model) {
                            pendingRemovalID = host.id
                        }
                }
            }
            if !otherHosts.isEmpty {
                Section {
                    ForEach(otherHosts) { host in
                        otherCard(host)
                            .opacity(dimsOtherHost(host) ? 0.55 : 1)
                            .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: dimsOtherHost(host))
                            .machineRow()
                            .machineActions(
                                host: host,
                                main: false,
                                offline: unavailable(host) || host.pairingRevoked,
                                model: model
                            ) {
                                pendingRemovalID = host.id
                            }
                    }
                } header: {
                    Text("其他电脑")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .textCase(nil)
                }
            }
        }
        .listStyle(.plain)
        .listRowSpacing(16)
        .scrollContentBackground(.hidden)
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: currentHost?.connection)
        .confirmationDialog("移除这台电脑？", isPresented: removePresented, titleVisibility: .visible) {
            Button("移除", role: .destructive) {
                guard let id = pendingRemovalID else { return }
                pendingRemovalID = nil
                _Concurrency.Task { await model.unpair(id) }
            }
            Button("取消", role: .cancel) {}
        }
    }

    private func mainCard(_ host: HostViewState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            hostHeader(host)
            if shownConnection(host) == .live {
                counts(host)
                livePaths(host)
            } else {
                reconnectRow(host)
            }
        }
        .padding(16)
        .machineGlass()
        .onChange(of: host.connection) { _, connection in
            guard reconnecting, !host.pairingRevoked else { return }
            if connection == .live {
                reconnecting = false
                reconnectError = nil
            } else if connection != .connecting {
                reconnecting = false
                reconnectError = host.failureMessage ?? "没连上"
            }
        }
        .onChange(of: host.failureMessage) { _, message in
            guard reconnecting, !host.pairingRevoked, host.connection != .live, host.connection != .connecting else { return }
            reconnecting = false
            reconnectError = message ?? "没连上"
        }
    }

    private func otherCard(_ host: HostViewState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            hostHeader(host)
            if shownConnection(host) == .live {
                livePaths(host)
            }
        }
        .padding(16)
        .background(
            Color(.secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 28, style: .continuous)
        )
    }

    @ViewBuilder
    private func livePaths(_ host: HostViewState) -> some View {
        if orderedPaths(for: host).isEmpty {
            noPathsRow(host)
        } else {
            pathList(host)
        }
    }

    private func hostHeader(_ host: HostViewState) -> some View {
        Button {
            detailHostID = host.id
        } label: {
            HStack(spacing: 12) {
                hostIcon(host)
                VStack(alignment: .leading, spacing: 2) {
                    Text(host.name)
                        .font(host.id == currentHost?.id ? .title3.weight(.semibold) : .headline)
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Circle()
                            .fill(statusDot(host))
                            .frame(width: 8, height: 8)
                            .alignmentGuide(.firstTextBaseline) { dimensions in
                                dimensions.height * 0.8
                            }
                            .accessibilityHidden(true)
                        Text(connectionLine(host))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(hostAccessibilityLabel(host))
        .accessibilityHint(host.pairingRevoked ? "重新配对" : "打开主机详情")
    }

    private func counts(_ host: HostViewState) -> some View {
        let waiting = count(host, status: .needsYou)
        let running = count(host, status: .running)
        let all = count(host, status: .all)
        let stack = dynamicTypeSize.isAccessibilitySize
        return Group {
            if stack {
                VStack(spacing: 8) {
                    countBlock("等你", waiting, needsYouColor, sideBySide: true) { focus(host, status: .needsYou) }
                    countBlock("运行中", running, Color.accentColor, sideBySide: true) { focus(host, status: .running) }
                    countBlock("会话", all, .primary, sideBySide: true) { focus(host, status: .all) }
                }
            } else {
                HStack(spacing: 8) {
                    countBlock("等你", waiting, needsYouColor, sideBySide: false) { focus(host, status: .needsYou) }
                    countBlock("运行中", running, Color.accentColor, sideBySide: false) { focus(host, status: .running) }
                    countBlock("会话", all, .primary, sideBySide: false) { focus(host, status: .all) }
                }
            }
        }
    }

    private func countBlock(
        _ title: String,
        _ value: Int,
        _ tint: Color,
        sideBySide: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Group {
                if sideBySide {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        countNumber(value, tint)
                        countLabel(title)
                        Spacer(minLength: 0)
                    }
                } else {
                    VStack(alignment: .leading, spacing: 2) {
                        countNumber(value, tint)
                        countLabel(title)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .frame(minHeight: 64)
            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(CountPressStyle())
        .accessibilityLabel(countAccessibility(title, value))
        .accessibilityHint(countHint(title))
    }

    private func pathList(_ host: HostViewState) -> some View {
        let paths = orderedPaths(for: host)
        let expanded = expandedHosts.contains(host.id)
        let shown = expanded ? paths : Array(paths.prefix(3))
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(shown, id: \.cwd) { option in
                pathRow(host, option)
            }
            if paths.count > 3, !expanded {
                Button("更多") { expandedHosts.insert(host.id) }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
                    .buttonStyle(.plain)
                    .frame(minHeight: 44)
                    .accessibilityLabel("更多路径")
            }
        }
    }

    private func pathRow(_ host: HostViewState, _ option: SessionPathOption) -> some View {
        let cwd = option.cwd
        let total = kotlinInt(option.count) ?? 0
        let waiting = count(host, cwd: cwd, status: .needsYou)
        let threads = orderedMarks(markThreads(host, cwd: cwd))
        let shown = Array(threads.prefix(3))
        let extra = max(0, threads.count - shown.count)
        let accessibleMarks = markValue(threads)
        return Button {
            openPath(host, cwd: cwd)
        } label: {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 4) {
                        pathTitle(cwd, host: host)
                        HStack(spacing: 8) {
                            markCluster(shown, extra: extra)
                            if waiting > 0 { waitingCapsule(waiting) }
                            Spacer(minLength: 0)
                            pathChevron
                        }
                    }
                } else {
                    HStack(spacing: 8) {
                        Image(systemName: "folder.fill")
                            .foregroundStyle(Color.accentColor)
                            .accessibilityHidden(true)
                        pathTitle(cwd, host: host)
                        Spacer(minLength: 8)
                        markCluster(shown, extra: extra)
                        if waiting > 0 { waitingCapsule(waiting) }
                        pathChevron
                    }
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(PathPressStyle())
        .contextMenu {
            Button("在此新建会话") { onCreateSession(host, cwd) }
        }
        .swipeActions(edge: .trailing) {
            Button("在此新建会话") { onCreateSession(host, cwd) }
                .tint(.accentColor)
        }
        .accessibilityLabel(pathLabel(host: host, cwd: cwd, total: total, waiting: waiting))
        .accessibilityValue(accessibleMarks)
        .accessibilityHint("在会话中查看")
        .accessibilityAction(named: "在此新建会话") { onCreateSession(host, cwd) }
    }

    private func pathTitle(_ cwd: String, host: HostViewState) -> some View {
        Text(relativePath(cwd, host: host))
            .font(.body.monospaced())
            .foregroundStyle(.primary)
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
            .truncationMode(.middle)
            .multilineTextAlignment(.leading)
    }

    private var pathChevron: some View {
        Image(systemName: "chevron.right")
            .font(.caption.weight(.semibold))
            .foregroundStyle(.tertiary)
            .accessibilityHidden(true)
    }

    private func markCluster(_ shown: [AgentThread], extra: Int) -> some View {
        HStack(spacing: 4) {
            HStack(spacing: -8) {
                ForEach(Array(shown.enumerated()), id: \.offset) { index, thread in
                    pathMark(thread)
                        .zIndex(Double(shown.count - index))
                }
            }
            .accessibilityHidden(true)
            if extra > 0 {
                Text("+\(extra)")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func waitingCapsule(_ waiting: Int) -> some View {
        Text("\(waiting) 等你")
            .font(.caption2.weight(.semibold))
            .foregroundStyle(needsYouColor)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(needsYouColor.opacity(0.20), in: Capsule())
    }

    private func noPathsRow(_ host: HostViewState) -> some View {
        HStack(spacing: 8) {
            Text("没有打开的路径")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Button("新建会话") { onCreateSession(host, nil) }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.accentColor)
                .buttonStyle(.plain)
                .accessibilityHint("在会话中新建")
        }
        .frame(minHeight: 44)
    }

    private func reconnectRow(_ host: HostViewState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if host.pairingRevoked {
                Button(action: onPair) {
                    Text("重新配对")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Color.accentColor, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("重新配对")
            } else {
                Button {
                    reconnect(host)
                } label: {
                    HStack(spacing: 8) {
                        if showsReconnectProgress(host) {
                            ProgressView()
                                .controlSize(.small)
                                .tint(.white)
                        }
                        Text("重新连接")
                            .font(.body.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Color.accentColor, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("重新连接")
                if let reconnectError {
                    Text(reconnectError)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                } else if host.connection != .connecting,
                          let message = host.failureMessage,
                          !message.isEmpty {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func reconnect(_ host: HostViewState) {
        if host.pairingRevoked { return }
        #if DEBUG
        if machinesFixture == MachinesPageFixture.currentOffline.rawValue { return }
        #endif
        reconnecting = true
        reconnectError = nil
        model.connect(host.id)
    }

    private func shownConnection(_ host: HostViewState) -> HostConnectionBadge {
        #if DEBUG
        if machinesFixture == MachinesPageFixture.currentOffline.rawValue, host.id == currentHost?.id {
            return .offline
        }
        #endif
        return host.connection
    }

    private func dimsOtherHost(_ host: HostViewState) -> Bool {
        let connection = shownConnection(host)
        return connection != .live && connection != .connecting
    }

    private func unavailable(_ host: HostViewState) -> Bool {
        let connection = shownConnection(host)
        return connection == .offline || connection == .stale
    }

    private func showsReconnectProgress(_ host: HostViewState) -> Bool {
        if host.pairingRevoked { return false }
        if reconnecting { return true }
        #if DEBUG
        if machinesFixture == MachinesPageFixture.currentOffline.rawValue { return false }
        #endif
        return host.connection == .connecting
    }

    private func hostIcon(_ host: HostViewState) -> some View {
        let quiet = unavailable(host) || host.pairingRevoked
        return RoundedRectangle(cornerRadius: 14, style: .continuous)
            .fill(quiet ? Color(.systemGray5) : Color.accentColor)
            .frame(width: 48, height: 48)
            .overlay {
                Image(systemName: "laptopcomputer")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(quiet ? Color.secondary : Color.white)
            }
            .accessibilityHidden(true)
    }

    private func statusDot(_ host: HostViewState) -> Color {
        if host.pairingRevoked { return Color(.systemGray) }
        switch shownConnection(host) {
        case .live: return Color(hex: "34C759")
        case .connecting: return Color.secondary
        case .stale, .offline: return Color(.systemGray)
        }
    }

    @ViewBuilder
    private func countNumber(_ value: Int, _ tint: Color) -> some View {
        if reduceMotion {
            Text("\(value)")
                .font(.title2)
                .foregroundStyle(tint)
        } else {
            Text("\(value)")
                .font(.title2)
                .foregroundStyle(tint)
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.3), value: value)
        }
    }

    private func countLabel(_ title: String) -> some View {
        Text(title)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .lineLimit(2)
    }

    private func focus(_ host: HostViewState, status: SessionFilter) {
        onFocus(host, HostFilterBridge.currentHost(), status)
    }

    private func openPath(_ host: HostViewState, cwd: String) {
        onFocus(host, HostFilterBridge.path(cwd), .all)
    }

    private func count(_ host: HostViewState, status: SessionFilter) -> Int {
        HostFilterBridge.sessions(
            threads: catalog,
            hostId: host.id,
            scope: HostFilterBridge.currentHost(),
            status: status,
            online: onlineHostIDs
        ).count
    }

    private func count(_ host: HostViewState, cwd: String, status: SessionFilter) -> Int {
        HostFilterBridge.sessions(
            threads: catalog,
            hostId: host.id,
            scope: HostFilterBridge.path(cwd),
            status: status,
            online: onlineHostIDs
        ).count
    }

    private func connectionLine(_ host: HostViewState) -> String {
        if host.pairingRevoked { return revokedCopy(host) }
        switch shownConnection(host) {
        case .live:
            var parts: [String] = []
            let current = host.id == currentHost?.id
            if current { parts.append("已连接") }
            if let route = host.route {
                parts.append(machineRouteWord(route))
            }
            if current, let latencyMs {
                parts.append("\(latencyMs) ms")
            }
            return parts.isEmpty ? "已连接" : parts.joined(separator: " · ")
        case .connecting:
            return "连接中"
        case .stale, .offline:
            guard let last = host.lastUpdated else { return "离线" }
            return offlineLine(last)
        }
    }

    private func spokenLive(_ host: HostViewState) -> String {
        var parts = ["已连接"]
        if let route = host.route {
            parts.append(machineRouteWord(route))
        }
        if host.id == currentHost?.id, let latencyMs {
            parts.append("延迟 \(latencyMs) 毫秒")
        }
        return parts.joined(separator: "，")
    }

    private func hostAccessibilityLabel(_ host: HostViewState) -> String {
        let name = host.id == currentHost?.id ? "\(host.name)，当前电脑" : host.name
        if host.pairingRevoked { return "\(name)，\(revokedCopy(host))" }
        if unavailable(host) {
            guard let last = host.lastUpdated else { return "\(name)，离线" }
            return "\(name)，离线，上次在线 \(offlineAmount(last))前"
        }
        if shownConnection(host) == .connecting { return "\(name)，连接中" }
        return "\(name)，\(spokenLive(host))"
    }

    private func revokedCopy(_ host: HostViewState) -> String {
        let message = host.failureMessage?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if message.isEmpty { return FailureMessagesKt.PROVE_REJECTED_ZH }
        return message
    }

    private func offlineLine(_ date: Date) -> String {
        "离线 · 上次 \(offlineAmount(date))前"
    }

    private func offlineAmount(_ date: Date) -> String {
        let seconds = max(0, Int(Date().timeIntervalSince(date)))
        if seconds < 3600 {
            return "\(max(1, seconds / 60)) 分钟"
        }
        if seconds < 86400 {
            return "\(max(1, seconds / 3600)) 小时"
        }
        return "\(max(1, seconds / 86400)) 天"
    }

    private func relativePath(_ cwd: String, host: HostViewState) -> String {
        let paths = catalog.compactMap { $0.hostId == host.id ? $0.cwd : nil }
        let home = HostFilterBridge.homeDirectory(username: host.user, paths: paths)
        return HostFilterBridge.relative(cwd, home: home)
    }

    private func spokenPath(_ cwd: String, host: HostViewState) -> String {
        relativePath(cwd, host: host).replacingOccurrences(of: "~", with: "主目录")
    }

    private func pathLabel(host: HostViewState, cwd: String, total: Int, waiting: Int) -> String {
        var label = "\(spokenPath(cwd, host: host))，\(total) 个会话"
        if waiting > 0 { label += "，\(waiting) 个等你" }
        return label
    }

    private func countAccessibility(_ title: String, _ value: Int) -> String {
        switch title {
        case "等你": "\(value) 个会话等你决定"
        case "运行中": "\(value) 个会话运行中"
        default: "\(value) 个会话"
        }
    }

    private func countHint(_ title: String) -> String {
        switch title {
        case "等你": "打开会话并筛选等你"
        case "运行中": "打开会话并筛选运行中"
        default: "打开会话并筛选全部"
        }
    }

    private func markThreads(_ host: HostViewState, cwd: String) -> [AgentThread] {
        HostFilterBridge.sessions(
            threads: catalog,
            hostId: host.id,
            scope: HostFilterBridge.path(cwd),
            status: .all,
            online: onlineHostIDs
        )
    }

    private func orderedMarks(_ threads: [AgentThread]) -> [AgentThread] {
        KotlinLists.array(SessionListKt.sortSessions(threads: threads) as Any)
    }

    private func markValue(_ threads: [AgentThread]) -> String {
        let names = threads.prefix(3).map(spokenAgent)
        guard !names.isEmpty else { return "" }
        if threads.count > names.count {
            return names.joined(separator: "、") + " 等 \(threads.count) 个 agent"
        }
        return names.joined(separator: "、")
    }

    private func spokenAgent(_ thread: AgentThread) -> String {
        let logo = SessionListKt.agentBrandLogo(agentType: thread.agentType, title: thread.title)
        switch logo?.assetId {
        case "claude": return "Claude Code"
        case "openai": return "Codex"
        case "lobe-grok": return "Grok"
        case "pi": return "pi"
        default:
            let avatar = SessionListKt.sessionAvatar(agentType: thread.agentType, name: thread.title)
            let mark = avatar.mark.trimmingCharacters(in: .whitespacesAndNewlines)
            return mark.isEmpty ? thread.title : mark
        }
    }

    private func orderedPaths(for host: HostViewState) -> [SessionPathOption] {
        let fresh = HostFilterBridge.paths(threads: catalog, hostId: host.id)
        guard let frozen = pathOrder[host.id] else { return fresh }
        var byCwd: [String: SessionPathOption] = [:]
        for option in fresh {
            if byCwd[option.cwd] == nil { byCwd[option.cwd] = option }
        }
        var rows = frozen.compactMap { byCwd[$0] }
        let seen = Set(rows.map(\.cwd))
        rows.append(contentsOf: fresh.filter { !seen.contains($0.cwd) })
        return rows
    }

    private func captureOrder() {
        guard !orderCaptured else { return }
        orderCaptured = true
        hostOrder = rankedOtherIDs
        var map: [String: [String]] = [:]
        for host in model.hosts {
            map[host.id] = HostFilterBridge.paths(threads: catalog, hostId: host.id).map(\.cwd)
        }
        pathOrder = map
    }

    private func newerHost(_ lhs: HostViewState, _ rhs: HostViewState) -> Bool {
        let left = lhs.lastUpdated ?? .distantPast
        let right = rhs.lastUpdated ?? .distantPast
        if left != right { return left > right }
        return lhs.id < rhs.id
    }

    @ViewBuilder
    private func pathMark(_ thread: AgentThread) -> some View {
        let logo = SessionListKt.agentBrandLogo(agentType: thread.agentType, title: thread.title)
        if let logo, let name = brandAssetName(logo.assetId) {
            Image(name)
                .resizable()
                .renderingMode(logo.preservesColor ? .original : .template)
                .scaledToFit()
                .frame(width: 14, height: 14)
                .padding(4)
                .background(Circle().fill(logo.preservesColor ? Color(.systemBackground) : Color(hex: logo.backgroundHex)))
                .foregroundStyle(Color(hex: logo.foregroundHex))
        } else {
            let avatar = SessionListKt.sessionAvatar(agentType: thread.agentType, name: thread.title)
            Text(avatar.mark)
                .font(.system(size: 9, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Circle().fill(Color.accentColor))
        }
    }

    private func brandAssetName(_ assetId: String) -> String? {
        switch assetId {
        case "claude": "agent-logo-claude"
        case "openai": "agent-logo-openai"
        case "lobe-grok": "agent-logo-lobe-grok"
        case "pi": "agent-logo-pi"
        default: nil
        }
    }
}

private struct MachineDetail: View {
    var hostId: String
    @Bindable var model: AppModel
    var onPair: () -> Void
    @State private var confirmRemove = false

    private var host: HostViewState? {
        model.hosts.first { $0.id == hostId }
    }

    var body: some View {
        Form {
            if let host {
                Section {
                    LabeledContent("连接", value: host.pairingRevoked ? revokedCopy(host) : connectionLabel(host.connection))
                    if let route = host.route {
                        LabeledContent("线路", value: machineRouteWord(route))
                    }
                    if !host.address.isEmpty {
                        LabeledContent("地址", value: host.address)
                    }
                    if !host.backend.isEmpty {
                        LabeledContent("后端", value: host.backend)
                    }
                }
                Section {
                    if host.pairingRevoked {
                        Button("重新配对", action: onPair)
                    } else {
                        Button("重新连接") { model.connect(host.id) }
                    }
                    Button("断开") { model.disconnect(host.id) }
                    Button("移除", role: .destructive) { confirmRemove = true }
                }
            } else {
                Text("这台电脑不在了")
            }
        }
        .navigationTitle(host?.name ?? "电脑")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("移除这台电脑？", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("移除", role: .destructive) {
                _Concurrency.Task { await model.unpair(hostId) }
            }
            Button("取消", role: .cancel) {}
        }
    }

    private func revokedCopy(_ host: HostViewState) -> String {
        let message = host.failureMessage?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if message.isEmpty { return FailureMessagesKt.PROVE_REJECTED_ZH }
        return message
    }
}

private struct CountPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.97 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: configuration.isPressed)
    }
}

private struct PathPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(
                        configuration.isPressed
                            ? Color(red: 120 / 255, green: 120 / 255, blue: 128 / 255).opacity(0.16)
                            : Color.clear
                    )
            }
            .animation(.easeOut(duration: 0.25), value: configuration.isPressed)
    }
}

private extension View {
    func machineRow() -> some View {
        listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    @ViewBuilder
    func machineActions(
        host: HostViewState,
        main: Bool,
        offline: Bool,
        model: AppModel,
        remove: @escaping () -> Void
    ) -> some View {
        if main {
            contextMenu {
                Button("断开") { model.disconnect(host.id) }
                Button("移除", role: .destructive, action: remove)
            }
            .accessibilityAction(named: "断开") { model.disconnect(host.id) }
            .accessibilityAction(named: "移除", remove)
        } else if offline {
            swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button("移除", role: .destructive, action: remove)
            }
            .accessibilityAction(named: "移除", remove)
        } else {
            swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button("断开") { model.disconnect(host.id) }
                    .tint(.gray)
            }
            .accessibilityAction(named: "断开") { model.disconnect(host.id) }
        }
    }

    @ViewBuilder
    func machineGlass() -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular, in: RoundedRectangle.rect(cornerRadius: 28))
        } else {
            background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        }
    }
}
