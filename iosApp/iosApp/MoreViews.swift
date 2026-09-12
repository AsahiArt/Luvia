import SwiftUI

struct MoreSurfaceSheet: View {
    @Bindable var model: AppModel
    let surface: MoreSurface
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch surface {
                case .files:
                    FilesSurfaceView(model: model)
                case .search:
                    SearchSurfaceView(model: model)
                case .worktrees:
                    WorktreesSurfaceView(model: model)
                case .automations:
                    AutomationsSurfaceView(model: model)
                case .layout:
                    LayoutSurfaceView(model: model)
                }
            }
            .navigationTitle(surface.title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task(id: surface) {
            // Unstructured so SwiftUI sheet identity churn cannot cancel the Kotlin unary.
            let model = model
            let surface = surface
            _Concurrency.Task { @MainActor in
                await model.loadMoreSurface(surface)
            }
        }
    }
}

struct FilesSurfaceView: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            if !model.hasLiveSession {
                unavailable("Connect to this host", symbol: "bolt.horizontal.circle", text: "A live session is required to load Files.")
            } else if !model.uhp.caps.filesTree {
                unavailable("Files", symbol: "folder", text: "This Host does not expose Files.")
            } else if model.uhp.fileRows.isEmpty {
                unavailable("Files", symbol: "folder", text: "No files in this workspace.")
            } else {
                List(model.uhp.fileRows) { row in
                    HStack(spacing: 8) {
                        Image(systemName: row.isDirectory ? "folder" : "doc")
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name)
                                .font(row.isDirectory ? .body : .system(.body, design: .monospaced))
                            if !row.path.isEmpty, row.path != row.name {
                                Text(row.path)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                        }
                    }
                    .padding(.leading, CGFloat(row.depth) * 12)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        guard !row.isDirectory else { return }
                        _Concurrency.Task { await model.openHostFile(row.path) }
                    }
                    .contextMenu {
                        if model.uhp.allowsMutation && model.uhp.caps.filesOpen && !row.isDirectory {
                            Button("Open") {
                                _Concurrency.Task { await model.openHostFile(row.path) }
                            }
                        }
                        if model.uhp.allowsMutation && model.uhp.caps.filesReveal {
                            Button("Reveal") {
                                _Concurrency.Task { await model.revealHostFile(row.path) }
                            }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            SurfaceStatusBanner(model: model)
        }
        .refreshable { await model.loadFileTree() }
    }
}

struct SearchSurfaceView: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            if !model.hasLiveSession {
                unavailable("Connect to this host", symbol: "bolt.horizontal.circle", text: "A live session is required to search.")
            } else if !model.uhp.caps.searchQuery {
                unavailable("Search", symbol: "magnifyingglass", text: "This Host does not expose Search.")
            } else {
                List {
                    Section {
                        HStack {
                            TextField("Query", text: $model.uhp.searchQuery)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            Button("Search") {
                                _Concurrency.Task { await model.runSearch() }
                            }
                            .disabled(
                                model.uhp.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                    || model.uhp.isSending
                            )
                        }
                    }
                    if model.uhp.searchTotal > 0 || !model.uhp.searchMatches.isEmpty {
                        Section {
                            Text(searchSummary)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Section {
                        if model.uhp.searchMatches.isEmpty {
                            Text("No matches.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(model.uhp.searchMatches) { item in
                                Button {
                                    _Concurrency.Task { await model.activateSearchMatch(item) }
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(item.label)
                                        HStack {
                                            Text(item.kind)
                                                .font(.caption.weight(.semibold))
                                            if let detail = item.detail, !detail.isEmpty {
                                                Text(detail)
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                                    .lineLimit(2)
                                            }
                                        }
                                    }
                                }
                                .disabled(!model.uhp.allowsMutation || !model.uhp.caps.searchActivate)
                            }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            SurfaceStatusBanner(model: model)
        }
        .refreshable { await model.runSearch() }
    }

    private var searchSummary: String {
        var text = "\(model.uhp.searchShown) of \(model.uhp.searchTotal)"
        if model.uhp.searchPartial { text += " · partial" }
        return text
    }
}

struct WorktreesSurfaceView: View {
    @Bindable var model: AppModel
    @State private var pendingRemove: WorktreeItem?

    var body: some View {
        Group {
            if !model.hasLiveSession {
                unavailable("Connect to this host", symbol: "bolt.horizontal.circle", text: "A live session is required to load Worktrees.")
            } else if !model.uhp.caps.worktreeList {
                unavailable("Worktrees", symbol: "arrow.triangle.branch", text: "This Host does not expose Worktrees.")
            } else if model.uhp.worktrees.isEmpty {
                unavailable("Worktrees", symbol: "arrow.triangle.branch", text: "No Worktrees on this Host.")
            } else {
                List(model.uhp.worktrees) { item in
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.branch ?? item.path)
                                .font(.headline)
                            Text(item.path)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                            if let head = item.head, !head.isEmpty {
                                Text(head)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                            if item.isMain {
                                Text("Main")
                                    .font(.caption.weight(.semibold))
                            }
                        }
                        Spacer()
                        if model.uhp.allowsMutation && model.uhp.caps.worktreeOpen {
                            Button("Open") {
                                _Concurrency.Task { await model.openHostWorktree(item.path) }
                            }
                            .disabled(model.uhp.isSending)
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        if model.uhp.allowsMutation && model.uhp.caps.worktreeRemove && !item.isMain {
                            Button("Remove", role: .destructive) { pendingRemove = item }
                                .disabled(model.uhp.isSending)
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            SurfaceStatusBanner(model: model)
        }
        .toolbar {
            if model.uhp.allowsMutation && model.uhp.caps.worktreeCreate {
                ToolbarItem(placement: .primaryAction) {
                    Button("Create", systemImage: "plus") {
                        model.uhp.isCreateWorktreePresented = true
                    }
                }
            }
        }
        .refreshable { await model.loadWorktrees() }
        .sheet(isPresented: $model.uhp.isCreateWorktreePresented) {
            CreateWorktreeSheet(model: model)
        }
        .confirmationDialog(
            "Remove this Worktree?",
            isPresented: Binding(
                get: { pendingRemove != nil },
                set: { if !$0 { pendingRemove = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let path = pendingRemove?.path {
                    pendingRemove = nil
                    _Concurrency.Task { await model.removeHostWorktree(path) }
                }
            }
            Button("Cancel", role: .cancel) { pendingRemove = nil }
        } message: {
            Text(pendingRemove?.path ?? "This Worktree will be removed on the Host.")
        }
    }
}

struct CreateWorktreeSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("Branch", text: $model.uhp.createWorktreeBranch)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .navigationTitle("Create Worktree")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        _Concurrency.Task { await model.createHostWorktree() }
                    }
                    .disabled(
                        model.uhp.createWorktreeBranch.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || !model.uhp.allowsMutation
                    )
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct AutomationsSurfaceView: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            if !model.hasLiveSession {
                unavailable("Connect to this host", symbol: "bolt.horizontal.circle", text: "A live session is required to load Automations.")
            } else if !model.uhp.caps.automationList && !model.uhp.caps.automationHealth {
                unavailable("Automations", symbol: "clock.arrow.2.circlepath", text: "This Host does not expose Automations.")
            } else if model.uhp.automations.isEmpty {
                unavailable("Automations", symbol: "clock.arrow.2.circlepath", text: "No Automations on this Host.")
            } else {
                List {
                    if let summary = model.uhp.automationHealthSummary {
                        Section {
                            Text(summary)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    ForEach(model.uhp.automations) { item in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(item.name)
                                    .font(.headline)
                                Spacer()
                                Text(item.enabled ? "Enabled" : "Disabled")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(item.enabled ? .green : .secondary)
                            }
                            if let state = item.state, !state.isEmpty {
                                Text(state)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            if let nextRun = item.nextRun {
                                Text(nextRun)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if let error = item.latestError, !error.isEmpty {
                                Text(error)
                                    .font(.caption)
                                    .foregroundStyle(.red)
                            }
                            if model.uhp.allowsMutation {
                                HStack {
                                    if item.enabled, model.uhp.caps.automationDisable {
                                        Button("Disable") {
                                            _Concurrency.Task { await model.setAutomationEnabled(item.id, enabled: false) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    } else if !item.enabled, model.uhp.caps.automationEnable {
                                        Button("Enable") {
                                            _Concurrency.Task { await model.setAutomationEnabled(item.id, enabled: true) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    }
                                    if model.uhp.caps.automationRun {
                                        Button("Run") {
                                            _Concurrency.Task { await model.runHostAutomation(item.id) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    }
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            SurfaceStatusBanner(model: model)
        }
        .refreshable { await model.loadAutomations() }
    }
}

struct LayoutSurfaceView: View {
    @Bindable var model: AppModel
    @State private var pendingClosePane: PaneItem?
    @State private var pendingCloseWorkspace: WorkspaceItem?

    var body: some View {
        Group {
            if !model.hasLiveSession {
                unavailable("Connect to this host", symbol: "bolt.horizontal.circle", text: "A live session is required to load Layout.")
            } else if !model.uhp.caps.workspaceList && !model.uhp.caps.paneList {
                unavailable("Layout", symbol: "rectangle.split.3x1", text: "This Host does not expose Layout.")
            } else if model.uhp.workspaces.isEmpty && model.uhp.panes.isEmpty {
                unavailable("Layout", symbol: "rectangle.split.3x1", text: "No workspaces or panes.")
            } else {
                List {
                    if !model.uhp.workspaces.isEmpty {
                        Section("Workspaces") {
                            ForEach(model.uhp.workspaces) { item in
                                HStack(alignment: .firstTextBaseline) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack {
                                            Text(item.name)
                                                .font(.headline)
                                            if item.isActive {
                                                Text("Active")
                                                    .font(.caption.weight(.semibold))
                                            }
                                            if item.isPinned {
                                                Text("Pinned")
                                                    .font(.caption)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                        if let cwd = item.cwd, !cwd.isEmpty {
                                            Text(cwd)
                                                .font(.system(.caption, design: .monospaced))
                                                .foregroundStyle(.secondary)
                                                .lineLimit(2)
                                        }
                                        if let branch = item.branch, !branch.isEmpty {
                                            Text(branch)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    if model.uhp.allowsMutation && model.uhp.caps.workspaceClose {
                                        Button("Close", role: .destructive) { pendingCloseWorkspace = item }
                                            .disabled(model.uhp.isSending)
                                    }
                                }
                            }
                        }
                    }
                    if !model.uhp.panes.isEmpty {
                        Section("Panes") {
                            ForEach(model.uhp.panes) { item in
                                HStack(alignment: .firstTextBaseline) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack {
                                            Text(item.pane)
                                                .font(.system(.headline, design: .monospaced))
                                            if item.isFocused {
                                                Text("Focused")
                                                    .font(.caption.weight(.semibold))
                                            }
                                        }
                                        if let agent = item.agent, !agent.isEmpty {
                                            Text(agent)
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                        Text(item.status)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        if let cwd = item.cwd, !cwd.isEmpty {
                                            Text(cwd)
                                                .font(.system(.caption, design: .monospaced))
                                                .foregroundStyle(.secondary)
                                                .lineLimit(2)
                                        }
                                    }
                                    Spacer()
                                    if model.uhp.allowsMutation && model.uhp.caps.paneFocus && !item.isFocused {
                                        Button("Focus") {
                                            _Concurrency.Task { await model.focusHostPane(item.pane) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    }
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    if model.uhp.allowsMutation && model.uhp.caps.paneClose {
                                        Button("Close", role: .destructive) { pendingClosePane = item }
                                            .disabled(model.uhp.isSending)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            SurfaceStatusBanner(model: model)
        }
        .refreshable { await model.loadLayout() }
        .confirmationDialog(
            "Close this workspace?",
            isPresented: Binding(
                get: { pendingCloseWorkspace != nil },
                set: { if !$0 { pendingCloseWorkspace = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Close", role: .destructive) {
                if let index = pendingCloseWorkspace?.index {
                    pendingCloseWorkspace = nil
                    _Concurrency.Task { await model.closeHostWorkspace(index) }
                }
            }
            Button("Cancel", role: .cancel) { pendingCloseWorkspace = nil }
        } message: {
            Text(pendingCloseWorkspace?.name ?? "This workspace will be closed on the Host.")
        }
        .confirmationDialog(
            "Close this Pane?",
            isPresented: Binding(
                get: { pendingClosePane != nil },
                set: { if !$0 { pendingClosePane = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Close", role: .destructive) {
                if let pane = pendingClosePane?.pane {
                    pendingClosePane = nil
                    _Concurrency.Task { await model.closeHostPane(pane) }
                }
            }
            Button("Cancel", role: .cancel) { pendingClosePane = nil }
        } message: {
            Text(pendingClosePane?.pane ?? "This Pane will be closed on the Host.")
        }
    }
}

struct SurfaceStatusBanner: View {
    @Bindable var model: AppModel

    var body: some View {
        if model.uhp.unconfirmed != nil || !(model.uhp.errorMessage ?? "").isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                if let unconfirmed = model.uhp.unconfirmed {
                    UnconfirmedBanner(action: unconfirmed) {
                        _Concurrency.Task { await model.checkUnconfirmed() }
                    }
                }
                if let error = model.uhp.errorMessage, !error.isEmpty {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
    }
}

private func unavailable(_ title: String, symbol: String, text: String) -> some View {
    ContentUnavailableView(title, systemImage: symbol, description: Text(text))
}
