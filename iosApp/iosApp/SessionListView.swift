import LuviaShared
import SwiftUI
import UIKit

private struct RoutedThread: Identifiable {
    let thread: AgentThread
    let id: String
}

private enum HomeTab: Hashable {
    case sessions
    case machines
    case settings
}

private extension SessionListView {
    static let decide = Color(hex: "FF9500")
    static let done = Color(hex: "34C759")
    static let segmentTrack = Color(red: 120 / 255, green: 120 / 255, blue: 128 / 255).opacity(0.12)
}

struct SessionListView: View {
    @Bindable var model: AppModel
    var threads: [AgentThread]
    var pending: [AgentThread]
    var filter: HostSessionFilter
    var roster: SessionRoster
    var scopePaths: [SessionPathOption]
    var scopeSubtitle: String
    var sessionListLoading: Bool = false
    var homeDir: String? = nil
    var filtersHighlighted: Bool
    var catalog: [AgentThread]
    var onlineHostIDs: Set<String>
    var trails: [String: TrailThumb] = [:]
    var latencyMs: Int? = nil
    var onScope: (SessionScope) -> Void
    var onStatus: (SessionFilter) -> Void
    var onSelectHost: (HostViewState) -> Void
    var onFocusFilter: (HostViewState, SessionScope, SessionFilter) -> Void
    var onOpen: (AgentThread) -> Void
    var onAnswer: (AgentThread, AskOption) -> Void
    var onPair: () -> Void
    var onNewSession: () -> Void
    var onCreateSession: (HostViewState, String?) -> Void
    /// Card terminal. The host menu has no card, so it cannot use this callback.
    var onTerminal: (AgentThread) -> Void
    /// Selected-host terminal from the host menu. `Button` action is `() -> Void`.
    var onOpenSelectedTerminal: () -> Void
    var onSettings: () -> Void
    var showsChrome: Bool = true
    var glass: Namespace.ID

    @State private var tab: HomeTab = .sessions
    @State private var searching = false
    @State private var query = ""
    @State private var lastChromeTapUptime: TimeInterval = -.infinity
    @State private var scopePathsExpanded = false
    @State private var scanning = false
    @State private var pairError: String?
    @State private var deckIndex = 0
    @State private var deckDrag: CGSize = .zero
    @State private var deckDragging = false
    @State private var deckPastSlop = false
    @State private var deckFlying = false
    @State private var deckAlpha = 1.0
    @State private var deckScale: CGFloat = 1
    @State private var deckFrozen: [AgentThread]?
    @State private var deckCardSize: CGSize = .zero
    @State private var listWidth: CGFloat = 0
    @ScaledMetric(relativeTo: .headline) private var sessionTitleSize: CGFloat = 16
    @ScaledMetric(relativeTo: .title3) private var decisionQuestionSize: CGFloat = 18
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Group {
            switch tab {
            case .sessions:
                sessions
            case .machines:
                machines
            case .settings:
                settings
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(tab == .sessions ? Color(.systemGroupedBackground) : Color(.systemBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if showsChrome { homeTabBar }
        }
        .sheet(isPresented: $scanning) {
            QRScannerView(
                onCode: { code in
                    scanning = false
                    let raw = code
                    _Concurrency.Task { @MainActor in
                        await completeScan(raw)
                    }
                },
                onUnavailable: {
                    pairError = "相机不可用。"
                    scanning = false
                },
                onCancel: { scanning = false }
            )
        }
    }

    private var sessions: some View {
        let host = model.selectedHost
        return NavigationStack {
            VStack(spacing: 0) {
                if showsChrome {
                    sessionChrome
                    sessionBanner(host)
                }
                if shown.isEmpty {
                    if sessionListLoading && !searching {
                        ProgressView("正在读取会话")
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if pathScopeCwd != nil, agentCount == 0, !model.hosts.isEmpty {
                        pathEmpty
                    } else {
                        ContentUnavailableView(
                            emptyTitle,
                            systemImage: "bubble.left.and.bubble.right",
                            description: Text(emptyDetail)
                        )
                    }
                } else {
                    sessionScroll
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("会话")
            .navigationBarTitleDisplayMode(.large)
            .onChange(of: visiblePending.map { "\($0.hostId):\($0.id)" }) { _, ids in
                guard deckFrozen == nil else { return }
                if ids.isEmpty {
                    deckIndex = 0
                } else if deckIndex >= ids.count {
                    deckIndex = ids.count - 1
                }
            }
            .toolbar(showsChrome ? .visible : .hidden, for: .navigationBar)
            .toolbarTitleMenu { scopeMenu }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { hostMenu }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if let host = model.selectedHost, host.connection != .live {
                        Button("重试") { model.connect(host.id) }
                    }
                    Button(action: onNewSession) {
                        Image(systemName: "plus")
                            .font(.body.weight(.semibold))
                            .frame(width: 36, height: 36)
                            .luviaGlass(in: Circle())
                    }
                    .accessibilityLabel("新会话")
                }
            }
        }
    }

    private var machines: some View {
        MachinesHome(
            model: model,
            catalog: catalog,
            onlineHostIDs: onlineHostIDs,
            latencyMs: latencyMs,
            pairError: pairError,
            onPair: onPair,
            onScan: {
                pairError = nil
                scanning = true
            },
            onFocus: { host, scope, status in
                onFocusFilter(host, scope, status)
                tab = .sessions
            },
            onCreateSession: { host, cwd in
                tab = .sessions
                onCreateSession(host, cwd)
            }
        )
    }

    private var sessionChrome: some View {
        VStack(alignment: .leading, spacing: 8) {
            summaryRow
            if searching {
                TextField("搜索会话", text: $query)
                    .textFieldStyle(.roundedBorder)
            }
            if showsDecisionStack {
                HStack(alignment: .firstTextBaseline) {
                    Button("等你决定 · \(deckThreads.count)") { onStatus(.needsYou) }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(needsYouColor)
                        .buttonStyle(.plain)
                    Spacer(minLength: 8)
                    Menu {
                        scopeMenu
                    } label: {
                        Text(scopeMenuTitle)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.accentColor)
                            .lineLimit(1)
                    }
                }
                stackedDecisions
            }
            Picker("状态", selection: statusBinding) {
                Text("全部 \(agentCount)").tag(SessionFilter.all)
                Text("等你 \(waitingCount)").tag(SessionFilter.needsYou)
                Text("运行 \(runningCount)").tag(SessionFilter.running)
                Text("空闲 \(idleCount)").tag(SessionFilter.idle)
            }
            .pickerStyle(.segmented)
            .accessibilityLabel("会话状态")
            .padding(2)
            .overlay {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .stroke(filtersHighlighted ? Color.accentColor : Color.clear, lineWidth: 2)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .coordinateSpace(name: "sessionList")
        .background {
            GeometryReader { proxy in
                Color.clear.preference(key: ListWidthKey.self, value: proxy.size.width)
            }
        }
        .onPreferenceChange(ListWidthKey.self) { listWidth = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: filtersHighlighted)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: deckThreads.count)
    }

    private var sessionScroll: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(routedShown.enumerated()), id: \.element.id) { index, row in
                    sessionRow(row.thread)
                        .contentShape(.interaction, Rectangle())
                        .onTapGesture { onOpen(row.thread) }
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction { onOpen(row.thread) }
                    if index < shown.count - 1 {
                        Divider().padding(.leading, 84)
                    }
                }
            }
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
    }

    private var statusBinding: Binding<SessionFilter> {
        Binding(get: { filter.status }, set: onStatus)
    }

    private var needsYouColor: Color {
        colorScheme == .dark ? Color(hex: "FFB340") : Color(hex: "FF9F0A")
    }

    private var summaryRow: some View {
        HStack(alignment: .center, spacing: 12) {
            HStack(spacing: 0) {
                Text(scopeSubtitle)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .font(pathScopeCwd == nil ? .subheadline : .system(size: 14, design: .monospaced))
                Text(" · \(agentCount) 会话")
                    .lineLimit(1)
                    .font(.subheadline)
                    .layoutPriority(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            HStack(spacing: 12) {
                summaryStat("\(runningCount) 运行", color: .green)
                summaryStat("\(waitingCount) 等你", color: needsYouColor)
            }
            .fixedSize(horizontal: true, vertical: false)
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(filtersHighlighted ? Color.accentColor.opacity(0.18) : Color.clear)
        )
    }

    private func summaryStat(_ label: String, color: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).lineLimit(1).font(.subheadline)
        }
        .accessibilityElement(children: .combine)
    }

    private var pathScopeCwd: String? {
        HostFilterBridge.cwd(of: filter.scope)
    }

    private var pathEmpty: some View {
        VStack(spacing: 16) {
            if let cwd = pathScopeCwd {
                VStack(spacing: 4) {
                    Text(HostFilterBridge.relative(cwd, home: homeDir))
                        .font(.system(size: 14, design: .monospaced))
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: 280)
                    Text("下没有会话")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Button("新建会话", action: onNewSession)
            Button("看全部") { onScope(HostFilterBridge.allOnline()) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(32)
    }

    private var emptyTitle: String {
        if model.hosts.isEmpty { return "配对一台电脑" }
        switch filter.status {
        case .needsYou: return "没有等你决定的"
        case .running: return "没有运行中的会话"
        case .idle: return "没有空闲会话"
        default: return "没有会话"
        }
    }

    private var emptyDetail: String {
        if model.hosts.isEmpty { return "在电脑上运行 luvia-host pair" }
        return scopeSubtitle
    }

    private var hostMenu: some View {
        let host = model.selectedHost
        return Menu {
            ForEach(model.hosts) { item in
                Button {
                    onSelectHost(item)
                } label: {
                    Text(item.name)
                    if item.route == .tailnet {
                        Label("在线 · Tailnet", systemImage: "network")
                    } else if item.route == .lan {
                        Text("在线 · 局域网")
                    } else if item.route == .unknown {
                        Text("在线 · 未知")
                    } else if item.connection != .live && item.connection != .connecting {
                        Text("离线")
                    }
                }
            }
            Button("配对电脑", action: onPair)
            if host != nil {
                // Job 133353265165: `(AgentThread) -> Void` is not a `Button` action.
                // The menu opens the selected host, not the card in front.
                Button("打开终端", action: onOpenSelectedTerminal)
                Button("电脑设置", action: onSettings)
            }
        } label: {
            HStack(spacing: 6) {
                Circle()
                    .fill(dotColor(host?.connection))
                    .frame(width: 8, height: 8)
                Text(host?.name ?? "没有电脑")
                    .font(.subheadline.weight(.semibold).monospaced())
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .luviaGlass(in: Capsule())
        }
        .tint(.primary)
    }

    @ViewBuilder
    private var scopeMenu: some View {
        Button {
            onScope(HostFilterBridge.allOnline())
        } label: {
            scopeChoice("全部", selected: HostFilterBridge.isAllOnline(filter.scope))
        }
        Button {
            onScope(HostFilterBridge.currentHost())
        } label: {
            scopeChoice("这台电脑", selected: HostFilterBridge.isCurrentHost(filter.scope))
        }
        .disabled(model.selectedHostID == nil)
        if !scopePaths.isEmpty {
            Divider()
        }
        ForEach(menuPaths, id: \.cwd) { option in
            let selected = HostFilterBridge.cwd(of: filter.scope) == option.cwd
            Button {
                onScope(HostFilterBridge.path(option.cwd))
            } label: {
                HStack {
                    Image(systemName: "checkmark")
                        .frame(width: 24)
                        .opacity(selected ? 1 : 0)
                    Image(systemName: "folder")
                    Text(HostFilterBridge.relative(option.cwd, home: homeDir))
                        .font(.system(size: 14, design: .monospaced))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 12)
                    Text("\(option.count)")
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel(HostFilterBridge.relative(option.cwd, home: homeDir))
            }
        }
        if scopePaths.count > 3, !scopePathsExpanded {
            Button {
                scopePathsExpanded = true
            } label: {
                HStack {
                    Color.clear.frame(width: 24, height: 24)
                    Text("更多")
                    Spacer()
                }
            }
        }
    }

    private func scopeChoice(_ title: String, selected: Bool) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark")
                .frame(width: 24)
                .opacity(selected ? 1 : 0)
            Text(title)
                .lineLimit(1)
        }
    }

    private var menuPaths: [SessionPathOption] {
        if scopePathsExpanded || scopePaths.count <= 3 { return scopePaths }
        let head = Array(scopePaths.prefix(3))
        guard let cwd = HostFilterBridge.cwd(of: filter.scope) else { return head }
        if head.contains(where: { $0.cwd == cwd }) { return head }
        guard let extra = scopePaths.first(where: { $0.cwd == cwd }) else { return head }
        return head + [extra]
    }

    private func completeScan(_ raw: String) async {
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        switch model.beginPairing(deviceLabel: name.isEmpty ? "iPhone" : name, role: .controller) {
        case .success(let draft):
            let result = await model.completePairing(draft: draft, rawCode: raw)
            if case .failure(let error) = result {
                pairError = error.message
            } else {
                tab = .sessions
            }
        case .failure(let error):
            pairError = error.message
        }
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("设置")
                .font(.largeTitle.bold())
            Text("连接、唤醒和取消配对。")
                .foregroundStyle(.secondary)
            if let host = model.selectedHost {
                Text(host.name).font(.title3.weight(.semibold))
                Text(hostStatusLine(host)).foregroundStyle(.secondary)
                Button("电脑设置", action: onSettings)
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)
            } else {
                Text("配对一台电脑")
                Button("配对电脑", action: onPair)
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)
            }
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var decisionsLocked: Bool {
        guard let host = model.selectedHost else { return false }
        return host.pairingRevoked || host.connection != .live
    }

    private var decisionLockLabel: String {
        if model.selectedHost?.pairingRevoked == true {
            return "配对已失效，重新配对后才能作答"
        }
        return "离线，连上后才能作答"
    }

    private func sessionBanner(_ host: HostViewState?) -> some View {
        let phase = sessionBannerPhase(host)
        return VStack(spacing: 0) {
            if phase == "revoked" {
                PairingRevokedBanner(onPair: onPair)
                    .transition(.opacity)
            } else if phase == "offline" || phase == "retrying", let host {
                OfflineCacheBanner(retrying: phase == "retrying") {
                    model.connect(host.id)
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: phase)
    }

    private func sessionBannerPhase(_ host: HostViewState?) -> String {
        guard let host else { return "none" }
        if host.pairingRevoked { return "revoked" }
        if host.connection == .connecting { return "retrying" }
        if host.connection != .live { return "offline" }
        return "live"
    }

    private var agentCount: Int { kotlinInt(roster.agents) ?? 0 }
    private var runningCount: Int { kotlinInt(roster.running) ?? 0 }
    private var waitingCount: Int { kotlinInt(roster.waiting) ?? 0 }
    private var idleCount: Int { kotlinInt(roster.idle) ?? 0 }

    /// Floating glass over the session list. The glass view hit-tests the glyph only,
    /// so a tap on 机器 used to fall through and open the row underneath.
    /// [ChromeHitShield] sits in front of the glass and owns the whole band.
    private var homeTabBar: some View {
        let layout = HomeChromeLayout.shared
        return HStack(spacing: CGFloat(layout.clusterGap)) {
            HStack(spacing: 0) {
                tabButton("会话", "bubble.left.and.bubble.right", .sessions)
                tabButton("机器", "laptopcomputer", .machines)
                tabButton("设置", "gearshape", .settings)
            }
            .padding(.horizontal, CGFloat(layout.capsuleInset))
            .padding(.vertical, CGFloat(layout.capsuleInset))
            .luviaGlass(in: Capsule())
            Button {
                guard claimChromeTap() else { return }
                toggleSearch()
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.body.weight(.semibold))
                    .frame(width: CGFloat(layout.searchDiameter), height: CGFloat(layout.searchDiameter))
                    .contentShape(Circle())
                    .luviaGlass(in: Circle())
            }
            .buttonStyle(.plain)
            .contentShape(.interaction, Circle())
            .foregroundStyle(searching ? Color.blue : Color.primary)
            .accessibilityLabel("搜索")
        }
        .padding(.horizontal, CGFloat(layout.horizontalInset))
        .padding(.bottom, CGFloat(layout.barBottomInset))
        .frame(maxWidth: .infinity)
        .overlay {
            GeometryReader { geo in
                ChromeHitShield { x, _ in
                    applyChromeTap(x: x, width: geo.size.width)
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .highPriorityGesture(
                    SpatialTapGesture().onEnded { event in
                        applyChromeTap(x: event.location.x, width: geo.size.width)
                    }
                )
            }
            .accessibilityHidden(true)
        }
        .zIndex(1)
        .matchedGeometryEffect(id: "home-tabs", in: glass)
    }

    private func claimChromeTap() -> Bool {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastChromeTapUptime > 0.08 else { return false }
        lastChromeTapUptime = now
        return true
    }

    private func toggleSearch() {
        searching.toggle()
        if !searching { query = "" }
        if searching { tab = .sessions }
    }

    /// Touch path for the bar. The row under the finger must not win.
    private func applyChromeTap(x: CGFloat, width: CGFloat) {
        guard width > 1, claimChromeTap() else { return }
        let winner = HomeChromeHitKt.resolveOverlappingTap(
            slot: HomeChromeHitKt.homeChromeSlot(x: Double(x), width: Double(width)),
            inChromeBand: true,
            rowContainsPoint: true
        )
        switch winner {
        case .sessions:
            tab = .sessions
        case .machines:
            tab = .machines
        case .settings:
            tab = .settings
        case .search:
            toggleSearch()
        default:
            break
        }
    }

    private func tabButton(_ title: String, _ symbol: String, _ value: HomeTab) -> some View {
        Button {
            guard claimChromeTap() else { return }
            tab = value
        } label: {
            VStack(spacing: 2) {
                Image(systemName: symbol)
                    .font(.body.weight(.semibold))
                Text(title)
                    .font(.caption2.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
            .foregroundStyle(tab == value ? Color.blue : Color.secondary)
        }
        .buttonStyle(.plain)
        .contentShape(.interaction, Rectangle())
        .accessibilityAddTraits(tab == value ? .isSelected : [])
    }

    private var shown: [AgentThread] {
        KotlinLists.array(SessionListKt.searchSessions(threads: threads, query: searching ? query : "") as Any)
    }

    private var routedShown: [RoutedThread] {
        shown.map { RoutedThread(thread: $0, id: SessionListKt.routeKey(thread: $0)) }
    }

    private var visiblePending: [AgentThread] {
        KotlinLists.array(SessionListKt.pendingMatchingShown(pending: pending, shown: shown) as Any)
    }

    private func sessionRow(_ thread: AgentThread) -> some View {
        let trail = SessionListKt.listTrail(thread: thread, thumb: trails[SessionListKt.routeKey(thread: thread)])
        let dots: [ListTrailDot] = KotlinLists.array(trail.dots as Any)
        let label = trail.label?.trimmingCharacters(in: .whitespacesAndNewlines)
        return HStack(alignment: .top, spacing: 8) {
            Circle()
                .fill(thread.needsYou ? Color.blue : Color.clear)
                .frame(width: 9, height: 9)
                .padding(.top, 16)
            agentAvatar(thread)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(thread.title)
                        .font(.system(size: sessionTitleSize, weight: .semibold))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    if let age = sessionAge(thread.updatedEpochMs) {
                        Text(age)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                HStack(spacing: 4) {
                    if let agent = agentName(thread) {
                        Text(agent)
                            .font(.caption.monospaced())
                            .foregroundStyle(.primary)
                        Text("·")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(rowStatus(thread))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(statusColor(thread))
                    Spacer(minLength: 4)
                    if !dots.isEmpty {
                        trailDots(dots)
                    }
                }
                if let label, !label.isEmpty {
                    Text(label)
                        .font(trail.useMono ? .subheadline.monospaced() : .subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if showsLiveStatus(thread) && isWorking(thread.status) {
                    if let fraction = progressFraction(trail.progressPermille) {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color.blue.opacity(0.16))
                                Capsule().fill(Color.blue).frame(width: max(0, geo.size.width * fraction))
                            }
                        }
                        .frame(height: 4)
                        .padding(.top, 5)
                    } else {
                        ProgressView()
                            .progressViewStyle(.linear)
                            .tint(.blue)
                            .padding(.top, 5)
                    }
                }
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func agentName(_ thread: AgentThread) -> String? {
        let agent = thread.agentType?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let agent, !agent.isEmpty { return agent }
        return nil
    }

    private func rowStatus(_ thread: AgentThread) -> String {
        if !showsLiveStatus(thread) { return "缓存" }
        if thread.needsYou { return "等你" }
        return sessionLabel(thread.status)
    }

    private func showsLiveStatus(_ thread: AgentThread) -> Bool {
        if model.selectedHost?.connection == .live { return true }
        #if DEBUG
        if PlaytestChatKt.isPlaytestThread(id: thread.id) { return true }
        #endif
        return false
    }

    private func canAnswer(_ thread: AgentThread) -> Bool {
        if model.selectedHost?.isController == true { return true }
        #if DEBUG
        if PlaytestChatKt.isPlaytestThread(id: thread.id) { return true }
        #endif
        return false
    }

    private func statusColor(_ thread: AgentThread) -> Color {
        if !showsLiveStatus(thread) { return .secondary }
        if thread.needsYou { return Self.decide }
        if isWorking(thread.status) { return .blue }
        if isDone(thread.status) { return Self.done }
        return .secondary
    }

    private var showsDecisionStack: Bool {
        !deckThreads.isEmpty && filter.status != .running && filter.status != .idle
    }

    private var deckThreads: [AgentThread] {
        deckFrozen ?? visiblePending
    }

    private var scopeMenuTitle: String {
        if HostFilterBridge.isAllOnline(filter.scope) { return "全部" }
        if HostFilterBridge.isCurrentHost(filter.scope) {
            return model.selectedHost?.name ?? "这台电脑"
        }
        return scopeSubtitle
    }

    private var stackedDecisions: some View {
        let threads = deckThreads
        let index = threads.isEmpty ? 0 : min(deckIndex, threads.count - 1)
        let peek = decisionDeckPeekCount(index: index, count: threads.count)
        let counter = threads.count > 1 ? "\(index + 1)/\(threads.count)" : nil
        return ZStack(alignment: .top) {
            ForEach(Array((0..<peek).reversed()), id: \.self) { layer in
                decisionCard(
                    threads[index + layer + 1],
                    counter: nil,
                    interactive: false
                )
                .scaleEffect(layer == 0 ? 0.96 : 0.92, anchor: .bottom)
                .offset(y: layer == 0 ? 6 : 12)
                .opacity(layer == 0 ? 0.72 : 0.45)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            if threads.indices.contains(index) {
                decisionCard(threads[index], counter: counter, interactive: true)
                    .offset(deckDrag)
                    .scaleEffect(deckScale)
                    .opacity(deckAlpha)
                    .rotationEffect(
                        .degrees(deckRotation(count: threads.count)),
                        anchor: .center
                    )
                    .background {
                        GeometryReader { proxy in
                            Color.clear.preference(key: DeckSizeKey.self, value: proxy.size)
                        }
                    }
                    .onPreferenceChange(DeckSizeKey.self) { deckCardSize = $0 }
                    .simultaneousGesture(deckDragGesture(count: threads.count))
                    .accessibilityElement(children: .contain)
                    .accessibilityAction(named: "下一张") { stepDeck(by: 1) }
                    .accessibilityAction(named: "上一张") { stepDeck(by: -1) }
            }
        }
        .padding(.bottom, peek > 0 ? 12 : 0)
        .padding(.top, 4)
        .modifier(DecisionLock(locked: decisionsLocked, label: decisionLockLabel))
    }

    private func decisionCard(_ thread: AgentThread, counter: String?, interactive: Bool) -> some View {
        let ask = thread.ask
        let options: [AskOption] = KotlinLists.array(ask?.options as Any)
        let primary = ask.flatMap { SessionListKt.recommendedOption(ask: $0) } ?? options.first
        let secondary = options.first { $0.label != primary?.label }
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 8) {
                agentAvatar(thread, diameter: 26)
                HStack(spacing: 0) {
                    if let agent = agentName(thread) {
                        Text(agent)
                            .font(.caption.monospaced())
                        Text(" · ")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(thread.title)
                        .font(.subheadline)
                        .lineLimit(1)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                Spacer(minLength: 6)
                if let age = sessionAge(thread.updatedEpochMs) {
                    Text(age)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let counter {
                    Text(counter)
                        .font(.caption.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(Self.decide)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Self.decide.opacity(0.14), in: Capsule())
                        .accessibilityHidden(true)
                }
            }
            if let question = SessionListKt.decisionQuestionText(thread: thread) {
                Text(question)
                    .font(.system(size: decisionQuestionSize, weight: .semibold))
                    .lineLimit(2)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        guard interactive, !deckPastSlop, !deckFlying else { return }
                        onOpen(thread)
                    }
                    .accessibilityLabel(interactive ? deckSpokenLabel(thread, count: deckThreads.count) : question)
            }
            HStack(spacing: 8) {
                if options.isEmpty {
                    Button("去终端回答") {
                        guard interactive, !deckPastSlop else { return }
                        onTerminal(thread)
                    }
                        .buttonStyle(.borderedProminent)
                        .tint(Self.decide)
                        .disabled(!interactive || !canAnswer(thread))
                }
                if let primary {
                    Button(primary.label) { commitAnswer(thread, primary) }
                        .buttonStyle(.borderedProminent)
                        .tint(Self.decide)
                        .disabled(!interactive || !canAnswer(thread))
                        .lineLimit(1)
                }
                if let secondary {
                    Button {
                        commitAnswer(thread, secondary)
                    } label: {
                        Text(secondary.label)
                            .font(.body.weight(.semibold))
                            .lineLimit(1)
                            .foregroundStyle(Color.primary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color(.tertiarySystemFill), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(!interactive || !canAnswer(thread))
                }
                Spacer(minLength: 0)
                Button {
                    guard interactive, !deckPastSlop, !deckFlying else { return }
                    onOpen(thread)
                } label: {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.primary)
                        .frame(width: 40, height: 40)
                        .background(Self.segmentTrack, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("打开")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .stroke(Self.decide.opacity(0.35), lineWidth: 1.5)
        )
        .shadow(color: .black.opacity(0.07), radius: 10, y: 4)
    }

    /// List rows use a brand mark when [agentBrandLogo] knows the agent.
    @ViewBuilder
    private func agentAvatar(_ thread: AgentThread, diameter: CGFloat = 40) -> some View {
        let logo = SessionListKt.agentBrandLogo(agentType: thread.agentType, title: thread.title)
        if let logo, let name = brandAssetName(logo.assetId) {
            let glyph = diameter * CGFloat(logo.inset)
            Group {
                if logo.preservesColor {
                    Image(name)
                        .resizable()
                        .renderingMode(.original)
                        .scaledToFit()
                        .frame(width: glyph, height: glyph)
                        .frame(width: diameter, height: diameter)
                        .clipShape(Circle())
                        .accessibilityHidden(true)
                } else {
                    ZStack {
                        Circle().fill(Color(hex: logo.backgroundHex))
                        Image(name)
                            .resizable()
                            .renderingMode(.template)
                            .scaledToFit()
                            .foregroundStyle(Color(hex: logo.foregroundHex))
                            .frame(width: glyph, height: glyph)
                            .accessibilityHidden(true)
                    }
                    .frame(width: diameter, height: diameter)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(brandAccessibilityName(logo.assetId))
        } else {
            letterAvatar(thread, diameter: diameter)
        }
    }

    private func letterAvatar(_ thread: AgentThread, diameter: CGFloat) -> some View {
        let avatar = SessionListKt.sessionAvatar(agentType: thread.agentType, name: thread.title)
        let mark = avatar.mark
        let fontSize = diameter <= 28 ? 10.0 : (mark.count > 1 ? 13.0 : 16.0)
        return Text(mark)
            .font(.system(size: fontSize, weight: .bold))
            .foregroundStyle(.white)
            .minimumScaleFactor(0.6)
            .lineLimit(1)
            .frame(width: diameter, height: diameter)
            .background(avatarGradient(avatar.palette), in: Circle())
            .accessibilityLabel(mark)
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

    private func brandAccessibilityName(_ assetId: String) -> String {
        switch assetId {
        case "claude": "Claude"
        case "openai": "OpenAI"
        case "lobe-grok": "Grok"
        case "pi": "Pi"
        default: assetId
        }
    }

    private func avatarGradient(_ palette: AgentAvatarPalette) -> LinearGradient {
        let pair: (Color, Color)
        switch palette {
        case .blue:
            pair = (Color(hex: "5AC8FA"), Color(hex: "007AFF"))
        case .orange:
            pair = (Color(hex: "FFB340"), Color(hex: "FF9500"))
        case .mint:
            pair = (Color(hex: "66D4CF"), Color(hex: "00C7BE"))
        case .gray:
            pair = (Color(hex: "AEAEB2"), Color(hex: "8E8E93"))
        case .teal:
            pair = (Color(hex: "5EEAD4"), Color(hex: "0F766E"))
        case .purple:
            pair = (Color(hex: "D8B4FE"), Color(hex: "7C3AED"))
        case .green:
            pair = (Color(hex: "86EFAC"), Color(hex: "16A34A"))
        case .indigo:
            pair = (Color(hex: "A5B4FC"), Color(hex: "4338CA"))
        case .pink:
            pair = (Color(hex: "F9A8D4"), Color(hex: "EC4899"))
        case .brown:
            pair = (Color(hex: "D6B39A"), Color(hex: "A47148"))
        default:
            pair = (Color(hex: "AEAEB2"), Color(hex: "8E8E93"))
        }
        return LinearGradient(colors: [pair.0, pair.1], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private func trailDots(_ dots: [ListTrailDot]) -> some View {
        HStack(spacing: 2.5) {
            ForEach(Array(dots.enumerated()), id: \.offset) { _, dot in
                let wide = dot == .run || dot == .decide
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(trailColor(dot))
                    .frame(width: wide ? 12 : 6, height: 6)
            }
        }
        .accessibilityHidden(true)
    }

    private func trailColor(_ dot: ListTrailDot) -> Color {
        switch dot {
        case .read: Color(hex: "007AFF")
        case .edit: Color(hex: "34C759")
        case .run: Color(hex: "FF3B30")
        case .decide: Self.decide
        default: Color(hex: "8E8E93")
        }
    }

    private func progressFraction(_ permille: Any?) -> CGFloat? {
        guard let value = kotlinInt(permille) else { return nil }
        return CGFloat(value) / 1000
    }

    private func sessionAge(_ updated: KotlinLong?) -> String? {
        guard let ms = kotlinInt64(updated) else { return nil }
        let delta = Int64(Date().timeIntervalSince1970 * 1000) - ms
        if delta < 0 { return nil }
        let minutes = delta / 60_000
        if minutes < 1 { return "刚刚" }
        if minutes < 60 { return "\(minutes) 分钟" }
        if minutes < 24 * 60 { return "\(minutes / 60) 小时" }
        return nil
    }

    private func dotColor(_ badge: HostConnectionBadge?) -> Color {
        switch badge {
        case .live: .green
        case .connecting: .blue
        default: .gray
        }
    }

    private func isWorking(_ status: AgentStatus) -> Bool {
        switch status {
        case .working: true
        default: false
        }
    }

    private func isDone(_ status: AgentStatus) -> Bool {
        switch status {
        case .done: true
        default: false
        }
    }

    private func deckRotation(count: Int) -> Double {
        guard !reduceMotion, count > 1, deckCardSize.width > 0 else { return 0 }
        let fraction = max(-1, min(1, deckDrag.width / deckCardSize.width))
        return Double(8 * fraction)
    }

    private func deckDragGesture(count: Int) -> some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .named("sessionList"))
            .onChanged { value in
                guard !decisionsLocked, !deckFlying else { return }
                guard decisionDeckClaims(startX: value.startLocation.x, width: listWidth) else { return }
                if deckFrozen == nil { deckFrozen = visiblePending }
                deckDragging = true
                deckPastSlop = true
                let damp: CGFloat = count <= 1 ? 0.3 : 1
                deckDrag = CGSize(
                    width: value.translation.width * damp,
                    height: value.translation.height * damp
                )
            }
            .onEnded { value in
                guard deckDragging, !deckFlying else { return }
                let flip = decisionDeckShouldFlip(
                    dx: value.translation.width,
                    dy: value.translation.height,
                    width: deckCardSize.width,
                    height: deckCardSize.height,
                    velocityX: value.velocity.width,
                    velocityY: value.velocity.height,
                    cardCount: count
                )
                if flip {
                    flyDeck(translation: value.translation, velocity: value.velocity, count: count)
                } else {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                        deckDrag = .zero
                    }
                    deckDragging = false
                    DispatchQueue.main.async {
                        deckPastSlop = false
                        deckFrozen = nil
                    }
                }
            }
    }

    private func flyDeck(translation: CGSize, velocity: CGSize, count: Int) {
        deckFlying = true
        let speed = hypot(velocity.width, velocity.height)
        let dir = speed > 50 ? velocity : translation
        let len = max(1, hypot(dir.width, dir.height))
        let distance = max(deckCardSize.width, deckCardSize.height) * 1.4
        let target = CGSize(width: dir.width / len * distance, height: dir.height / len * distance)
        let next = decisionDeckNextIndex(deckIndex, count)
        withAnimation(.easeOut(duration: reduceMotion ? 0.15 : 0.22)) {
            if !reduceMotion { deckDrag = target }
            deckAlpha = 0
        } completion: {
            deckIndex = next
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                deckDrag = .zero
                deckAlpha = 1
                deckScale = reduceMotion ? 1 : 0.96
            }
            deckFrozen = nil
            deckFlying = false
            deckDragging = false
            deckPastSlop = false
            clampDeckIndex()
            announceDeck()
            if !reduceMotion {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                    deckScale = 1
                }
            }
        }
    }

    private func stepDeck(by delta: Int) {
        let count = deckThreads.count
        guard count > 1, !decisionsLocked else { return }
        let next = delta > 0
            ? decisionDeckNextIndex(deckIndex, count)
            : decisionDeckPreviousIndex(deckIndex, count)
        if reduceMotion {
            deckIndex = next
            announceDeck()
            return
        }
        withAnimation(.easeInOut(duration: 0.15)) {
            deckAlpha = 0
        } completion: {
            deckIndex = next
            deckAlpha = 1
            deckScale = 0.96
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                deckScale = 1
            }
            announceDeck()
        }
    }

    private func commitAnswer(_ thread: AgentThread, _ option: AskOption) {
        guard !deckPastSlop, !deckFlying else { return }
        deckFlying = true
        let finish = {
            onAnswer(thread, option)
            deckFlying = false
            deckAlpha = 1
            deckScale = 1
            clampDeckIndex()
        }
        if reduceMotion {
            finish()
            return
        }
        withAnimation(.easeOut(duration: 0.2)) {
            deckScale = 0.9
            deckAlpha = 0
        } completion: {
            finish()
        }
    }

    private func clampDeckIndex() {
        let count = visiblePending.count
        if count == 0 {
            deckIndex = 0
        } else if deckIndex >= count {
            deckIndex = count - 1
        }
    }

    private func announceDeck() {
        let threads = deckThreads
        guard threads.indices.contains(deckIndex) else { return }
        UIAccessibility.post(
            notification: .announcement,
            argument: deckSpokenLabel(threads[deckIndex], count: threads.count)
        )
    }

    private func deckSpokenLabel(_ thread: AgentThread, count: Int) -> String {
        let agent = agentName(thread) ?? thread.title
        let question = SessionListKt.decisionQuestionText(thread: thread) ?? ""
        let place = min(deckIndex, max(count - 1, 0)) + 1
        return "\(agent) 等你决定：\(question)，第 \(place) 个，共 \(count) 个"
    }
}

private struct ListWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct DeckSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

func decisionDeckPeekCount(index: Int, count: Int) -> Int {
    guard count > 1, index >= 0, index < count else { return 0 }
    let behind = count - index - 1
    if behind <= 0 { return 0 }
    return min(2, behind)
}

func decisionDeckNextIndex(_ index: Int, _ count: Int) -> Int {
    guard count > 0 else { return 0 }
    return (index + 1) % count
}

func decisionDeckPreviousIndex(_ index: Int, _ count: Int) -> Int {
    guard count > 0 else { return 0 }
    return (index - 1 + count) % count
}

func decisionDeckClaims(startX: CGFloat, width: CGFloat, edge: CGFloat = 20) -> Bool {
    guard width > edge * 2 else { return true }
    return startX >= edge && startX <= width - edge
}

func decisionDeckShouldFlip(
    dx: CGFloat,
    dy: CGFloat,
    width: CGFloat,
    height: CGFloat,
    velocityX: CGFloat,
    velocityY: CGFloat,
    cardCount: Int
) -> Bool {
    if cardCount <= 1 { return false }
    if hypot(velocityX, velocityY) > 800 { return true }
    let absX = abs(dx)
    let absY = abs(dy)
    if absX >= absY {
        return width > 0 && absX > width / 3
    }
    return height > 0 && absY > height / 3
}

func sessionLabel(_ status: AgentStatus) -> String {
    switch status {
    case .idle: "空闲"
    case .working: "运行中"
    case .blocked: "等你决定"
    case .done: "已完成"
    default: "未知"
    }
}

struct RouteChip: View {
    var route: HostRoute

    var body: some View {
        let tailnet = route == .tailnet
        let label = route == .tailnet ? "Tailnet" : (route == .lan ? "局域网" : "未知")
        let description = route == .tailnet ? "经 Tailnet 连接" : (route == .lan ? "经局域网连接" : "目的地址类别未知")
        HStack(spacing: tailnet ? 3 : 0) {
            if tailnet {
                Image(systemName: "network")
                    .font(.system(size: 11, weight: .semibold))
            }
            Text(label)
                .font(.caption2.weight(.semibold))
                .contentTransition(.opacity)
        }
        .foregroundStyle(tailnet ? Color.accentColor : Color.secondary)
        .padding(.horizontal, 7)
        .frame(height: 20)
        .background(
            (tailnet ? Color.accentColor : Color.secondary).opacity(tailnet ? 0.14 : 0.12),
            in: Capsule()
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
        .animation(.easeInOut(duration: 0.2), value: route)
    }
}

struct PairingRevokedBanner: View {
    var onPair: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(FailureMessagesKt.PROVE_REJECTED_ZH)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: onPair) {
                Text("重新配对")
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .tint(.blue)
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(16)
        .background(Color(.systemRed).opacity(0.15), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }
}

struct OfflineCacheBanner: View {
    var retrying: Bool
    var onRetry: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 15))
                .foregroundStyle(.secondary)
            Text("离线，显示的是缓存")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                if !retrying { onRetry() }
            } label: {
                if retrying {
                    ProgressView()
                } else {
                    Text("重试")
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(16)
        .background(
            Color(.secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }
}

private struct DecisionLock: ViewModifier {
    var locked: Bool
    var label: String

    func body(content: Content) -> some View {
        if locked {
            content
                .opacity(0.38)
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(label)
        } else {
            content
        }
    }
}

func connectionLabel(_ badge: HostConnectionBadge) -> String {
    switch badge {
    case .live: "在线"
    case .connecting: "重连中"
    case .stale, .offline: "离线"
    }
}

func hostStatusLine(_ host: HostViewState) -> String {
    if host.pairingRevoked {
        return host.failureMessage ?? FailureMessagesKt.PROVE_REJECTED_ZH
    }
    return connectionLabel(host.connection)
}

func hostSubtitle(_ host: HostViewState?) -> String {
    guard let host else { return "配对一台电脑" }
    if host.pairingRevoked {
        return host.failureMessage ?? FailureMessagesKt.PROVE_REJECTED_ZH
    }
    if host.connection == .live {
        return host.address.isEmpty ? "在线" : host.address
    }
    if host.address.isEmpty { return connectionLabel(host.connection) }
    return "\(connectionLabel(host.connection)) · \(host.address)"
}

/// Claims every point in the tab band so a session row scrolling underneath cannot take the touch.
private struct ChromeHitShield: UIViewRepresentable {
    var onTap: (CGFloat, CGFloat) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap)
    }

    func makeUIView(context: Context) -> ShieldView {
        let view = ShieldView()
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ uiView: ShieldView, context: Context) {
        context.coordinator.onTap = onTap
        uiView.coordinator = context.coordinator
        uiView.isUserInteractionEnabled = true
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ShieldView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }

    final class Coordinator {
        var onTap: (CGFloat, CGFloat) -> Void
        init(onTap: @escaping (CGFloat, CGFloat) -> Void) {
            self.onTap = onTap
        }
    }

    final class ShieldView: UIView {
        var coordinator: Coordinator?
        private var origin: CGPoint?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isAccessibilityElement = false
            backgroundColor = .clear
            isUserInteractionEnabled = true
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
            bounds.contains(point)
        }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            origin = touches.first?.location(in: self)
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            defer { origin = nil }
            guard let origin, let end = touches.first?.location(in: self), bounds.contains(end) else { return }
            let dx = end.x - origin.x
            let dy = end.y - origin.y
            guard dx * dx + dy * dy <= 12 * 12 else { return }
            coordinator?.onTap(end.x, bounds.width)
        }

        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
            origin = nil
        }
    }
}
