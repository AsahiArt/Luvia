import SwiftUI

struct WorkspaceSectionView: View {
    @Bindable var model: AppModel
    let host: HostViewState

    private var hasContent: Bool {
        model.hasLiveSession || host.hasCachedContent
    }

    var body: some View {
        VStack(spacing: 0) {
            if !hasContent && host.connection == .connecting {
                EmptyState(title: "Connecting…", message: "Loading this Host.", systemImage: "plus.forwardslash.minus")
            } else if !hasContent {
                EmptyState(
                    title: "This Host has not connected yet.",
                    message: "Review and Tasks appear after the first live session.",
                    systemImage: "plus.forwardslash.minus"
                )
            } else {
                if model.uhp.projectChoices.count != 1 {
                    ProjectChips(
                        choices: model.uhp.projectChoices,
                        selectedId: model.uhp.snapshot?.projectWorkspaceId(),
                        revealToken: model.selectedSection.rawValue,
                        onSelect: { model.selectWorkspace($0) }
                    )
                }
                Picker("Workspace", selection: $model.workspaceSegment) {
                    ForEach(WorkspaceSegment.allCases) { segment in
                        Text(segment.rawValue).tag(segment)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, DesignTokens.Space.m)
                .padding(.vertical, DesignTokens.Space.s)
                .onChange(of: model.workspaceSegment) { _, _ in
                    _Concurrency.Task { await model.loadSelectedSection() }
                }
                if model.uhp.needsProjectPick {
                    EmptyState(
                        title: "Select a project",
                        message: "Review and Tasks are scoped to one project.",
                        systemImage: "plus.forwardslash.minus"
                    )
                } else {
                    switch model.workspaceSegment {
                    case .review:
                        ReviewSectionView(model: model, host: host)
                    case .tasks:
                        TasksSectionView(model: model, host: host)
                    }
                }
            }
        }
    }
}

struct ReviewSectionView: View {
    @Bindable var model: AppModel
    let host: HostViewState

    var body: some View {
        Group {
            if !model.uhp.caps.diffList {
                EmptyState(
                    title: "Review",
                    message: "This Host does not expose Diffs.",
                    systemImage: "plus.forwardslash.minus"
                )
            } else {
                ReviewListView(model: model)
            }
        }
    }
}


struct ProjectPickerBar: View {
    @Bindable var model: AppModel

    var body: some View {
        let choices = model.uhp.projectChoices
        let label = model.uhp.projectLabel ?? "Select a project"
        HStack {
            Text("Project")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            if choices.count >= 2 {
                Picker("Project", selection: Binding(
                    get: { model.uhp.snapshot?.projectWorkspaceId() ?? "" },
                    set: { model.selectWorkspace($0) }
                )) {
                    ForEach(choices, id: \.id) { choice in
                        Text(choice.label).tag(choice.id)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            } else {
                Text(label)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }
}

struct ReviewListView: View {
    @Bindable var model: AppModel
    @State private var layer = "Worktree"
    @State private var notesOnly = false


    private let layers = ["Staged", "Worktree", "Untracked", "Conflict"]

    private var visibleFiles: [DiffFileItem] {
        model.uhp.diffFiles.filter { file in
            guard file.layer == layer else { return false }
            if notesOnly {
                return openNoteCount(for: file) > 0
            }
            return true
        }
    }

    var body: some View {
        Group {
            if model.uhp.diffFiles.isEmpty {
                EmptyState(
                    title: "No Diff for this project.",
                    message: "Pull to refresh when the Host has changes.",
                    systemImage: "plus.forwardslash.minus"
                )
            } else {
                List {
                    Section {
                        Picker("Layer", selection: $layer) {
                            ForEach(layers, id: \.self) { item in
                                Text(item).tag(item)
                            }
                        }
                        .pickerStyle(.segmented)
                        .accessibilityLabel("Layer")
                        Toggle("Files with notes", isOn: $notesOnly)
                            .font(.subheadline)
                    } header: {
                        Text("Layer")
                    }
                    if let branch = model.uhp.diffBranch, !branch.isEmpty {
                        Section {
                            Text(branch)
                                .font(.system(.body, design: .monospaced))
                        } header: {
                            Text("Branch")
                        }
                    }
                    Section {
                        if visibleFiles.isEmpty {
                            Text(notesOnly ? "No files with open Review notes in this layer." : "No files in this layer.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(visibleFiles) { file in
                                if file.isDirectory {
                                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                                        Text(file.path)
                                            .font(.system(.body, design: .monospaced))
                                            .lineLimit(3)
                                        Spacer(minLength: 8)
                                        Text("Directory")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                } else {
                                    NavigationLink(value: file) {
                                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                                            Text(file.path)
                                                .font(.system(.body, design: .monospaced))
                                                .lineLimit(3)
                                            Spacer(minLength: 8)
                                            let notes = openNoteCount(for: file)
                                            if notes > 0 {
                                                Text("\(notes)")
                                                    .font(.caption.weight(.semibold))
                                                    .padding(.horizontal, 6)
                                                    .padding(.vertical, 2)
                                                    .foregroundStyle(.white)
                                                    .background(DesignTokens.accent, in: Capsule())
                                                    .accessibilityLabel("\(notes) open notes")
                                            }
                                            Text("+\(file.additions)")
                                                .font(.caption.monospacedDigit())
                                                .foregroundStyle(DiffPalette.add)
                                            Text("-\(file.deletions)")
                                                .font(.caption.monospacedDigit())
                                                .foregroundStyle(DiffPalette.remove)
                                        }
                                    }
                                }
                            }
                        }
                    } header: {
                        Text(layer)
                    }
                }
            }
        }
        .toolbar {
            if model.uhp.caps.diffNoteList {
                ToolbarItem(placement: .primaryAction) {
                    Button("Notes") { model.uhp.isNotesPresented = true }
                }
            }
            if model.uhp.canSendNotes {
                ToolbarItem(placement: .secondaryAction) {
                    Button("Send notes") { model.uhp.isSendNotesPresented = true }
                }
            }
        }
        .refreshable { await model.loadDiff() }
        .sheet(isPresented: $model.uhp.isNotesPresented) {
            ReviewNotesSheet(model: model)
        }
        .sheet(isPresented: $model.uhp.isSendNotesPresented) {
            SendNotesSheet(model: model)
        }
        .overlay(alignment: .bottom) {
            VStack(spacing: 8) {
                if let unconfirmed = model.uhp.unconfirmed {
                    UnconfirmedBanner(action: unconfirmed) {
                        _Concurrency.Task { await model.checkUnconfirmed() }
                    }
                }
                if let error = model.uhp.errorMessage, !error.isEmpty {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
                if let message = model.uhp.sendNotesMessage {
                    Text(message)
                        .font(.footnote)
                        .padding(12)
                        .frame(maxWidth: .infinity)
                        .background(.ultraThinMaterial)
                        .onTapGesture { model.uhp.sendNotesMessage = nil }
                }
            }
            .padding()
        }
        .onAppear {
            if !model.uhp.diffFiles.contains(where: { $0.layer == layer }) {
                layer = layers.first { candidate in
                    model.uhp.diffFiles.contains { $0.layer == candidate }
                } ?? "Worktree"
            }
        }
    }

    private func openNoteCount(for file: DiffFileItem) -> Int {
        model.uhp.notes.filter { note in
            note.isOpen && note.path == file.path
        }.count
    }
}

struct DiffFileDetailView: View {
    @Bindable var model: AppModel
    let file: DiffFileItem
    @State private var collapsedHunks: Set<String> = []

    private var detail: DiffFileDetail? {
        model.uhp.selectedDiff?.item.id == file.id ? model.uhp.selectedDiff : nil
    }

    private var fileName: String {
        let leaf = URL(fileURLWithPath: file.path).lastPathComponent
        return leaf.isEmpty ? file.path : leaf
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(file.layer)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text("+\(file.additions)")
                            .foregroundStyle(DiffPalette.add)
                        Text("-\(file.deletions)")
                            .foregroundStyle(DiffPalette.remove)
                    }
                    Text(file.path)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                .padding(.horizontal)
                if let unconfirmed = model.uhp.unconfirmed {
                    UnconfirmedBanner(action: unconfirmed) {
                        _Concurrency.Task { await model.checkUnconfirmed() }
                    }
                    .padding(.horizontal)
                }
                if let hunks = detail?.hunks, !hunks.isEmpty {
                    ForEach(hunks) { hunk in
                        DisclosureGroup(
                            isExpanded: Binding(
                                get: { !collapsedHunks.contains(hunk.id) },
                                set: { expanded in
                                    if expanded {
                                        collapsedHunks.remove(hunk.id)
                                    } else {
                                        collapsedHunks.insert(hunk.id)
                                    }
                                }
                            )
                        ) {
                            VStack(alignment: .leading, spacing: 0) {
                                ForEach(hunk.lines) { line in
                                    DiffLineRow(line: line)
                                        .contentShape(Rectangle())
                                        .onTapGesture {
                                            guard model.uhp.canAddNote else { return }
                                            model.beginAddNote(file: file, hunk: hunk, line: line)
                                        }
                                }
                            }
                        } label: {
                            Text(hunk.header)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 4)
                        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                        .padding(.horizontal)
                    }
                } else if model.uhp.snapshot?.review.loading == true {
                    ProgressView()
                        .padding()
                } else {
                    Text("No hunks for this file.")
                        .foregroundStyle(.secondary)
                        .padding()
                }
            }
            .padding(.vertical)
        }
        .navigationTitle(fileName)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $model.uhp.isAddNotePresented) {
            AddNoteSheet(model: model)
        }
    }
}

private enum DiffPalette {
    static let add = DesignTokens.diffAdd
    static let remove = DesignTokens.diffDel
}

private struct DiffLineRow: View {
    let line: DiffLineItem
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(gutter)
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(.secondary)
                .frame(width: 56, alignment: .trailing)
            Text(line.text)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.horizontal)
        .padding(.vertical, 2)
        .background(tint)
    }

    private var gutter: String {
        let old = line.oldLine.map(String.init) ?? ""
        let new = line.newLine.map(String.init) ?? ""
        return "\(old) \(new)"
    }

    private var tint: Color {
        let kind = line.kind.lowercased()
        let opacity = colorScheme == .dark ? 0.32 : 0.16
        if kind.contains("add") || kind == "+" { return DiffPalette.add.opacity(opacity) }
        if kind.contains("del") || kind == "-" { return DiffPalette.remove.opacity(opacity) }
        return .clear
    }
}

struct ReviewNotesSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var openNotes: [ReviewNoteItem] { model.uhp.notes.filter(\.isOpen) }
    private var resolvedNotes: [ReviewNoteItem] { model.uhp.notes.filter(\.isResolved) }

    var body: some View {
        NavigationStack {
            List {
                if let unconfirmed = model.uhp.unconfirmed {
                    Section {
                        UnconfirmedBanner(action: unconfirmed) {
                            _Concurrency.Task { await model.checkUnconfirmed() }
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    }
                }
                if let error = model.uhp.errorMessage, !error.isEmpty {
                    Section {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                Section("Open") {
                    if openNotes.isEmpty {
                        Text("No open Review notes.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(openNotes) { note in
                            NoteRow(note: note)
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    if model.uhp.canResolveNote {
                                        Button("Resolve") {
                                            _Concurrency.Task { await model.resolveNote(note.id) }
                                        }
                                        .tint(.orange)
                                        .disabled(model.uhp.isSending)
                                    }
                                    if model.uhp.canRemoveNote {
                                        Button("Remove", role: .destructive) {
                                            _Concurrency.Task { await model.removeNote(note.id) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    }
                                }
                        }
                    }
                }
                Section("Resolved") {
                    if resolvedNotes.isEmpty {
                        Text("No resolved Review notes.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(resolvedNotes) { note in
                            NoteRow(note: note)
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    if model.uhp.canReopenNote {
                                        Button("Reopen") {
                                            _Concurrency.Task { await model.reopenNote(note.id) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    }
                                    if model.uhp.canRemoveNote {
                                        Button("Remove", role: .destructive) {
                                            _Concurrency.Task { await model.removeNote(note.id) }
                                        }
                                        .disabled(model.uhp.isSending)
                                    }
                                }
                        }
                    }
                }
            }
            .navigationTitle("Review notes")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct NoteRow: View {
    let note: ReviewNoteItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(note.body)
            HStack {
                Text(note.stateLabel)
                    .font(.caption.weight(.semibold))
                if let path = note.path {
                    Text(path)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                if let line = note.line {
                    Text(":\(line)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let deliveries = note.deliveries {
                Text("Delivered to \(deliveries)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

struct AddNoteSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        NavigationStack {
            Form {
                if let error = model.uhp.errorMessage, !error.isEmpty {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                    }
                }
                LabeledContent("File") {
                    Text(model.uhp.addNote.file)
                        .font(.system(.body, design: .monospaced))
                }
                LabeledContent("Line") {
                    Text("\(model.uhp.addNote.line)")
                        .font(.body.monospacedDigit())
                }
                Section("Anchored line") {
                    ForEach(Array(model.uhp.addNote.contextBefore.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Text(model.uhp.addNote.anchoredText.isEmpty ? " " : model.uhp.addNote.anchoredText)
                        .font(.system(.footnote, design: .monospaced).weight(.semibold))
                        .padding(.vertical, 2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(DiffPalette.add.opacity(colorScheme == .dark ? 0.28 : 0.14))
                    ForEach(Array(model.uhp.addNote.contextAfter.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                TextField("Review note", text: $model.uhp.addNote.body, axis: .vertical)
                    .lineLimit(3...8)
                    .disabled(model.uhp.isSending || model.uhp.unconfirmed != nil)
            }
            .navigationTitle("Add Review note")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(model.uhp.isSending)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        _Concurrency.Task { await model.addReviewNote() }
                    }
                    .disabled(
                        model.uhp.addNote.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || !model.uhp.canAddNote
                    )
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(model.uhp.isSending)
    }
}

struct SendNotesSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirm = false

    var body: some View {
        NavigationStack {
            List {
                if model.uhp.agents.isEmpty {
                    Text("No Agents to send to.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.uhp.agents) { agent in
                        Button {
                            model.uhp.sendNotesTarget = agent.id
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(agent.name)
                                    Text(agent.status)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if model.uhp.sendNotesTarget == agent.id {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Send notes")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send notes") { confirm = true }
                        .disabled(model.uhp.sendNotesTarget == nil || !model.uhp.canSendNotes)
                }
            }
            .confirmationDialog(
                "Send notes to this Agent?",
                isPresented: $confirm,
                titleVisibility: .visible
            ) {
                Button("Send notes") {
                    guard let target = model.uhp.sendNotesTarget else { return }
                    _Concurrency.Task { await model.sendReviewNotes(to: target) }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Open Review notes will be delivered as a hand-off. This is not retried automatically.")
            }
        }
    }
}

#Preview("Review list") {
    NavigationStack {
        List {
            Section("Staged") {
                HStack {
                    Text("iosApp/AgentViews.swift")
                        .font(.system(.body, design: .monospaced))
                    Spacer()
                    Text("+24").foregroundStyle(DesignTokens.diffAdd)
                    Text("-3").foregroundStyle(DesignTokens.diffDel)
                }
            }
            Section("Worktree") {
                HStack {
                    Text("shared/src/Client.kt")
                        .font(.system(.body, design: .monospaced))
                    Spacer()
                    Text("+8").foregroundStyle(DesignTokens.diffAdd)
                    Text("-1").foregroundStyle(DesignTokens.diffDel)
                }
            }
        }
        .navigationTitle("Review")
    }
}
