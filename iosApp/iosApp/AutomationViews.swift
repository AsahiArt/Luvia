import SwiftUI
import LuviaShared

struct AutomationsSurfaceView: View {
    @Bindable var model: AppModel
    @State private var editor: AutomationEditorSession?
    @State private var historyID: AutomationSheetID?
    @State private var rebindID: AutomationSheetID?
    @State private var pendingDelete: Automation?

    private var canMutate: Bool { model.uhp.isController }
    private var automations: [Automation] {
        KotlinLists.array(model.uhp.snapshot?.automations.automations as Any)
    }
    private var healthById: [String: AutomationView] {
        let views: [AutomationView] = KotlinLists.array(model.uhp.snapshot?.automations.health?.automations as Any)
        return Dictionary(uniqueKeysWithValues: views.map { ($0.id, $0) })
    }

    var body: some View {
        Group {
            if !model.hasLiveSession {
                unavailable(
                    "This Host has not connected yet.",
                    symbol: "bolt.horizontal.circle",
                    text: "Automations appear after the first live session."
                )
            } else if !model.uhp.caps.automationList && !model.uhp.caps.automationHealth {
                unavailable(
                    "Automations",
                    symbol: "clock.arrow.2.circlepath",
                    text: "This Host does not expose Automations."
                )
            } else if automations.isEmpty {
                unavailable(
                    "Automations",
                    symbol: "clock.arrow.2.circlepath",
                    text: "No Automations on this Host."
                )
            } else {
                list
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            SurfaceStatusBanner(model: model)
        }
        .toolbar {
            if canMutate {
                ToolbarItem(placement: .primaryAction) {
                    Button("Add Automation", systemImage: "plus") {
                        editor = AutomationEditorSession(existing: nil)
                    }
                }
            }
        }
        .refreshable { await model.loadAutomations() }
        .sheet(item: $editor) { session in
            AutomationEditorSheet(model: model, session: session)
        }
        .sheet(item: $historyID) { item in
            AutomationHistorySheet(model: model, automationID: item.id)
        }
        .sheet(item: $rebindID) { item in
            AutomationRebindSheet(model: model, automationID: item.id)
        }
        .confirmationDialog(
            "Delete this Automation?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let id = pendingDelete?.id {
                    pendingDelete = nil
                    model.deleteHostAutomation(id)
                }
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            Text(pendingDelete?.name ?? "This Automation will be removed from the Host.")
        }
    }

    private var list: some View {
        List {
            if let summary = automationHealthSummary {
                Section {
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.inkMuted)
                }
            }
            ForEach(automations, id: \.id) { item in
                AutomationCard(
                    automation: item,
                    health: healthById[item.id],
                    lastRun: lastRun(for: item.id),
                    canMutate: canMutate,
                    canToggle: canMutate && (item.enabled ? model.uhp.caps.automationDisable : model.uhp.caps.automationEnable),
                    canRun: canMutate && model.uhp.caps.automationRun,
                    isSending: model.uhp.isSending,
                    onToggle: { enabled in
                        _Concurrency.Task { await model.setAutomationEnabled(item.id, enabled: enabled) }
                    },
                    onRun: { model.runHostAutomation(item.id) },
                    onEdit: { editor = AutomationEditorSession(existing: item) },
                    onHistory: { historyID = AutomationSheetID(id: item.id) },
                    onRebind: { rebindID = AutomationSheetID(id: item.id) },
                    onDelete: { pendingDelete = item }
                )
            }
        }
    }

    private var automationHealthSummary: String? {
        guard let summary = model.uhp.snapshot?.automations.health?.summary else { return nil }
        let enabled = kotlinInt64(summary.enabled) ?? 0
        let scheduled = kotlinInt64(summary.scheduled) ?? 0
        let running = kotlinInt64(summary.running) ?? 0
        let failed = kotlinInt64(summary.failed) ?? 0
        var parts = ["\(enabled) enabled"]
        if scheduled > 0 { parts.append("\(scheduled) scheduled") }
        if running > 0 { parts.append("\(running) running") }
        if failed > 0 { parts.append("\(failed) failed") }
        return parts.joined(separator: " · ")
    }

    private func lastRun(for id: String) -> AutomationRun? {
        automationHistoryRuns(id: id, from: model.uhp.snapshot?.automations).first
    }
}

private struct AutomationCard: View {
    let automation: Automation
    let health: AutomationView?
    let lastRun: AutomationRun?
    let canMutate: Bool
    let canToggle: Bool
    let canRun: Bool
    let isSending: Bool
    let onToggle: (Bool) -> Void
    let onRun: () -> Void
    let onEdit: () -> Void
    let onHistory: () -> Void
    let onRebind: () -> Void
    let onDelete: () -> Void

    private var needsRebind: Bool {
        (automation.targetState ?? health?.targetState)?.lowercased() == "needs_rebind"
    }

    private var lastStatus: String? {
        lastRun?.status ?? health?.latestStatus
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(automation.name)
                    .font(.headline)
                    .foregroundStyle(DesignTokens.ink)
                Spacer(minLength: 8)
                if canToggle {
                    Toggle(
                        automation.enabled ? "Enabled" : "Disabled",
                        isOn: Binding(
                            get: { automation.enabled },
                            set: onToggle
                        )
                    )
                    .labelsHidden()
                    .tint(DesignTokens.live)
                    .disabled(isSending)
                    .accessibilityLabel(automation.enabled ? "Enabled" : "Disabled")
                } else {
                    Text(automation.enabled ? "Enabled" : "Disabled")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(automation.enabled ? DesignTokens.live : DesignTokens.inkMuted)
                }
            }
            Text(AutomationCopy.triggerSummary(automation.trigger))
                .font(.subheadline)
                .foregroundStyle(DesignTokens.inkMuted)
            if let next = kotlinInt64(automation.nextRunAt) ?? kotlinInt64(health?.nextRunAt) {
                Text(AutomationCopy.relative(next))
                    .font(.caption)
                    .foregroundStyle(DesignTokens.inkMuted)
            }
            HStack(spacing: 8) {
                targetChip
                if let lastStatus, !lastStatus.isEmpty {
                    RunStatusPill(status: lastStatus)
                }
            }
            if let error = lastRun?.error ?? health?.latestError, !error.isEmpty {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(3)
            }
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            if canRun {
                Button("Run now", systemImage: "play.fill", action: onRun)
                    .tint(DesignTokens.live)
                    .disabled(isSending)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if canMutate {
                Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
                    .disabled(isSending)
            }
        }
        .contextMenu {
            if canRun {
                Button("Run now", systemImage: "play.fill", action: onRun)
            }
            if canMutate {
                Button("Edit", systemImage: "pencil", action: onEdit)
            }
            Button("History", systemImage: "clock", action: onHistory)
            if canMutate && needsRebind {
                Button("Rebind", systemImage: "link", action: onRebind)
            }
            if canMutate {
                Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
            }
        }
    }

    @ViewBuilder
    private var targetChip: some View {
        if needsRebind {
            HStack(spacing: 6) {
                Text("Needs rebind")
                    .font(.caption.weight(.semibold))
                if canMutate {
                    Button("Rebind", action: onRebind)
                        .font(.caption.weight(.semibold))
                        .disabled(isSending)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(.white)
            .background(DesignTokens.accent, in: Capsule())
        } else {
            Text(AutomationCopy.targetLabel(automation.target))
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .foregroundStyle(DesignTokens.inkMuted)
                .background(DesignTokens.inkMuted.opacity(0.16), in: Capsule())
        }
    }
}

private struct AutomationEditorSession: Identifiable {
    let id: String
    let existing: Automation?

    init(existing: Automation?) {
        self.existing = existing
        self.id = existing?.id ?? "new"
    }
}

private struct AutomationEditorSheet: View {
    @Bindable var model: AppModel
    let session: AutomationEditorSession
    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var enabled: Bool
    @State private var kind: TriggerKind
    @State private var onceDate: Date
    @State private var intervalValue: Int
    @State private var intervalUnit: IntervalUnit
    @State private var timeOfDay: Date
    @State private var timezone: String
    @State private var weekdays: Set<Int>
    @State private var usesLiveAgent: Bool
    @State private var paneId: String
    @State private var ifBusy: String
    @State private var title: String
    @State private var prompt: String
    @State private var agentId: String
    @State private var workspaceId: String
    @State private var mode: String
    @State private var access: String
    @State private var pathsText: String
    @State private var gate: String
    @State private var misfire: String
    @State private var overlap: String
    @State private var graceText: String
    @State private var submitted = false
    @State private var previewTask: _Concurrency.Task<Void, Never>?
    @State private var timezoneQuery = ""

    private var canMutate: Bool { model.uhp.isController }
    private var existingID: String? { session.existing?.id }

    init(model: AppModel, session: AutomationEditorSession) {
        self.model = model
        self.session = session
        let unpacked = UnpackedAutomation(session.existing)
        _name = State(initialValue: unpacked.name)
        _enabled = State(initialValue: unpacked.enabled)
        _kind = State(initialValue: unpacked.kind)
        _onceDate = State(initialValue: unpacked.onceDate)
        _intervalValue = State(initialValue: unpacked.intervalValue)
        _intervalUnit = State(initialValue: unpacked.intervalUnit)
        _timeOfDay = State(initialValue: unpacked.timeOfDay)
        _timezone = State(initialValue: unpacked.timezone)
        _weekdays = State(initialValue: unpacked.weekdays)
        _usesLiveAgent = State(initialValue: unpacked.usesLiveAgent)
        _paneId = State(initialValue: unpacked.paneId)
        _ifBusy = State(initialValue: unpacked.ifBusy)
        _title = State(initialValue: unpacked.title)
        _prompt = State(initialValue: unpacked.prompt)
        _agentId = State(initialValue: unpacked.agentId)
        _workspaceId = State(initialValue: unpacked.workspaceId)
        _mode = State(initialValue: unpacked.mode)
        _access = State(initialValue: unpacked.access)
        _pathsText = State(initialValue: unpacked.pathsText)
        _gate = State(initialValue: unpacked.gate)
        _misfire = State(initialValue: unpacked.misfire)
        _overlap = State(initialValue: unpacked.overlap)
        _graceText = State(initialValue: unpacked.graceText)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                    if canMutate {
                        Toggle("Enabled", isOn: $enabled)
                            .tint(DesignTokens.live)
                    }
                }
                Section("Trigger") {
                    Picker("Kind", selection: $kind) {
                        ForEach(TriggerKind.allCases) { item in
                            Text(item.title).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)
                    triggerFields
                }
                Section("Target") {
                    Picker("Run as", selection: $usesLiveAgent) {
                        Text("New worker").tag(false)
                        Text("Live agent").tag(true)
                    }
                    if usesLiveAgent {
                        if model.uhp.agents.isEmpty {
                            Text("No live agents on this Host.")
                                .foregroundStyle(DesignTokens.inkMuted)
                        } else {
                            Picker("Agent pane", selection: $paneId) {
                                Text("Choose a pane").tag("")
                                ForEach(model.uhp.agents) { agent in
                                    Text(agent.name).tag(agent.id)
                                }
                            }
                            Picker("If busy", selection: $ifBusy) {
                                Text("Wait").tag("wait")
                                Text("Skip").tag("skip")
                            }
                        }
                    }
                }
                Section("Task") {
                    TextField("Title", text: $title)
                    TextEditor(text: $prompt)
                        .frame(minHeight: 88)
                        .accessibilityLabel("Prompt")
                    Picker("Agent", selection: $agentId) {
                        Text("Choose an agent").tag("")
                        ForEach(agentCatalog, id: \.id) { row in
                            Text(row.name).tag(row.id)
                        }
                    }
                    Picker("Workspace", selection: $workspaceId) {
                        Text("Active workspace").tag("")
                        ForEach(workspaceChoices, id: \.id) { row in
                            Text(row.name).tag(row.id)
                        }
                    }
                    Picker("Mode", selection: $mode) {
                        Text("Default").tag("")
                        Text("Worktree").tag("worktree")
                        Text("Workspace").tag("workspace")
                    }
                    TextField("Access", text: $access)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Paths", text: $pathsText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Gate", text: $gate)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                DisclosureGroup("Advanced") {
                    Picker("Misfire", selection: $misfire) {
                        Text("Run latest").tag("run_latest")
                        Text("Skip").tag("skip")
                        Text("Run all").tag("run_all")
                    }
                    Picker("Overlap", selection: $overlap) {
                        Text("Skip").tag("skip")
                        Text("Queue").tag("queue")
                    }
                    TextField("Misfire grace (seconds)", text: $graceText)
                        .keyboardType(.numberPad)
                }
                Section("Next occurrences") {
                    previewStrip
                }
                if let error = model.uhp.snapshot?.automations.editorError, !error.isEmpty {
                    Section {
                        Text(error).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(existingID == nil ? "New Automation" : "Edit Automation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                if canMutate {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(existingID == nil ? "Create" : "Save") { save() }
                            .disabled(!canSave || model.uhp.isSending)
                    }
                }
            }
            .onChange(of: triggerSignature) { _, _ in
                schedulePreview()
            }
            .onChange(of: model.uhp.snapshot?.automations.mutating) { _, mutating in
                if submitted, mutating == false, (model.uhp.snapshot?.automations.editorError ?? "").isEmpty {
                    dismiss()
                }
            }
            .onDisappear {
                previewTask?.cancel()
                model.clearAutomationPreview()
            }
            .task {
                model.loadAcpAgents()
                await model.loadLayout()
                if workspaceId.isEmpty, let active = workspaceChoices.first(where: \.isActive) {
                    workspaceId = active.id
                }
                if agentId.isEmpty, let first = agentCatalog.first {
                    agentId = first.id
                }
                if usesLiveAgent, paneId.isEmpty, let first = model.uhp.agents.first {
                    paneId = first.id
                }
                schedulePreview()
            }
        }
    }

    @ViewBuilder
    private var triggerFields: some View {
        switch kind {
        case .once:
            DatePicker("When", selection: $onceDate, displayedComponents: [.date, .hourAndMinute])
        case .interval:
            Stepper(value: $intervalValue, in: 1...24 * 60) {
                Text("Every \(intervalValue) \(intervalUnit.title)")
            }
            Picker("Unit", selection: $intervalUnit) {
                ForEach(IntervalUnit.allCases) { unit in
                    Text(unit.title).tag(unit)
                }
            }
            .pickerStyle(.segmented)
        case .daily:
            DatePicker("Time", selection: $timeOfDay, displayedComponents: [.hourAndMinute])
            timezonePicker
        case .weekly:
            DatePicker("Time", selection: $timeOfDay, displayedComponents: [.hourAndMinute])
            weekdayChips
            timezonePicker
        }
    }

    private var timezonePicker: some View {
        NavigationLink {
            TimezoneSearchView(selection: $timezone, query: $timezoneQuery)
        } label: {
            HStack {
                Text("Timezone")
                Spacer()
                Text(timezone)
                    .foregroundStyle(DesignTokens.inkMuted)
                    .lineLimit(1)
            }
        }
    }

    private var weekdayChips: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Weekdays")
            HStack(spacing: 6) {
                ForEach(1...7, id: \.self) { day in
                    let selected = weekdays.contains(day)
                    Button(AutomationCopy.weekdayShort(day)) {
                        if selected {
                            weekdays.remove(day)
                        } else {
                            weekdays.insert(day)
                        }
                    }
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .foregroundStyle(selected ? Color.white : DesignTokens.ink)
                    .background(selected ? DesignTokens.accent : DesignTokens.inkMuted.opacity(0.16), in: Capsule())
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }

    @ViewBuilder
    private var previewStrip: some View {
        if model.uhp.snapshot?.automations.previewLoading == true {
            ProgressView()
        } else {
            let stamps = previewEpochs
            if stamps.isEmpty {
                Text("Change the trigger to preview the next runs.")
                    .font(.caption)
                    .foregroundStyle(DesignTokens.inkMuted)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(stamps, id: \.self) { stamp in
                        Text(AutomationCopy.previewStamp(stamp, timezone: timezone))
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.ink)
                    }
                }
            }
        }
    }

    private var previewEpochs: [Int64] {
        let raw: [Any] = KotlinLists.array(model.uhp.snapshot?.automations.preview as Any)
        return raw.compactMap(kotlinInt64)
    }

    private var agentCatalog: [(id: String, name: String)] {
        var seen = Set<String>()
        var rows: [(id: String, name: String)] = []
        let agents: [AcpAgentKind] = KotlinLists.array(model.acpState?.agents as Any)
        for agent in agents where seen.insert(agent.id).inserted {
            rows.append((agent.id, agent.name))
        }
        for agent in model.uhp.agents {
            if let kind = agent.kind, seen.insert(kind).inserted {
                rows.append((kind, kind))
            }
        }
        return rows
    }

    private var workspaceChoices: [WorkspaceChoice] {
        var rows = model.uhp.workspaces.map {
            WorkspaceChoice(id: $0.cwd ?? $0.name, name: $0.name, isActive: $0.isActive)
        }
        if rows.isEmpty {
            rows = (model.selectedHost?.workspaces ?? []).map {
                WorkspaceChoice(id: $0.cwd ?? $0.name, name: $0.name, isActive: $0.isActive)
            }
        }
        return rows
    }

    private var canSave: Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        if kind == .weekly, weekdays.isEmpty { return false }
        if usesLiveAgent, paneId.isEmpty { return false }
        return true
    }

    private var triggerSignature: String {
        "\(kind.rawValue)|\(onceDate.timeIntervalSince1970)|\(intervalValue)|\(intervalUnit.rawValue)|\(secondOfDay)|\(timezone)|\(weekdays.sorted().map(String.init).joined(separator: ","))"
    }

    private var secondOfDay: Int {
        let parts = Calendar.current.dateComponents([.hour, .minute, .second], from: timeOfDay)
        return (parts.hour ?? 0) * 3600 + (parts.minute ?? 0) * 60 + (parts.second ?? 0)
    }

    private func schedulePreview() {
        previewTask?.cancel()
        let trigger = makeTrigger()
        previewTask = _Concurrency.Task {
            try? await _Concurrency.Task.sleep(for: .milliseconds(300))
            guard !_Concurrency.Task.isCancelled else { return }
            await MainActor.run {
                model.previewHostAutomation(trigger)
            }
        }
    }

    private func save() {
        guard let draft = makeDraft() else { return }
        submitted = true
        if let existingID {
            model.updateHostAutomation(id: existingID, draft: draft)
        } else {
            model.createHostAutomation(draft)
        }
    }

    private func makeDraft() -> AutomationDraft? {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { return nil }
        let paths = pathsText
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let grace = Int64(graceText.trimmingCharacters(in: .whitespacesAndNewlines))
        let policy = AutomationPolicySpec(
            misfire: misfire.isEmpty ? nil : misfire,
            overlap: overlap.isEmpty ? nil : overlap,
            misfireGraceSeconds: grace.map { KotlinLong(longLong: $0) }
        )
        let task = AutomationTaskSpec(
            title: title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? trimmedName : title,
            prompt: prompt,
            agentId: agentId,
            workspaceId: workspaceId,
            mode: mode.isEmpty ? nil : mode,
            access: access.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : access,
            paths: paths,
            gate: gate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : gate
        )
        return AutomationDraft(
            name: trimmedName,
            enabled: enabled,
            trigger: makeTrigger(),
            target: makeTarget(),
            task: task,
            policy: policy
        )
    }

    private func makeTrigger() -> AutomationTrigger {
        switch kind {
        case .once:
            return AutomationTrigger.Once(atUtc: Int64(onceDate.timeIntervalSince1970))
        case .interval:
            let seconds = Int64(intervalValue) * intervalUnit.seconds
            let anchor = kotlinInt64(session.existing.flatMap(AutomationCopy.intervalAnchor))
                ?? Int64(Date().timeIntervalSince1970)
            return AutomationTrigger.Interval(everySeconds: seconds, anchorUtc: anchor)
        case .daily:
            return AutomationTrigger.Daily(timezone: timezone, secondOfDay: Int32(secondOfDay))
        case .weekly:
            let days = weekdays.sorted().map { KotlinInt(int: Int32($0)) }
            return AutomationTrigger.Weekly(timezone: timezone, weekdays: days, secondOfDay: Int32(secondOfDay))
        }
    }

    private func makeTarget() -> AutomationTarget {
        if usesLiveAgent, !paneId.isEmpty {
            let terminalId = model.selectedHost?.paneTerminalIds[paneId]
                ?? model.uhp.agents.first { $0.id == paneId }.flatMap(\.terminalId)
                ?? ""
            return AutomationTarget.ActiveAgent(
                paneId: paneId,
                terminalId: terminalId,
                ifBusy: ifBusy.isEmpty ? "wait" : ifBusy,
                binding: nil
            )
        }
        return AutomationTarget.NewWorker.shared
    }
}

private struct TimezoneSearchView: View {
    @Binding var selection: String
    @Binding var query: String
    @Environment(\.dismiss) private var dismiss

    private var identifiers: [String] {
        let all = TimeZone.knownTimeZoneIdentifiers
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return all }
        return all.filter { $0.localizedCaseInsensitiveContains(trimmed) }
    }

    var body: some View {
        List(identifiers, id: \.self) { identifier in
            Button {
                selection = identifier
                dismiss()
            } label: {
                HStack {
                    Text(identifier)
                    Spacer()
                    if identifier == selection {
                        Image(systemName: "checkmark")
                            .foregroundStyle(DesignTokens.accent)
                    }
                }
            }
            .foregroundStyle(DesignTokens.ink)
        }
        .navigationTitle("Timezone")
        .searchable(text: $query, prompt: "Search timezones")
    }
}

private struct AutomationHistorySheet: View {
    @Bindable var model: AppModel
    let automationID: String
    @Environment(\.dismiss) private var dismiss

    private var runs: [AutomationRun] {
        automationHistoryRuns(id: automationID, from: model.uhp.snapshot?.automations)
    }

    private var loading: Bool {
        model.uhp.snapshot?.automations.historyLoading == automationID
    }

    var body: some View {
        NavigationStack {
            Group {
                if loading && runs.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if runs.isEmpty {
                    unavailable("History", symbol: "clock", text: "No runs yet.")
                } else {
                    List(runs, id: \.id) { run in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                RunStatusPill(status: run.status ?? "unknown")
                                Spacer()
                                if let attempt = kotlinInt64(run.attempt) {
                                    Text("Attempt \(attempt)")
                                        .font(.caption)
                                        .foregroundStyle(DesignTokens.inkMuted)
                                }
                            }
                            if let scheduled = kotlinInt64(run.scheduledAt) {
                                Text("Scheduled \(AutomationCopy.relative(scheduled))")
                                    .font(.caption)
                                    .foregroundStyle(DesignTokens.inkMuted)
                            }
                            if let started = kotlinInt64(run.startedAt) {
                                Text("Started \(AutomationCopy.relative(started))")
                                    .font(.caption)
                                    .foregroundStyle(DesignTokens.inkMuted)
                            }
                            if let finished = kotlinInt64(run.finishedAt) {
                                Text("Finished \(AutomationCopy.relative(finished))")
                                    .font(.caption)
                                    .foregroundStyle(DesignTokens.inkMuted)
                            }
                            if let error = run.error, !error.isEmpty {
                                Text(error)
                                    .font(.caption)
                                    .foregroundStyle(.red)
                            }
                            if let taskId = run.taskId, !taskId.isEmpty {
                                Button("Open task") {
                                    model.showWorkspaceTasks()
                                    dismiss()
                                }
                                .font(.caption.weight(.semibold))
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                model.loadAutomationHistory(automationID)
            }
        }
    }
}

private struct AutomationRebindSheet: View {
    @Bindable var model: AppModel
    let automationID: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if model.uhp.agents.isEmpty {
                    unavailable("Rebind", symbol: "link", text: "No live agent panes on this Host.")
                } else {
                    List(model.uhp.agents) { agent in
                        Button {
                            let terminalId = model.selectedHost?.paneTerminalIds[agent.id] ?? agent.terminalId
                            model.rebindHostAutomation(id: automationID, pane: agent.id, terminalId: terminalId)
                            dismiss()
                        } label: {
                            AgentRow(item: AgentListItem(agent))
                        }
                        .disabled(!model.uhp.isController || model.uhp.isSending)
                    }
                }
            }
            .navigationTitle("Rebind")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct RunStatusPill: View {
    let status: String

    var body: some View {
        Text(AutomationCopy.displayStatus(status))
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(foreground)
            .background(background, in: Capsule())
    }

    private var normalized: String { status.lowercased() }

    private var foreground: Color {
        switch normalized {
        case "failed": .white
        case "succeeded", "delivered": DesignTokens.live
        case "running", "starting": DesignTokens.connecting
        case "review": DesignTokens.stale
        default: DesignTokens.inkMuted
        }
    }

    private var background: Color {
        switch normalized {
        case "failed": DesignTokens.accent
        case "succeeded", "delivered": DesignTokens.live.opacity(0.16)
        case "running", "starting": DesignTokens.connecting.opacity(0.16)
        case "review": DesignTokens.stale.opacity(0.16)
        default: DesignTokens.inkMuted.opacity(0.16)
        }
    }
}

private enum TriggerKind: String, CaseIterable, Identifiable {
    case once
    case interval
    case daily
    case weekly

    var id: String { rawValue }

    var title: String {
        switch self {
        case .once: "Once"
        case .interval: "Interval"
        case .daily: "Daily"
        case .weekly: "Weekly"
        }
    }
}

private enum IntervalUnit: String, CaseIterable, Identifiable {
    case seconds
    case minutes
    case hours

    var id: String { rawValue }

    var title: String {
        switch self {
        case .seconds: "sec"
        case .minutes: "min"
        case .hours: "hr"
        }
    }

    var seconds: Int64 {
        switch self {
        case .seconds: 1
        case .minutes: 60
        case .hours: 3600
        }
    }
}

private struct WorkspaceChoice {
    var id: String
    var name: String
    var isActive: Bool
}

private struct UnpackedAutomation {
    var name = ""
    var enabled = true
    var kind: TriggerKind = .daily
    var onceDate = Date()
    var intervalValue = 30
    var intervalUnit: IntervalUnit = .minutes
    var timeOfDay = Calendar.current.date(from: DateComponents(hour: 9, minute: 0)) ?? Date()
    var timezone = TimeZone.current.identifier
    var weekdays: Set<Int> = [1]
    var usesLiveAgent = false
    var paneId = ""
    var ifBusy = "wait"
    var title = ""
    var prompt = ""
    var agentId = ""
    var workspaceId = ""
    var mode = ""
    var access = ""
    var pathsText = ""
    var gate = ""
    var misfire = "run_latest"
    var overlap = "skip"
    var graceText = ""

    init(_ automation: Automation?) {
        guard let automation else { return }
        name = automation.name
        enabled = automation.enabled
        if let trigger = automation.trigger {
            unpack(trigger)
        }
        if let target = automation.target as? AutomationTarget.ActiveAgent {
            usesLiveAgent = true
            paneId = target.paneId
            ifBusy = target.ifBusy ?? "wait"
        }
        if let task = automation.task {
            title = task.title
            prompt = task.prompt
            agentId = task.agentId
            workspaceId = task.workspaceId
            mode = task.mode ?? ""
            access = task.access ?? ""
            let paths: [String] = KotlinLists.array(task.paths as Any)
            pathsText = paths.joined(separator: ", ")
            gate = task.gate ?? ""
        }
        if let policy = automation.policy {
            misfire = policy.misfire ?? "run_latest"
            overlap = policy.overlap ?? "skip"
            if let grace = kotlinInt64(policy.misfireGraceSeconds) {
                graceText = String(grace)
            }
        }
    }

    private mutating func unpack(_ trigger: AutomationTrigger) {
        if let once = trigger as? AutomationTrigger.Once, let at = kotlinInt64(once.atUtc) {
            kind = .once
            onceDate = Date(timeIntervalSince1970: TimeInterval(at))
            return
        }
        if let interval = trigger as? AutomationTrigger.Interval, let every = kotlinInt64(interval.everySeconds) {
            kind = .interval
            if every % 3600 == 0 {
                intervalUnit = .hours
                intervalValue = max(1, Int(every / 3600))
            } else if every % 60 == 0 {
                intervalUnit = .minutes
                intervalValue = max(1, Int(every / 60))
            } else {
                intervalUnit = .seconds
                intervalValue = max(1, Int(every))
            }
            return
        }
        if let daily = trigger as? AutomationTrigger.Daily {
            kind = .daily
            timezone = daily.timezone
            timeOfDay = AutomationCopy.date(secondOfDay: Int(daily.secondOfDay))
            return
        }
        if let weekly = trigger as? AutomationTrigger.Weekly {
            kind = .weekly
            timezone = weekly.timezone
            timeOfDay = AutomationCopy.date(secondOfDay: Int(weekly.secondOfDay))
            let days: [Any] = KotlinLists.array(weekly.weekdays as Any)
            weekdays = Set(days.compactMap(kotlinInt))
            if weekdays.isEmpty { weekdays = [1] }
        }
    }
}

private enum AutomationCopy {
    static func triggerSummary(_ trigger: AutomationTrigger?) -> String {
        guard let trigger else { return "—" }
        if let once = trigger as? AutomationTrigger.Once, let at = kotlinInt64(once.atUtc) {
            let formatter = DateFormatter()
            formatter.dateFormat = "MMM d HH:mm"
            return "Once \(formatter.string(from: Date(timeIntervalSince1970: TimeInterval(at))))"
        }
        if let interval = trigger as? AutomationTrigger.Interval, let every = kotlinInt64(interval.everySeconds) {
            if every % 3600 == 0 {
                let hours = every / 3600
                return hours == 1 ? "Every 1 hr" : "Every \(hours) hr"
            }
            if every % 60 == 0 {
                return "Every \(every / 60) min"
            }
            return "Every \(every)s"
        }
        if let daily = trigger as? AutomationTrigger.Daily {
            return "Daily \(clock(Int(daily.secondOfDay))) \(daily.timezone)"
        }
        if let weekly = trigger as? AutomationTrigger.Weekly {
            let days: [Any] = KotlinLists.array(weekly.weekdays as Any)
            let labels = days.compactMap(kotlinInt).sorted().map(weekdayShort).joined(separator: "/")
            return "Weekly \(labels) \(clock(Int(weekly.secondOfDay)))"
        }
        return "—"
    }

    static func targetLabel(_ target: AutomationTarget?) -> String {
        if let agent = target as? AutomationTarget.ActiveAgent {
            return "Agent pane \(agent.paneId)"
        }
        return "New worker"
    }

    static func relative(_ epochSeconds: Int64) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(
            for: Date(timeIntervalSince1970: TimeInterval(epochSeconds)),
            relativeTo: Date()
        )
    }

    static func previewStamp(_ epochSeconds: Int64, timezone: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "EEE MMM d HH:mm"
        formatter.timeZone = TimeZone(identifier: timezone) ?? .current
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochSeconds)))
    }

    static func displayStatus(_ status: String) -> String {
        guard let first = status.first else { return status }
        return first.uppercased() + status.dropFirst()
    }

    static func weekdayShort(_ day: Int) -> String {
        let names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        guard (1...7).contains(day) else { return "\(day)" }
        return names[day - 1]
    }

    static func clock(_ secondOfDay: Int) -> String {
        let clamped = max(0, secondOfDay)
        return String(format: "%02d:%02d", clamped / 3600, (clamped % 3600) / 60)
    }

    static func date(secondOfDay: Int) -> Date {
        let hour = secondOfDay / 3600
        let minute = (secondOfDay % 3600) / 60
        return Calendar.current.date(from: DateComponents(hour: hour, minute: minute)) ?? Date()
    }

    static func intervalAnchor(_ automation: Automation) -> Any? {
        (automation.trigger as? AutomationTrigger.Interval)?.anchorUtc
    }
}

private func automationHistoryRuns(id: String, from state: AutomationsState?) -> [AutomationRun] {
    let runs = state?.history[id] ?? []
    return runs.sorted { lhs, rhs in
        let left = kotlinInt64(lhs.createdAt) ?? kotlinInt64(lhs.scheduledAt) ?? 0
        let right = kotlinInt64(rhs.createdAt) ?? kotlinInt64(rhs.scheduledAt) ?? 0
        return left > right
    }
}

private func unavailable(_ title: String, symbol: String, text: String) -> some View {
    ContentUnavailableView(title, systemImage: symbol, description: Text(text))
}

private struct AutomationSheetID: Identifiable {
    var id: String
}
