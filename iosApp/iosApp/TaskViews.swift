import SwiftUI

struct TasksSectionView: View {
    @Bindable var model: AppModel
    let host: HostViewState

    var body: some View {
        if !model.hasLiveSession {
            ContentUnavailableView(
                "Connect to this host",
                systemImage: "bolt.horizontal.circle",
                description: Text("A live session is required to load Agents, Review, and Tasks.")
            )
        } else if !model.uhp.caps.taskList && host.tasks.isEmpty {
            ContentUnavailableView(
                "Tasks",
                systemImage: "checklist",
                description: Text("This Host does not expose the Task board.")
            )
        } else {
            TasksListView(model: model)
        }
    }
}

struct TasksListView: View {
    @Bindable var model: AppModel
    @State private var pendingComplete: TaskViewState?

    private var grouped: [(status: String, tasks: [TaskViewState])] {
        let order = ["blocked", "running", "claimed", "queued", "review", "failed", "merging", "merged", "done"]
        let groups = Dictionary(grouping: model.uhp.tasks) { $0.status.lowercased() }
        let known = order.compactMap { key -> (String, [TaskViewState])? in
            guard let tasks = groups[key], !tasks.isEmpty else { return nil }
            return (displayStatus(key), tasks)
        }
        let extra = groups.keys
            .filter { !order.contains($0) }
            .sorted()
            .compactMap { key -> (String, [TaskViewState])? in
                guard let tasks = groups[key] else { return nil }
                return (displayStatus(key), tasks)
            }
        return known + extra
    }

    var body: some View {
        Group {
            if model.uhp.tasks.isEmpty {
                ContentUnavailableView(
                    "Tasks",
                    systemImage: "checklist",
                    description: Text("No Tasks on the board.")
                )
            } else {
                List {
                    if let message = model.uhp.boardChangedMessage {
                        Section {
                            Text(message)
                                .foregroundStyle(.orange)
                        }
                    }
                    if let unconfirmed = model.uhp.unconfirmed {
                        Section {
                            UnconfirmedBanner(action: unconfirmed) {
                                _Concurrency.Task { await model.checkUnconfirmed() }
                            }
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                        }
                    }
                    ForEach(grouped, id: \.status) { group in
                        Section(group.status) {
                            ForEach(group.tasks) { task in
                                HStack(alignment: .firstTextBaseline) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(task.title)
                                            .font(.headline)
                                        StatusChip(
                                            status: displayStatus(task.status),
                                            isBlocked: task.status.lowercased() == "blocked"
                                        )
                                    }
                                    Spacer()
                                    if model.uhp.isController
                                        && model.uhp.caps.taskDone
                                        && model.uhp.unconfirmed == nil
                                        && canComplete(task.status)
                                    {
                                        Button("Complete") { pendingComplete = task }
                                            .disabled(model.uhp.isSending)
                                    }
                                }
                                .padding(.vertical, 4)
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
            if model.uhp.isController && model.uhp.caps.taskAdd && model.uhp.unconfirmed == nil {
                ToolbarItem(placement: .primaryAction) {
                    Button("Add Task", systemImage: "plus") {
                        model.uhp.isAddTaskPresented = true
                    }
                }
            }
        }
        .refreshable { await model.loadTasks() }
        .sheet(isPresented: $model.uhp.isAddTaskPresented) {
            AddTaskSheet(model: model)
        }
        .confirmationDialog(
            "Complete this Task?",
            isPresented: Binding(
                get: { pendingComplete != nil },
                set: { if !$0 { pendingComplete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Complete") {
                if let id = pendingComplete?.id {
                    pendingComplete = nil
                    _Concurrency.Task { await model.completeTask(id) }
                }
            }
            Button("Cancel", role: .cancel) { pendingComplete = nil }
        } message: {
            Text(pendingComplete?.title ?? "This Task will be marked done.")
        }
    }

    private func canComplete(_ status: String) -> Bool {
        let value = status.lowercased()
        return value != "done" && value != "merged" && value != "failed"
    }

    private func displayStatus(_ status: String) -> String {
        guard let first = status.first else { return status }
        return first.uppercased() + status.dropFirst()
    }
}

struct AddTaskSheet: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $model.uhp.addTaskTitle)
                TextField("Paths (comma separated)", text: $model.uhp.addTaskPaths, axis: .vertical)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(2...4)
            }
            .navigationTitle("Add Task")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        _Concurrency.Task { await model.addTask() }
                    }
                    .disabled(
                        model.uhp.addTaskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || model.uhp.isSending
                            || model.uhp.unconfirmed != nil
                    )
                }
            }
        }
        .presentationDetents([.medium])
    }
}

#Preview("Tasks list") {
    NavigationStack {
        List {
            Section("Blocked") {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Ship UHP phone UI")
                            .font(.headline)
                        StatusChip(status: "Blocked", isBlocked: true)
                    }
                    Spacer()
                    Button("Complete") {}
                }
            }
            Section("Queued") {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Write contract tests")
                        .font(.headline)
                    StatusChip(status: "Queued", isBlocked: false)
                }
            }
            Section("Done") {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Pairing flow")
                        .font(.headline)
                    StatusChip(status: "Done", isBlocked: false)
                }
            }
        }
        .navigationTitle("Tasks")
    }
}
