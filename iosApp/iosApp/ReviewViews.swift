import SwiftUI

struct ReviewSectionView: View {
    @Bindable var model: AppModel
    let host: HostViewState

    var body: some View {
        if !model.hasLiveSession {
            ContentUnavailableView(
                "Connect to this host",
                systemImage: "bolt.horizontal.circle",
                description: Text("A live session is required to load Agents, Review, and Tasks.")
            )
        } else if !model.uhp.caps.diffList {
            ContentUnavailableView(
                "Review",
                systemImage: "plus.forwardslash.minus",
                description: Text("This Host does not expose Diffs.")
            )
        } else {
            NavigationStack {
                ReviewListView(model: model)
                    .navigationDestination(for: DiffFileItem.self) { file in
                        DiffFileDetailView(model: model, file: file)
                            .task { await model.openDiffFile(file) }
                    }
            }
        }
    }
}

struct ReviewListView: View {
    @Bindable var model: AppModel

    private var grouped: [(layer: String, files: [DiffFileItem])] {
        let order = ["Conflict", "Staged", "Worktree", "Untracked", "Other"]
        let groups = Dictionary(grouping: model.uhp.diffFiles, by: \.layer)
        return order.compactMap { layer in
            guard let files = groups[layer], !files.isEmpty else { return nil }
            return (layer, files)
        } + groups.keys
            .filter { !order.contains($0) }
            .sorted()
            .compactMap { layer in
                guard let files = groups[layer] else { return nil }
                return (layer, files)
            }
    }

    var body: some View {
        Group {
            if model.uhp.diffFiles.isEmpty {
                ContentUnavailableView(
                    "Review",
                    systemImage: "plus.forwardslash.minus",
                    description: Text("No Diffs in this workspace.")
                )
            } else {
                List {
                    if let branch = model.uhp.diffBranch, !branch.isEmpty {
                        Section {
                            Text(branch)
                                .font(.system(.body, design: .monospaced))
                        } header: {
                            Text("Branch")
                        }
                    }
                    ForEach(grouped, id: \.layer) { group in
                        Section(group.layer) {
                            ForEach(group.files) { file in
                                NavigationLink(value: file) {
                                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                                        Text(file.path)
                                            .font(.system(.body, design: .monospaced))
                                            .lineLimit(3)
                                        Spacer(minLength: 8)
                                        Text("+\(file.additions)")
                                            .font(.caption.monospacedDigit())
                                            .foregroundStyle(.green)
                                        Text("-\(file.deletions)")
                                            .font(.caption.monospacedDigit())
                                            .foregroundStyle(.red)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let error = model.uhp.errorMessage, !error.isEmpty {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
        }
        .toolbar {
            if model.uhp.caps.diffNoteList {
                ToolbarItem(placement: .primaryAction) {
                    Button("Notes") { model.uhp.isNotesPresented = true }
                }
            }
            if model.uhp.isController && model.uhp.caps.diffNoteSend {
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
            if let message = model.uhp.sendNotesMessage {
                Text(message)
                    .font(.footnote)
                    .padding(12)
                    .frame(maxWidth: .infinity)
                    .background(.ultraThinMaterial)
                    .onTapGesture { model.uhp.sendNotesMessage = nil }
            }
        }
    }
}

struct DiffFileDetailView: View {
    @Bindable var model: AppModel
    let file: DiffFileItem

    private var detail: DiffFileDetail? {
        model.uhp.selectedDiff?.item.id == file.id ? model.uhp.selectedDiff : nil
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text(file.path)
                        .font(.system(.headline, design: .monospaced))
                    Spacer()
                    Text("+\(file.additions)")
                        .foregroundStyle(.green)
                    Text("-\(file.deletions)")
                        .foregroundStyle(.red)
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
                        VStack(alignment: .leading, spacing: 0) {
                            Text(hunk.header)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal)
                                .padding(.vertical, 6)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color.secondary.opacity(0.12))
                            ForEach(hunk.lines) { line in
                                DiffLineRow(line: line)
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                        guard model.uhp.isController, model.uhp.caps.diffNoteAdd else { return }
                                        model.beginAddNote(file: file, line: line)
                                    }
                            }
                        }
                    }
                } else {
                    Text("No hunks for this file.")
                        .foregroundStyle(.secondary)
                        .padding()
                }
            }
            .padding(.vertical)
        }
        .navigationTitle(file.layer)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $model.uhp.isAddNotePresented) {
            AddNoteSheet(model: model)
        }
    }
}

private struct DiffLineRow: View {
    let line: DiffLineItem

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(gutter)
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(.secondary)
                .frame(width: 56, alignment: .trailing)
            Text(line.text)
                .font(.system(.footnote, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal)
        .padding(.vertical, 2)
        .background(tint.opacity(0.18))
    }

    private var gutter: String {
        let old = line.oldLine.map(String.init) ?? ""
        let new = line.newLine.map(String.init) ?? ""
        return "\(old) \(new)"
    }

    private var tint: Color {
        let kind = line.kind.lowercased()
        if kind.contains("add") || kind == "+" { return .green }
        if kind.contains("del") || kind == "-" { return .red }
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
                Section("Open") {
                    if openNotes.isEmpty {
                        Text("No open Review notes.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(openNotes) { note in
                            NoteRow(note: note)
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    if model.uhp.isController {
                                        Button("Resolve") {
                                            _Concurrency.Task { await model.resolveNote(note.id) }
                                        }
                                        .tint(.orange)
                                        Button("Remove", role: .destructive) {
                                            _Concurrency.Task { await model.removeNote(note.id) }
                                        }
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
                                    if model.uhp.isController {
                                        Button("Reopen") {
                                            _Concurrency.Task { await model.reopenNote(note.id) }
                                        }
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

    var body: some View {
        NavigationStack {
            Form {
                LabeledContent("File") {
                    Text(model.uhp.addNote.file)
                        .font(.system(.body, design: .monospaced))
                }
                LabeledContent("Line") {
                    Text("\(model.uhp.addNote.line)")
                        .font(.body.monospacedDigit())
                }
                TextField("Review note", text: $model.uhp.addNote.body, axis: .vertical)
                    .lineLimit(3...8)
            }
            .navigationTitle("Add Review note")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        _Concurrency.Task { await model.addReviewNote() }
                    }
                    .disabled(model.uhp.addNote.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
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
                        .disabled(model.uhp.sendNotesTarget == nil || model.uhp.isSending)
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
                    Text("+24").foregroundStyle(.green)
                    Text("-3").foregroundStyle(.red)
                }
            }
            Section("Worktree") {
                HStack {
                    Text("shared/src/Client.kt")
                        .font(.system(.body, design: .monospaced))
                    Spacer()
                    Text("+8").foregroundStyle(.green)
                    Text("-1").foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Review")
    }
}
