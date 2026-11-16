import LuviaShared
import SwiftUI
import UIKit

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
    var filter: SessionFilter
    var roster: SessionRoster
    var trails: [String: TrailThumb] = [:]
    var latencyMs: Int? = nil
    var onFilter: (SessionFilter) -> Void
    var onOpen: (AgentThread) -> Void
    var onAnswer: (AgentThread, AskOption) -> Void
    var onPair: () -> Void
    var onNewSession: () -> Void
    var onTerminal: () -> Void
    var onSettings: () -> Void
    var showsChrome: Bool = true
    var glass: Namespace.ID

    @State private var tab: HomeTab = .sessions
    @State private var searching = false
    @State private var query = ""
    @State private var lastChromeTapUptime: TimeInterval = -.infinity

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
    }

    private var sessions: some View {
        let host = model.selectedHost
        return VStack(spacing: 0) {
            if showsChrome {
                header(host)
                titleRow
                sessionBanner(host)
                if searching {
                    TextField("搜索会话", text: $query)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }
                if waitingCount > 0 {
                    HStack {
                        Button("\(waitingCount) 个待你决定") { onFilter(.needsYou) }
                            .foregroundStyle(Self.decide)
                        Spacer()
                        Button("全部") { onFilter(.all) }
                            .foregroundStyle(Color.blue)
                    }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.plain)
                    .padding(.horizontal, 20)
                    .padding(.top, 4)
                }
                if !visiblePending.isEmpty && filter != .running && filter != .idle {
                    stackedDecisions
                }
                HStack(spacing: 0) {
                    filterChip("全部 \(agentCount)", .all)
                    filterChip("等你 \(waitingCount)", .needsYou)
                    filterChip("运行 \(runningCount)", .running)
                    filterChip("空闲 \(idleCount)", .idle)
                }
                .padding(3)
                .background(Self.segmentTrack, in: Capsule())
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            if shown.isEmpty {
                ContentUnavailableView(
                    host == nil ? "配对一台电脑" : "没有会话",
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text("新会话用顶栏 +。电脑上的 agent 仍由主机创建。")
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(shown.enumerated()), id: \.element.id) { index, thread in
                            // Plain Button gestures inside this scroll view win hits on the
                            // tab bar. A bounded tap stays inside the row.
                            sessionRow(thread)
                                .contentShape(.interaction, Rectangle())
                                .onTapGesture { onOpen(thread) }
                                .accessibilityElement(children: .combine)
                                .accessibilityAddTraits(.isButton)
                                .accessibilityAction { onOpen(thread) }
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
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(.systemGroupedBackground))
    }

    private var machines: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("机器")
                .font(.largeTitle.bold())
                .padding(.horizontal, 16)
                .padding(.top, 8)
            Text("已配对的电脑。点一台查看它的会话。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            if model.hosts.isEmpty {
                ContentUnavailableView("配对一台电脑", systemImage: "laptopcomputer", description: Text("配对之后会出现在这里。"))
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(model.hosts) { host in
                            HStack(spacing: 12) {
                                Circle()
                                    .fill(dotColor(host.connection))
                                    .frame(width: 10, height: 10)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(host.name).font(.headline)
                                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                                        Text(hostStatusLine(host))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        if let route = host.route {
                                            RouteChip(route: route)
                                                .transition(.opacity)
                                        }
                                    }
                                    .animation(.easeInOut(duration: 0.2), value: host.route)
                                }
                                Spacer()
                                if host.id == model.selectedHostID {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.blue)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(.interaction, Rectangle())
                            .onTapGesture {
                                model.select(host)
                                tab = .sessions
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityAddTraits(.isButton)
                            .accessibilityAction {
                                model.select(host)
                                tab = .sessions
                            }
                        }
                    }
                }
            }
            HStack {
                Button("配对电脑", action: onPair)
                    .buttonStyle(.bordered)
                if model.selectedHost != nil {
                    Button("打开终端", action: onTerminal)
                        .buttonStyle(.bordered)
                }
            }
            .tint(.blue)
            .padding(16)
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

    private func header(_ host: HostViewState?) -> some View {
        HStack(spacing: 8) {
            Menu {
                ForEach(model.hosts) { item in
                    Button {
                        model.select(item)
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
            Spacer()
            Button(action: onNewSession) {
                Image(systemName: "plus")
                    .font(.body.weight(.semibold))
                    .frame(width: 36, height: 36)
                    .luviaGlass(in: Circle())
            }
            .buttonStyle(.plain)
            .tint(.blue)
            .accessibilityLabel("新会话")
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var titleRow: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("会话")
                .font(.largeTitle.bold())
            Spacer(minLength: 12)
            Text(statsLine)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
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

    private var statsLine: String {
        var parts = ["\(agentCount) agents"]
        if let latencyMs {
            parts.append("\(latencyMs) ms")
        }
        parts.append("\(runningCount) 运行")
        parts.append("\(waitingCount) 等你")
        return parts.joined(separator: " · ")
    }

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

    private var visiblePending: [AgentThread] {
        let ids = Set(shown.map(\.id))
        return pending.filter { ids.contains($0.id) }
    }

    private func sessionRow(_ thread: AgentThread) -> some View {
        let trail = SessionListKt.listTrail(thread: thread, thumb: trails[thread.id])
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
                        .font(.system(size: 16, weight: .semibold))
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

    private var stackedDecisions: some View {
        ZStack(alignment: .bottom) {
            if visiblePending.count > 2 {
                stackRim(inset: 28, opacity: 0.15)
                    .offset(y: 20)
            }
            if visiblePending.count > 1 {
                stackRim(inset: 14, opacity: 0.28)
                    .offset(y: 10)
            }
            decisionCard(visiblePending[0])
                .padding(.horizontal, 16)
        }
        .padding(.bottom, visiblePending.count > 1 ? 24 : 8)
        .padding(.top, 8)
        .modifier(DecisionLock(locked: decisionsLocked, label: decisionLockLabel))
    }

    private func stackRim(inset: CGFloat, opacity: Double) -> some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(Color(.systemBackground))
            .overlay(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .stroke(Self.decide.opacity(opacity), lineWidth: 1)
            )
            .frame(height: 36)
            .padding(.horizontal, 16 + inset)
            .allowsHitTesting(false)
    }

    private func decisionCard(_ thread: AgentThread) -> some View {
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
            }
            if let question = SessionListKt.decisionQuestionText(thread: thread) {
                Text(question)
                    .font(.system(size: 18, weight: .semibold))
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { onOpen(thread) }
            }
            HStack(spacing: 8) {
                if let primary {
                    Button(primary.label) { onAnswer(thread, primary) }
                        .buttonStyle(.borderedProminent)
                        .tint(Self.decide)
                        .disabled(!canAnswer(thread))
                        .lineLimit(1)
                }
                if let secondary {
                    if secondary.tone == .destructive {
                        Button(secondary.label) { onAnswer(thread, secondary) }
                            .buttonStyle(.bordered)
                            .tint(.red)
                            .disabled(!canAnswer(thread))
                            .lineLimit(1)
                    } else {
                        Button {
                            onAnswer(thread, secondary)
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
                        .disabled(!canAnswer(thread))
                    }
                }
                Spacer(minLength: 0)
                Button {
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

    private func filterChip(_ title: String, _ value: SessionFilter) -> some View {
        let selected = filter == value
        return Button {
            onFilter(value)
        } label: {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(selected ? Color(.systemBackground) : Color.clear, in: Capsule())
                .shadow(color: selected ? .black.opacity(0.12) : .clear, radius: 3, y: 1)
                .foregroundStyle(selected ? Color.primary : Color.secondary)
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
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
