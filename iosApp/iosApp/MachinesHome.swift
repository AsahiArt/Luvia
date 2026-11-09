import LuviaShared
import SwiftUI

struct MachinesHome: View {
    @Bindable var model: AppModel
    var catalog: [AgentThread]
    var onlineHostIDs: Set<String>
    var latencyMs: Int?
    var pairError: String?
    var onPair: () -> Void
    var onScan: () -> Void
    var onFocus: (HostViewState, SessionScope, SessionFilter) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var detailHostID: String?
    @State private var litPath: String?
    @State private var expandedHosts: Set<String> = []
    @State private var reconnecting = false
    @State private var reconnectError: String?

    private var needsYouColor: Color {
        colorScheme == .dark ? Color(hex: "FFB340") : Color(hex: "FF9F0A")
    }

    private var currentHost: HostViewState? {
        guard let id = model.selectedHostID else { return nil }
        return model.hosts.first { $0.id == id }
    }

    private var otherHosts: [HostViewState] {
        model.hosts.filter { $0.id != currentHost?.id }
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.hosts.isEmpty {
                    empty
                } else {
                    list
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("机器")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("扫码", systemImage: "qrcode.viewfinder", action: onScan)
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
            .navigationDestination(isPresented: detailPresented) {
                if let detailHostID {
                    MachineDetail(hostId: detailHostID, model: model)
                }
            }
        }
    }

    private var detailPresented: Binding<Bool> {
        Binding(
            get: { detailHostID != nil },
            set: { if !$0 { detailHostID = nil } }
        )
    }

    private var empty: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "laptopcomputer")
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("还没有电脑")
                .font(.title2.weight(.semibold))
            Text("在电脑上运行")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("luvia-host pair")
                .font(.body.monospaced())
                .textSelection(.enabled)
                .accessibilityLabel("luvia-host pair")
            Button("配对新电脑", action: onPair)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            if let pairError {
                Text(pairError)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                        .machineActions(host: host, reconnect: { reconnect(host) }, model: model)
                }
            }
            if !otherHosts.isEmpty {
                Section {
                    ForEach(otherHosts) { host in
                        otherCard(host)
                            .opacity(host.connection == .live || host.connection == .connecting ? 1 : 0.55)
                            .machineRow()
                            .machineActions(host: host, reconnect: { reconnect(host) }, model: model)
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
    }

    private func mainCard(_ host: HostViewState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            hostHeader(host)
            counts(host)
            if host.connection == .live {
                pathList(host)
            } else {
                reconnectRow(host)
            }
        }
        .padding(16)
        .machineGlass()
        .onChange(of: host.connection) { _, connection in
            guard reconnecting else { return }
            if connection == .live {
                reconnecting = false
                reconnectError = nil
            } else if connection != .connecting {
                reconnecting = false
                reconnectError = host.failureMessage ?? "没连上"
            }
        }
        .onChange(of: host.failureMessage) { _, message in
            guard reconnecting, host.connection != .live, host.connection != .connecting else { return }
            reconnecting = false
            reconnectError = message ?? "没连上"
        }
    }

    private func otherCard(_ host: HostViewState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            hostHeader(host)
            if host.connection == .live {
                pathList(host)
            }
        }
        .padding(16)
        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func hostHeader(_ host: HostViewState) -> some View {
        Button {
            detailHostID = host.id
        } label: {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.accentColor)
                    .frame(width: 48, height: 48)
                    .overlay {
                        Image(systemName: "desktopcomputer")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(host.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(connectionLine(host))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
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
        .buttonStyle(.borderless)
        .accessibilityLabel("\(host.name)，\(connectionLabel(host.connection))")
        .accessibilityHint("打开这台电脑")
    }

    private func counts(_ host: HostViewState) -> some View {
        let waiting = count(host, status: .needsYou)
        let running = count(host, status: .running)
        let all = count(host, status: .all)
        let stack = dynamicTypeSize.isAccessibilitySize
        return Group {
            if stack {
                VStack(spacing: 8) {
                    countBlock("等你", waiting, needsYouColor) { focus(host, status: .needsYou) }
                    countBlock("运行中", running, .primary) { focus(host, status: .running) }
                    countBlock("会话", all, .primary) { focus(host, status: .all) }
                }
            } else {
                HStack(spacing: 8) {
                    countBlock("等你", waiting, needsYouColor) { focus(host, status: .needsYou) }
                    countBlock("运行中", running, .primary) { focus(host, status: .running) }
                    countBlock("会话", all, .primary) { focus(host, status: .all) }
                }
            }
        }
    }

    private func countBlock(
        _ title: String,
        _ value: Int,
        _ tint: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                Text("\(value)")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(tint)
                    .contentTransition(.numericText())
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.3), value: value)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .frame(minHeight: 64)
            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(CountPressStyle())
        .accessibilityLabel("\(title) \(value) 个")
        .accessibilityHint("轻点查看")
    }

    private func pathList(_ host: HostViewState) -> some View {
        let paths = HostFilterBridge.paths(threads: catalog, hostId: host.id)
        let expanded = expandedHosts.contains(host.id)
        let shown = expanded ? paths : Array(paths.prefix(3))
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(shown, id: \.cwd) { option in
                pathRow(host, option)
            }
            if paths.count > 3, !expanded {
                Button("更多") { expandedHosts.insert(host.id) }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.borderless)
                    .frame(minHeight: 44)
                    .accessibilityLabel("更多路径")
            }
        }
    }

    private func pathRow(_ host: HostViewState, _ option: SessionPathOption) -> some View {
        let cwd = option.cwd
        let total = kotlinInt(option.count) ?? 0
        let waiting = count(host, cwd: cwd, status: .needsYou)
        let threads = HostFilterBridge.sessions(
            threads: catalog,
            hostId: host.id,
            scope: HostFilterBridge.path(cwd),
            status: .all,
            online: onlineHostIDs
        )
        let shown = Array(threads.prefix(3))
        let extra = max(0, threads.count - shown.count)
        let lit = litPath == "\(host.id)\u{1f}\(cwd)"
        return Button {
            openPath(host, cwd: cwd)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "folder")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text(MachinePath.display(cwd))
                    .font(.subheadline.monospaced())
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
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
                if waiting > 0 {
                    Text("\(waiting) 等你")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(needsYouColor)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(needsYouColor.opacity(0.20), in: Capsule())
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 8)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(lit ? Color.accentColor.opacity(0.16) : Color.clear)
            )
            .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: lit)
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(pathLabel(cwd: cwd, total: total, waiting: waiting))
        .accessibilityHint("轻点查看")
    }

    private func reconnectRow(_ host: HostViewState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                reconnect(host)
            } label: {
                HStack(spacing: 8) {
                    if reconnecting || host.connection == .connecting {
                        ProgressView()
                            .controlSize(.small)
                    }
                    Text("重新连接")
                        .font(.body.weight(.semibold))
                }
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderless)
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

    private func reconnect(_ host: HostViewState) {
        reconnecting = true
        reconnectError = nil
        model.connect(host.id)
    }

    private func focus(_ host: HostViewState, status: SessionFilter) {
        onFocus(host, HostFilterBridge.currentHost(), status)
    }

    private func openPath(_ host: HostViewState, cwd: String) {
        let key = "\(host.id)\u{1f}\(cwd)"
        litPath = key
        let go = {
            onFocus(host, HostFilterBridge.path(cwd), .all)
            litPath = nil
        }
        if reduceMotion {
            go()
            return
        }
        _Concurrency.Task { @MainActor in
            try? await _Concurrency.Task.sleep(nanoseconds: 250_000_000)
            guard litPath == key else { return }
            go()
        }
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
        switch host.connection {
        case .live:
            var line = host.address.isEmpty ? "已连接" : "已连接 · \(host.address)"
            if host.id == currentHost?.id, let latencyMs {
                line += " · \(latencyMs) ms"
            }
            return line
        case .connecting:
            return "连接中"
        case .stale:
            return "需要重新连接"
        case .offline:
            return "离线"
        }
    }

    private func pathLabel(cwd: String, total: Int, waiting: Int) -> String {
        var label = "路径 \(MachinePath.display(cwd))，\(total) 个会话"
        if waiting > 0 { label += "，\(waiting) 个等你决定" }
        return label
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
    @State private var confirmRemove = false

    private var host: HostViewState? {
        model.hosts.first { $0.id == hostId }
    }

    var body: some View {
        Form {
            if let host {
                Section {
                    LabeledContent("连接", value: connectionLabel(host.connection))
                    if !host.address.isEmpty {
                        LabeledContent("地址", value: host.address)
                    }
                    if !host.backend.isEmpty {
                        LabeledContent("后端", value: host.backend)
                    }
                }
                Section {
                    Button("重新连接") { model.connect(host.id) }
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
}

private struct CountPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.97 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: configuration.isPressed)
    }
}

private extension View {
    func machineRow() -> some View {
        listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    func machineActions(host: HostViewState, reconnect: @escaping () -> Void, model: AppModel) -> some View {
        swipeActions(edge: .leading) {
            Button("重新连接", action: reconnect)
                .tint(.blue)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button("断开") { model.disconnect(host.id) }
                .tint(.orange)
            Button("移除", role: .destructive) {
                _Concurrency.Task { await model.unpair(host.id) }
            }
        }
        .accessibilityAction(named: "重新连接", reconnect)
        .accessibilityAction(named: "断开") { model.disconnect(host.id) }
        .accessibilityAction(named: "移除") {
            _Concurrency.Task { await model.unpair(host.id) }
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
