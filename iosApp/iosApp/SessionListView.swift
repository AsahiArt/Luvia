import LuviaShared
import SwiftUI

private enum HomeTab: Hashable {
    case sessions
    case machines
    case settings
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
        .background(Color(.systemBackground))
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
                if searching {
                    TextField("搜索会话", text: $query)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }
                if waitingCount > 0 {
                    HStack {
                        Button("\(waitingCount) 个待你决定") { onFilter(.needsYou) }
                        Spacer()
                        Button("全部") { onFilter(.all) }
                    }
                    .font(.subheadline.weight(.semibold))
                    .tint(.blue)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
                }
                if !visiblePending.isEmpty && filter != .running && filter != .idle {
                    stackedDecisions
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        filterChip("全部 \(agentCount)", .all)
                        filterChip("等你 \(waitingCount)", .needsYou)
                        filterChip("运行 \(runningCount)", .running)
                        filterChip("空闲 \(idleCount)", .idle)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
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
                        ForEach(shown, id: \.id) { thread in
                            Button {
                                onOpen(thread)
                            } label: {
                                sessionRow(thread)
                            }
                            .buttonStyle(.plain)
                            .contentShape(Rectangle())
                            Divider().padding(.leading, 72)
                        }
                    }
                }
            }
        }
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
                            Button {
                                model.select(host)
                                tab = .sessions
                            } label: {
                                HStack(spacing: 12) {
                                    Circle()
                                        .fill(dotColor(host.connection))
                                        .frame(width: 10, height: 10)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(host.name).font(.headline)
                                        Text(connectionLabel(host.connection))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
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
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
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
                Text(connectionLabel(host.connection)).foregroundStyle(.secondary)
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
                    Button(item.name) { model.select(item) }
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
                        .font(.subheadline.weight(.semibold))
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
            if let host, host.connection != .live {
                Button("重试") { model.connect(host.id) }
                    .buttonStyle(.bordered)
                    .tint(.blue)
            }
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
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
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

    private var homeTabBar: some View {
        HStack(spacing: 12) {
            HStack(spacing: 0) {
                tabButton("会话", "bubble.left.and.bubble.right", .sessions)
                tabButton("机器", "laptopcomputer", .machines)
                tabButton("设置", "gearshape", .settings)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 6)
            .luviaGlass(in: Capsule())
            Button {
                searching.toggle()
                if !searching { query = "" }
                if searching { tab = .sessions }
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.body.weight(.semibold))
                    .frame(width: 48, height: 48)
                    .luviaGlass(in: Circle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(searching ? Color.blue : Color.primary)
            .accessibilityLabel("搜索")
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 6)
        .matchedGeometryEffect(id: "home-tabs", in: glass)
    }

    private func tabButton(_ title: String, _ symbol: String, _ value: HomeTab) -> some View {
        Button {
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
            .foregroundStyle(tab == value ? Color.blue : Color.secondary)
        }
        .buttonStyle(.plain)
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
        let thumb = trails[thread.id]
        let kinds: [ToolTrailKind] = thumb.map { KotlinLists.array($0.kinds as Any) } ?? []
        return HStack(alignment: .top, spacing: 12) {
            agentAvatar(thread)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(thread.title)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                    if let age = sessionAge(thread.updatedEpochMs) {
                        Text(age)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                Text(rowSubtitle(thread))
                    .font(.caption)
                    .foregroundStyle(statusColor(thread))
                if let detail = thumb?.label ?? thread.summary, !detail.isEmpty {
                    Text(detail)
                        .font(thumb == nil ? .subheadline : .caption.monospaced())
                        .lineLimit(2)
                }
                if model.selectedHost?.connection == .live && isWorking(thread.status) {
                    if let fraction = progressFraction(thumb) {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color.blue.opacity(0.18))
                                Capsule().fill(Color.blue).frame(width: geo.size.width * fraction)
                            }
                        }
                        .frame(height: 4)
                    } else {
                        ProgressView()
                            .progressViewStyle(.linear)
                            .tint(.blue)
                    }
                }
                if !kinds.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(Array(kinds.enumerated()), id: \.offset) { _, kind in
                            RoundedRectangle(cornerRadius: kind == .run ? 2 : 4, style: .continuous)
                                .fill(trailColor(kind))
                                .frame(width: 8, height: 8)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func rowSubtitle(_ thread: AgentThread) -> String {
        let status = rowStatus(thread)
        let agent = thread.agentType ?? ""
        if agent.isEmpty { return status }
        return "\(agent) · \(status)"
    }

    private func rowStatus(_ thread: AgentThread) -> String {
        if model.selectedHost?.connection == .live {
            return sessionLabel(thread.status)
        }
        return "缓存"
    }

    private func statusColor(_ thread: AgentThread) -> Color {
        if model.selectedHost?.connection != .live { return .secondary }
        if isWorking(thread.status) || isDone(thread.status) { return .green }
        if thread.ask != nil { return .blue }
        return .secondary
    }

    private var stackedDecisions: some View {
        ZStack {
            if visiblePending.count > 1 {
                decisionCard(visiblePending[1], interactive: false)
                    .padding(.horizontal, 28)
                    .offset(y: 18)
                    .opacity(0.55)
                    .allowsHitTesting(false)
            }
            decisionCard(visiblePending[0], interactive: true)
                .padding(.horizontal, 16)
        }
        .padding(.bottom, visiblePending.count > 1 ? 28 : 8)
        .padding(.top, 8)
    }

    private func decisionCard(_ thread: AgentThread, interactive: Bool) -> some View {
        let ask = thread.ask
        let options: [AskOption] = KotlinLists.array(ask?.options as Any)
        let primary = ask.flatMap { SessionListKt.recommendedOption(ask: $0) } ?? options.first
        let secondary = options.first { $0.label != primary?.label }
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 10) {
                agentAvatar(thread)
                VStack(alignment: .leading, spacing: 2) {
                    Text([thread.agentType, thread.title].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    if let age = sessionAge(thread.updatedEpochMs) {
                        Text(age).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if interactive {
                    Button {
                        onOpen(thread)
                    } label: {
                        Image(systemName: "chevron.right")
                            .font(.body.weight(.semibold))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("打开")
                }
            }
            Text(ask?.question ?? thread.title)
                .font(.headline)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture {
                    if interactive { onOpen(thread) }
                }
            if interactive {
                HStack {
                    if let primary {
                        Button(primary.label) { onAnswer(thread, primary) }
                            .buttonStyle(.borderedProminent)
                            .tint(.blue)
                            .disabled(model.selectedHost?.isController != true)
                    }
                    if let secondary {
                        Button(secondary.label) { onAnswer(thread, secondary) }
                            .buttonStyle(.bordered)
                            .tint(.secondary)
                            .disabled(model.selectedHost?.isController != true)
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous), tint: .blue)
    }

    private func filterChip(_ title: String, _ value: SessionFilter) -> some View {
        let selected = filter == value
        return Button(title) { onFilter(value) }
            .font(.subheadline.weight(selected ? .semibold : .regular))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(selected ? Color(.systemBackground) : Color(.secondarySystemFill), in: Capsule())
            .overlay(Capsule().stroke(Color.primary.opacity(selected ? 0.08 : 0), lineWidth: 1))
            .buttonStyle(.plain)
            .tint(selected ? .blue : .secondary)
    }

    private func agentAvatar(_ thread: AgentThread) -> some View {
        let mark = SessionListKt.agentMark(agentType: thread.agentType, title: thread.title)
        let slot = kotlinInt(SessionListKt.agentColorSlot(agentType: thread.agentType, title: thread.title)) ?? 0
        return Text(mark)
            .font(.caption.weight(.bold))
            .foregroundStyle(avatarInk(slot))
            .frame(width: 40, height: 40)
            .background(avatarFill(slot), in: Circle())
    }

    private func avatarFill(_ slot: Int) -> Color {
        switch slot {
        case 0: Color.blue.opacity(0.18)
        case 1: Color.teal.opacity(0.2)
        case 2: Color.green.opacity(0.18)
        default: Color.gray.opacity(0.2)
        }
    }

    private func avatarInk(_ slot: Int) -> Color {
        switch slot {
        case 0: .blue
        case 1: .teal
        case 2: .green
        default: .secondary
        }
    }

    private func trailColor(_ kind: ToolTrailKind) -> Color {
        switch kind {
        case .read: .blue
        case .edit: .teal
        case .run: .red
        default: .gray
        }
    }

    private func progressFraction(_ thumb: TrailThumb?) -> CGFloat? {
        guard let thumb, let value = kotlinInt(thumb.progressPermille) else { return nil }
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

func connectionLabel(_ badge: HostConnectionBadge) -> String {
    switch badge {
    case .live: "在线"
    case .connecting: "重连中"
    case .stale, .offline: "离线"
    }
}

func hostSubtitle(_ host: HostViewState?) -> String {
    guard let host else { return "配对一台电脑" }
    if host.connection == .live {
        return host.address.isEmpty ? "在线" : host.address
    }
    if host.address.isEmpty { return connectionLabel(host.connection) }
    return "\(connectionLabel(host.connection)) · \(host.address)"
}
