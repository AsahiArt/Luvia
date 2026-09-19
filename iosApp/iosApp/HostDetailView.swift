import SwiftUI
import LuviaShared

struct HostDetailView: View {
    let host: HostViewState
    @Binding var section: HostSection
    @Bindable var model: AppModel

    @State private var agentQuery = ""
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            TabView(selection: $section) {
                AgentsSectionView(model: model, host: host, query: $agentQuery)
                    .tabItem {
                        Label(HostSection.agents.rawValue, systemImage: HostSection.agents.symbol)
                    }
                    .tag(HostSection.agents)

                ReviewSectionView(model: model, host: host)
                    .tabItem {
                        Label(HostSection.review.rawValue, systemImage: HostSection.review.symbol)
                    }
                    .tag(HostSection.review)

                TasksSectionView(model: model, host: host)
                    .tabItem {
                        Label(HostSection.tasks.rawValue, systemImage: HostSection.tasks.symbol)
                    }
                    .tag(HostSection.tasks)

                AutomationsSurfaceView(model: model)
                    .tabItem {
                        Label(HostSection.automations.rawValue, systemImage: HostSection.automations.symbol)
                    }
                    .tag(HostSection.automations)
                    .task { await model.loadAutomations() }
            }
            .tint(DesignTokens.accent)
            .hostSessionChrome(host: host, model: model)
            .navigationDestination(for: String.self) { id in
                AgentDetailView(model: model, agentID: id)
                    .task { await model.openAgent(id) }
            }
            .navigationDestination(for: DiffFileItem.self) { file in
                DiffFileDetailView(model: model, file: file)
                    .task { await model.openDiffFile(file) }
            }
            .sheet(item: $model.uhp.moreSurface) { surface in
                MoreSurfaceSheet(model: model, surface: surface)
            }
            .fullScreenCover(isPresented: $model.uhp.isAcpPresented, onDismiss: {
                model.closeAcp()
            }) {
                AcpSessionView(model: model)
            }
            .onChange(of: model.pendingOpenAgentID) { _, id in
                guard let id else { return }
                section = .agents
                path.append(id)
                model.pendingOpenAgentID = nil
            }
            .onChange(of: model.pendingHostSection) { _, next in
                guard let next else { return }
                path = NavigationPath()
                section = next
                model.pendingHostSection = nil
            }
            .onChange(of: model.pendingPresentAcp) { _, present in
                guard present else { return }
                if model.uhp.snapshot?.acp.open == true {
                    model.uhp.isAcpPresented = true
                    model.pendingPresentAcp = false
                }
            }
        }
    }

}

struct HostSessionChrome: ViewModifier {
    let host: HostViewState
    @Bindable var model: AppModel
    @State private var editingConnection = false

    func body(content: Content) -> some View {
        content
            .navigationTitle(host.name)
            .navigationBarTitleDisplayMode(model.hasLiveSession ? .large : .inline)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    if host.connection == .live || host.connection == .connecting {
                        Button("Disconnect", systemImage: "pause.circle") {
                            model.disconnect(host.id)
                        }
                    } else {
                        Button("Connect", systemImage: "bolt.horizontal.circle") {
                            model.connect(host.id)
                        }
                    }
                    Button("Refresh", systemImage: "arrow.clockwise") {
                        _Concurrency.Task { await model.refresh(host.id) }
                    }
                    .disabled(host.connection != .live)
                    Button("Edit Connection", systemImage: "network") {
                        editingConnection = true
                    }
                    Menu {
                        Button("Files", systemImage: MoreSurface.files.symbol) {
                            model.uhp.moreSurface = .files
                        }
                        Button("Search", systemImage: MoreSurface.search.symbol) {
                            model.uhp.moreSurface = .search
                        }
                        Button("Worktrees", systemImage: MoreSurface.worktrees.symbol) {
                            model.uhp.moreSurface = .worktrees
                        }
                        Button("Layout", systemImage: MoreSurface.layout.symbol) {
                            model.uhp.moreSurface = .layout
                        }
                    } label: {
                        Label("More", systemImage: "ellipsis.circle")
                    }
                    .disabled(host.connection != .live)
                    .accessibilityLabel("More")
                }
            }
            .sheet(isPresented: $editingConnection) {
                EditConnectionSheet(host: host, model: model)
            }
    }
}

extension View {
    func hostSessionChrome(host: HostViewState, model: AppModel) -> some View {
        modifier(HostSessionChrome(host: host, model: model))
    }
}

private struct EditConnectionSheet: View {
    let host: HostViewState
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var alias: String
    @State private var hosts: String
    @State private var port: String
    @State private var username: String
    @State private var errorMessage: String?
    @State private var saving = false

    init(host: HostViewState, model: AppModel) {
        self.host = host
        self.model = model
        _alias = State(initialValue: host.name)
        _hosts = State(initialValue: host.addresses.joined(separator: ", "))
        _port = State(initialValue: String(host.sshPort))
        _username = State(initialValue: host.username)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $alias)
                    TextField("Host", text: $hosts)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Port", text: $port)
                        .keyboardType(.numberPad)
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text("Comma-separated hosts. SSH host keys stay pinned from pairing.")
                }
                if model.uhp.caps.push {
                    Section {
                        Toggle("Wake me for approvals", isOn: Binding(
                            get: { model.isPushEnabled },
                            set: { model.setWakeForApprovals($0) }
                        ))
                    } footer: {
                        Text("Wake this phone when an agent is blocked or an ACP agent asks for permission. The Host never sends transcript text.")
                    }
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Edit Connection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(saving)
                }
            }
        }
    }

    private func save() {
        saving = true
        errorMessage = nil
        _Concurrency.Task {
            let result = await model.updateConnection(
                hostID: host.id,
                alias: alias,
                hosts: hosts,
                port: port,
                username: username
            )
            saving = false
            switch result {
            case .success:
                dismiss()
            case .failure(let error):
                errorMessage = error.message
            }
        }
    }
}


private enum TerminalChrome {
    static let background = DesignTokens.Terminal.background
    static let foreground = DesignTokens.Terminal.foreground
    static let muted = DesignTokens.Terminal.muted
}

private struct TerminalKeySpec: Identifiable {
    let id: String
    let title: String
    let key: TerminalKey
}

struct TerminalPane: View {
    let host: HostViewState
    let text: String
    let status: String?
    let holdsControl: Bool
    var onSend: (String) -> Void
    var onSendKey: (TerminalKey) -> Void
    var onRequestControl: () -> Void

    @State private var input = ""
    @State private var wrap = true

    private var keys: [TerminalKeySpec] {
        [
            TerminalKeySpec(id: "esc", title: "Esc", key: .escape),
            TerminalKeySpec(id: "tab", title: "Tab", key: .tab),
            TerminalKeySpec(id: "ctrl-c", title: "Ctrl-C", key: .ctrlC),
            TerminalKeySpec(id: "ctrl-d", title: "Ctrl-D", key: .ctrlD),
            TerminalKeySpec(id: "up", title: "↑", key: .up),
            TerminalKeySpec(id: "down", title: "↓", key: .down),
            TerminalKeySpec(id: "left", title: "←", key: .left),
            TerminalKeySpec(id: "right", title: "→", key: .right),
            TerminalKeySpec(id: "enter", title: "Enter", key: .enter),
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(holdsControl ? "Controlling" : "Observing")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .foregroundStyle(holdsControl ? TerminalChrome.background : TerminalChrome.foreground)
                    .background(
                        holdsControl ? DesignTokens.live : TerminalChrome.muted.opacity(0.35),
                        in: Capsule()
                    )
                    .accessibilityLabel(holdsControl ? "Controlling terminal" : "Observing terminal")
                    .accessibilityAddTraits(.isStaticText)
                Spacer()
                Toggle("Wrap", isOn: $wrap)
                    .toggleStyle(.button)
                    .controlSize(.small)
                    .accessibilityLabel("Wrap terminal text")
                if host.isController, !holdsControl {
                    Button("Request control") { onRequestControl() }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                        .disabled(host.connection != .live)
                        .accessibilityLabel("Request terminal control")
                }
            }

            if !holdsControl {
                Text("Watching this pane. Request control to type.")
                    .font(.footnote)
                    .foregroundStyle(TerminalChrome.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                    .accessibilityLabel("Watching this pane. Request control to type.")
            }

            if let status {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(TerminalChrome.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
            if text.isEmpty {
                ContentUnavailableView(
                    "Terminal unavailable",
                    systemImage: "terminal",
                    description: Text("Select a live pane to observe or request control.")
                )
                .foregroundStyle(TerminalChrome.foreground)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                JumpToLatestScroll(token: text, wrap: wrap) {
                    Text(
                        ansiAttributedString(
                            text,
                            defaultForeground: TerminalChrome.foreground,
                            defaultBackground: TerminalChrome.background
                        )
                    )
                    .font(DesignTokens.Typography.mono)
                    .fixedSize(horizontal: !wrap, vertical: false)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                    .padding()
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(
                            holdsControl ? DesignTokens.live.opacity(0.85) : TerminalChrome.muted.opacity(0.45),
                            lineWidth: 2
                        )
                        .padding(4)
                }
            }
            if host.isController {
                let canSend = !input.isEmpty && host.connection == .live && holdsControl
                VStack(spacing: 8) {
                    if holdsControl {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(keys) { spec in
                                    Button(spec.title) {
                                        onSendKey(spec.key)
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                    .foregroundStyle(TerminalChrome.foreground)
                                    .disabled(host.connection != .live)
                                }
                            }
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack(spacing: 10) {
                        TextField("Send to terminal", text: $input)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.body)
                            .foregroundStyle(TerminalChrome.foreground)
                        Button {
                            let payload = input
                            input = ""
                            onSend(payload)
                        } label: {
                            Image(systemName: "arrow.up")
                                .font(.body.weight(.bold))
                                .foregroundStyle(.white)
                                .frame(width: 32, height: 32)
                                .background(canSend ? DesignTokens.live : Color.secondary.opacity(0.35), in: Circle())
                        }
                        .disabled(!canSend)
                        .buttonStyle(.plain)
                        .accessibilityLabel("Send")
                    }
                    .padding(.leading, 16)
                    .padding(.trailing, 6)
                    .padding(.vertical, 6)
                    .luviaGlass(in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                }
                .padding(.horizontal, DesignTokens.Space.m)
                .padding(.vertical, DesignTokens.Space.s)
                .contentShape(Rectangle())
            }
        }
        .background(TerminalChrome.background)
        .colorScheme(.dark)
    }
}

struct JumpToLatestScroll<Content: View>: View {
    let token: String
    var wrap = false
    @ViewBuilder var content: () -> Content

    @State private var stickToBottom = true
    private let endID = "jump-end"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(wrap ? Axis.Set.vertical : [.horizontal, .vertical]) {
                VStack(alignment: .leading, spacing: 0) {
                    content()
                    Color.clear.frame(width: 1, height: 1).id(endID)
                }
            }
            .onAppear {
                proxy.scrollTo(endID, anchor: .bottomLeading)
            }
            .onChange(of: token) { _, _ in
                if stickToBottom {
                    proxy.scrollTo(endID, anchor: .bottomLeading)
                }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 16).onChanged { value in
                    let vertical = value.translation.height
                    let horizontal = value.translation.width
                    if vertical > 20, abs(vertical) > abs(horizontal) {
                        stickToBottom = false
                    }
                }
            )
            .overlay(alignment: .bottom) {
                if !stickToBottom {
                    Button("Jump to latest") {
                        stickToBottom = true
                        proxy.scrollTo(endID, anchor: .bottomLeading)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.bottom, 12)
                }
            }
        }
    }
}
