import SwiftUI
import LuviaShared

struct HostDetailView: View {
    let host: HostViewState
    @Binding var section: HostSection
    @Bindable var model: AppModel
    var compactColumn: NavigationSplitViewColumn = .detail
    var chromeNonce: Int = 0

    @State private var agentQuery = ""
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if model.uhp.needsProjectPick {
                    ProjectPickerList(model: model)
                } else {
                    TabView(selection: pageBinding) {
                        AgentsSectionView(model: model, host: host, query: $agentQuery)
                            .tabItem { Label("Threads", systemImage: "bubble.left.and.bubble.right") }
                            .badge(model.uhp.attentionCount)
                            .tag(ProjectPage.threads)
                        WorkspaceSectionView(model: model, host: host, showsSegmentPicker: false)
                            .tabItem { Label("Changes", systemImage: "plus.forwardslash.minus") }
                            .tag(ProjectPage.changes)
                        WorkspaceSectionView(model: model, host: host, showsSegmentPicker: false)
                            .tabItem { Label("Tasks", systemImage: "checklist") }
                            .tag(ProjectPage.tasks)
                    }
                }
            }
            .tint(DesignTokens.accent)
            .navigationTitle(model.uhp.snapshot?.projectLabel() ?? host.name)
            .navigationBarTitleDisplayMode(model.hasLiveSession ? .large : .inline)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    StatusPill.link(host.connection)
                }
                if host.backend == "herdr" {
                    ToolbarItem(placement: .topBarTrailing) {
                        TypeBadge(kind: .herdr)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(visibleTools) { tool in
                            Button(tool.title, systemImage: tool.symbol) { path.append(tool) }
                        }
                        Divider()
                        Button("Host settings", systemImage: "gearshape") {
                            model.isHostSettingsPresented = true
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("Project tools")
                }
            }
            .sheet(isPresented: $model.isHostSettingsPresented) {
                HostSettingsSheet(host: host, model: model)
            }
            .navigationDestination(for: String.self) { id in
                ThreadView(model: model)
                    .task { await model.openAgent(id) }
            }
            .navigationDestination(for: AcpRoute.self) { _ in
                ThreadView(model: model)
                    .onAppear { model.viewAcp() }
            }
            .navigationDestination(for: MoreSurface.self) { surface in
                MoreSurfaceDestination(model: model, surface: surface)
            }
            .navigationDestination(for: DiffFileItem.self) { file in
                DiffFileDetailView(model: model, file: file)
                    .task { await model.openDiffFile(file) }
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
            .onChange(of: model.uhp.snapshot?.acp.viewing) { _, viewing in
                if viewing == true {
                    path.append(AcpRoute.session)
                }
            }
            .onChange(of: compactColumn) { _, column in
                guard column == .sidebar else { return }
                guard model.pendingOpenAgentID == nil else { return }
                path = NavigationPath()
            }
            .onChange(of: chromeNonce) { _, _ in
                guard model.pendingOpenAgentID == nil else { return }
                path = NavigationPath()
            }
            .onAppear {
                if let id = model.pendingOpenAgentID {
                    section = .agents
                    path.append(id)
                    model.pendingOpenAgentID = nil
                }
            }
        }
    }

    private enum ProjectPage: String, CaseIterable, Identifiable {
        case threads, changes, tasks
        var id: Self { self }
    }

    private var pageBinding: Binding<ProjectPage> {
        Binding(
            get: {
                guard section == .workspace else { return .threads }
                return model.workspaceSegment == .tasks ? .tasks : .changes
            },
            set: { page in
                switch page {
                case .threads:
                    section = .agents
                case .changes, .tasks:
                    model.workspaceSegment = page == .tasks ? .tasks : .review
                    if section == .workspace {
                        _Concurrency.Task { await model.loadSelectedSection() }
                    } else {
                        section = .workspace
                    }
                }
            }
        )
    }

    private var visibleTools: [MoreSurface] {
        let all: [MoreSurface] = [.files, .search, .worktrees, .automations, .layout]
        guard let snapshot = model.uhp.snapshot, snapshot.connected else { return all }
        let visible: [LuviaShared.HostSection] = KotlinLists.array(snapshot.visibleSections() as Any)
        return all.filter { tool in
            let wanted: LuviaShared.HostSection = switch tool {
            case .files: .files
            case .search: .search
            case .worktrees: .worktrees
            case .automations: .automations
            case .layout: .layout
            }
            return visible.contains(wanted)
        }
    }
}

private struct HostSettingsSheet: View {
    let host: HostViewState
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var alias: String
    @State private var hosts: String
    @State private var port: String
    @State private var username: String
    @State private var errorMessage: String?
    @State private var saving = false
    @State private var confirmUnpair = false

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
                    Text(host.isController ? "Controller" : "Observer")
                } header: {
                    Text("Role")
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
                } header: {
                    Text("Edit connection")
                } footer: {
                    Text("Comma-separated hosts. SSH host keys stay pinned from pairing.")
                }
                Section {
                    if host.connection == .live || host.connection == .connecting {
                        Button(host.connection == .connecting ? "Cancel" : "Disconnect", role: .destructive) {
                            model.disconnect(host.id)
                            dismiss()
                        }
                    }
                    Button("Unpair", role: .destructive) {
                        confirmUnpair = true
                    }
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Host settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(saving)
                }
            }
            .confirmationDialog(
                "Unpair \(host.name)?",
                isPresented: $confirmUnpair,
                titleVisibility: .visible
            ) {
                Button("Unpair", role: .destructive) {
                    dismiss()
                    _Concurrency.Task { await model.unpair(host.id) }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This device will no longer be able to connect until you pair again.")
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
    var ansi: Bool = true
    let status: String?
    let holdsControl: Bool
    var onSend: (String) -> Void
    var onSendKey: (TerminalKey) -> Void
    var onRequestControl: () -> Void

    @State private var input = ""
    @State private var wrap = false
    @State private var stickToBottom = true

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
                TerminalCanvas(text: text, ansi: ansi, wrap: wrap, stickToBottom: $stickToBottom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .overlay {
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(
                                holdsControl ? DesignTokens.live.opacity(0.85) : TerminalChrome.muted.opacity(0.45),
                                lineWidth: 2
                            )
                            .padding(4)
                    }
                    .overlay(alignment: .bottom) {
                        if !stickToBottom {
                            Button("Jump to latest") {
                                stickToBottom = true
                            }
                            .buttonStyle(.borderedProminent)
                            .padding(.bottom, DesignTokens.Space.m)
                        }
                    }
            }
            if host.isController, holdsControl {
                let canSend = !input.isEmpty && host.connection == .live
                VStack(spacing: 8) {
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
                    .padding(.bottom, DesignTokens.Space.m)
                }
            }
        }
    }
}



/// Shown only when the page was opened without a project (Hosts sheet, notification).
private struct ProjectPickerList: View {
    @Bindable var model: AppModel

    var body: some View {
        List {
            Section("Pick a project") {
                ForEach(model.uhp.projectChoices, id: \.id) { choice in
                    Button(choice.label) { model.selectWorkspace(choice.id) }
                        .foregroundStyle(DesignTokens.ink)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(DesignTokens.canvas.ignoresSafeArea())
    }
}
