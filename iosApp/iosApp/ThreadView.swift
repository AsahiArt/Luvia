import SwiftUI
import LuviaShared

/// One conversation screen for pane Agents and ACP sessions.
struct ThreadView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var showTerminal = false

    private var snapshot: HostUhpState? { model.uhp.snapshot }
    private var thread: AgentThread? {
        _ = snapshot
        return model.hostUhp()?.openThread()
    }
    private var isPane: Bool { thread?.kind == .pane }

    var body: some View {
        ZStack {
            DesignTokens.canvas.ignoresSafeArea()
            if !isPane, snapshot?.acp.run == .starting {
                ProgressView("Starting agent…")
                    .foregroundStyle(DesignTokens.ink)
            } else if let thread {
                ThreadTimelineView(
                    items: timeline(for: thread),
                    canAnswer: idle,
                    observer: snapshot?.isObserver ?? true,
                    onAnswer: { model.hostUhp()?.answer(option: $0) }
                )
            } else {
                EmptyState(title: "Conversation ended", message: "This Agent is no longer running.")
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { titleBlock }
            ToolbarItem(placement: .primaryAction) { overflowMenu }
        }
        .safeAreaInset(edge: .top, spacing: 0) { banners }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let thread {
                ThreadComposer(
                    thread: thread,
                    draft: draftBinding,
                    canSend: canSend,
                    canKeys: canKeys,
                    observer: snapshot?.isObserver ?? true,
                    onSend: sendDraft,
                    onCommand: { command in
                        model.hostUhp()?.sendToThread(text: command.name)
                        draftBinding.wrappedValue = ""
                        if command.needsTerminal && isPane { showTerminal = true }
                    },
                    onKey: { model.hostUhp()?.sendKeyBar(key: $0) },
                    onOpenTerminal: isPane ? { showTerminal = true } : nil
                )
            }
        }
        .tint(DesignTokens.accent)
        .fullScreenCover(isPresented: $showTerminal) {
            TerminalControlView(model: model, title: thread?.title ?? "Terminal") { showTerminal = false }
        }
        .sheet(isPresented: $model.uhp.isNameAgentPresented) { NameAgentSheet(model: model) }
        .sheet(isPresented: $model.uhp.isForkAgentPresented) { ForkAgentSheet(model: model) }
        .onDisappear {
            // fullScreenCover also fires onDisappear on the presenter.
            guard !showTerminal else { return }
            if isPane { model.closeOpenAgent() } else { model.hideAcp() }
        }
    }

    private func timeline(for thread: AgentThread) -> [TimelineItem] {
        KotlinLists.array(model.hostUhp()?.timeline(thread: thread) as Any)
    }

    private var sending: Bool { isPane && snapshot?.agentDetail.sending == true }
    private var unconfirmed: UnconfirmedKind? { isPane ? snapshot?.agentDetail.unconfirmed : nil }
    private var idle: Bool { snapshot?.canMutate == true && !sending && unconfirmed == nil }
    private var canSend: Bool { idle && (!isPane || snapshot?.capabilities.agentPrompt == true) }
    private var canKeys: Bool { isPane && idle && snapshot?.capabilities.agentKeys == true }
    private var errorText: String? {
        isPane ? snapshot?.agentDetail.errorText : snapshot?.acp.errorText
    }

    private var draftBinding: Binding<String> {
        Binding(
            get: { (isPane ? snapshot?.agentDetail.draft : snapshot?.acp.draft) ?? "" },
            set: { text in
                if isPane { model.hostUhp()?.setAgentDraft(text: text) } else { model.setAcpDraft(text) }
            }
        )
    }

    private func sendDraft() {
        if isPane {
            model.hostUhp()?.promptAgent(text: draftBinding.wrappedValue)
        } else {
            model.promptAcp()
        }
    }

    private var titleBlock: some View {
        VStack(spacing: 1) {
            HStack(spacing: 6) {
                Text(thread?.title ?? "Agent")
                    .font(.headline)
                    .foregroundStyle(DesignTokens.ink)
                    .lineLimit(1)
                if let status = thread?.status { StatusGlyph(status: status) }
            }
            if let project = thread?.projectLabel {
                Button(project) { model.showProjectReview() }
                    .font(.caption)
                    .foregroundStyle(DesignTokens.inkMuted)
                    .disabled(!isPane)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var overflowMenu: some View {
        let caps = snapshot?.capabilities
        let paneMutate = isPane && snapshot?.canMutate == true
        Menu {
            if paneMutate && caps?.agentName == true {
                Button("Rename") { model.beginNameAgent() }
            }
            if paneMutate && caps?.agentFork == true {
                Button("Fork") { model.beginForkAgent() }
            }
            if !isPane {
                Button("Cancel turn") { model.cancelAcp() }
                Button("End session", role: .destructive) {
                    model.closeAcp()
                    dismiss()
                }
            }
        } label: {
            Label("More", systemImage: "ellipsis.circle")
        }
    }

    @ViewBuilder
    private var banners: some View {
        VStack(spacing: DesignTokens.Space.s) {
            if unconfirmed != nil, let action = model.uhp.unconfirmed {
                UnconfirmedBanner(action: action) {
                    model.hostUhp()?.checkAgent()
                }
            }
            if !isPane, snapshot?.acp.run == .exited {
                AcpExitBanner(message: snapshot?.acp.exitMessage) {
                    model.closeAcp()
                    dismiss()
                }
            }
            if let errorText, !errorText.isEmpty {
                Text(errorText)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, DesignTokens.Space.m)
    }
}

/// Status as shape plus color so it reads without color vision.
struct StatusGlyph: View {
    let status: AgentStatus

    var body: some View {
        Text("\(glyph) \(label)")
            .font(.caption.weight(.medium))
            .foregroundStyle(color)
    }

    private var glyph: String {
        switch status {
        case .blocked: "●"
        case .working: "◐"
        case .idle: "○"
        case .done: "✓"
        default: "◌"
        }
    }

    var label: String {
        switch status {
        case .blocked: "Needs you"
        case .working: "Working"
        case .idle: "Idle"
        case .done: "Done"
        default: "Unknown"
        }
    }

    private var color: Color {
        switch status {
        case .blocked: DesignTokens.agentBlocked
        case .working: DesignTokens.agentWorking
        case .idle, .done: DesignTokens.agentIdle
        default: DesignTokens.agentUnknown
        }
    }
}

struct ThreadTimelineView: View {
    let items: [TimelineItem]
    let canAnswer: Bool
    let observer: Bool
    let onAnswer: (AskOption) -> Void

    private var scrollToken: String {
        guard let last = items.last else { return "0" }
        if let output = last as? TimelineItem.Output {
            return "\(items.count)|\(output.id)|\(output.text.count)"
        }
        return "\(items.count)|\(last.id)"
    }

    var body: some View {
        if items.isEmpty {
            Text("Nothing yet.")
                .foregroundStyle(DesignTokens.inkMuted)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: DesignTokens.Space.m) {
                        ForEach(items, id: \.id) { item in
                            row(item).id(item.id)
                        }
                        Color.clear.frame(height: 1).id("thread-end")
                    }
                    .padding(.horizontal, DesignTokens.Space.m)
                    .padding(.vertical, DesignTokens.Space.s)
                }
                .scrollDismissesKeyboard(.interactively)
                .onAppear { proxy.scrollTo("thread-end", anchor: .bottom) }
                .onChange(of: scrollToken) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("thread-end", anchor: .bottom) }
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ item: TimelineItem) -> some View {
        if let output = item as? TimelineItem.Output {
            OutputBlock(text: output.text, streaming: output.streaming)
        } else if let mine = item as? TimelineItem.Mine {
            MineBubble(text: mine.text, unconfirmed: false)
        } else if let pending = item as? TimelineItem.Unconfirmed {
            MineBubble(text: pending.text, unconfirmed: true)
        } else if let thought = item as? TimelineItem.Thought {
            AcpThoughtBlock(text: thought.text)
        } else if let tool = item as? TimelineItem.Tool {
            AcpToolRow(call: tool.call)
        } else if let plan = item as? TimelineItem.Plan {
            AcpPlanCard(entries: KotlinLists.array(plan.entries as Any))
        } else if let status = item as? TimelineItem.Status {
            StatusDivider(label: status.status.map { StatusGlyph(status: $0).label } ?? "Turn ended")
        } else if let ask = item as? TimelineItem.AskCard {
            AttentionCard(ask: ask.ask, canAnswer: canAnswer, observer: observer, onAnswer: onAnswer)
        }
    }
}

/// Agent text in body sans; fenced code, indented code and box drawing stay mono so TUI output keeps its shape.
private struct OutputBlock: View {
    let text: String
    let streaming: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(outputRuns(text).enumerated()), id: \.offset) { _, run in
                if run.mono {
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(run.text)
                            .font(DesignTokens.Typography.mono)
                            .foregroundStyle(DesignTokens.ink)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                    }
                    .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
                } else {
                    Text(run.text)
                        .font(.body)
                        .foregroundStyle(DesignTokens.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if streaming { AcpStreamingCursor() }
        }
        .textSelection(.enabled)
    }
}

struct OutputRun {
    var text: String
    var mono: Bool
}

func outputRuns(_ text: String) -> [OutputRun] {
    var runs: [OutputRun] = []
    var fenced = false
    for sub in text.split(separator: "\n", omittingEmptySubsequences: false) {
        let line = String(sub)
        if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") {
            fenced.toggle()
            continue
        }
        let mono = fenced || looksPreformatted(line)
        if var last = runs.last, last.mono == mono {
            last.text += "\n" + line
            runs[runs.count - 1] = last
        } else {
            runs.append(OutputRun(text: line, mono: mono))
        }
    }
    return runs
        .map { OutputRun(text: $0.text.trimmingCharacters(in: .newlines), mono: $0.mono) }
        .filter { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }
}

private func looksPreformatted(_ line: String) -> Bool {
    if line.hasPrefix("    ") || line.hasPrefix("\t") { return true }
    return line.unicodeScalars.contains { (0x2500...0x259F).contains($0.value) }
}

private struct MineBubble: View {
    let text: String
    let unconfirmed: Bool

    var body: some View {
        HStack {
            Spacer(minLength: 48)
            VStack(alignment: .trailing, spacing: 2) {
                Text(text)
                    .foregroundStyle(DesignTokens.ink)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(
                        DesignTokens.surface,
                        in: RoundedRectangle(cornerRadius: DesignTokens.Radius.l, style: .continuous)
                    )
                if unconfirmed {
                    Text("Unconfirmed — not resent")
                        .font(.caption2)
                        .foregroundStyle(DesignTokens.linkStale)
                }
            }
        }
    }
}

private struct StatusDivider: View {
    let label: String

    var body: some View {
        HStack(spacing: DesignTokens.Space.s) {
            Rectangle().fill(DesignTokens.inkMuted.opacity(0.3)).frame(height: 1)
            Text(label).font(.caption).foregroundStyle(DesignTokens.inkMuted).fixedSize()
            Rectangle().fill(DesignTokens.inkMuted.opacity(0.3)).frame(height: 1)
        }
    }
}

/// The one card that uses the accent: an Agent waiting on you.
struct AttentionCard: View {
    let ask: Ask
    let canAnswer: Bool
    let observer: Bool
    let onAnswer: (AskOption) -> Void

    private var options: [AskOption] { KotlinLists.array(ask.options as Any) }

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Space.m) {
            Text(ask.question ?? "Waiting for your decision")
                .font(.headline)
                .foregroundStyle(DesignTokens.ink)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: DesignTokens.Space.s) { buttons }
                VStack(alignment: .leading, spacing: DesignTokens.Space.s) { buttons }
            }
            if observer {
                Text("Observer — can't answer")
                    .font(.caption)
                    .foregroundStyle(DesignTokens.inkMuted)
            }
        }
        .padding(DesignTokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            DesignTokens.accent.opacity(0.10),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.l, style: .continuous)
        )
        .sensoryFeedback(.success, trigger: canAnswer)
    }

    @ViewBuilder
    private var buttons: some View {
        ForEach(options, id: \.label) { option in
            Button(option.label) { onAnswer(option) }
                .buttonStyle(AskButtonStyle(tone: option.tone))
                .disabled(!canAnswer)
        }
    }
}

private struct AskButtonStyle: ButtonStyle {
    let tone: AskTone
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .foregroundStyle(foreground)
            .background(background, in: Capsule())
            .overlay {
                if tone == .destructive { Capsule().stroke(Color.red.opacity(0.6)) }
            }
            .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.4)
    }

    private var foreground: Color {
        switch tone {
        case .primary: .white
        case .destructive: .red
        default: DesignTokens.ink
        }
    }

    private var background: Color {
        switch tone {
        case .primary: DesignTokens.accent
        case .destructive: .clear
        default: DesignTokens.surface
        }
    }
}

struct ThreadComposer: View {
    let thread: AgentThread
    @Binding var draft: String
    let canSend: Bool
    let canKeys: Bool
    let observer: Bool
    let onSend: () -> Void
    let onCommand: (SlashCommand) -> Void
    let onKey: (KeyBarKey) -> Void
    let onOpenTerminal: (() -> Void)?

    private var matches: [SlashCommand] {
        guard draft.hasPrefix("/"), !draft.contains(" ") else { return [] }
        let all: [SlashCommand] = KotlinLists.array(thread.commands() as Any)
        return KotlinLists.array(SlashCommandKt.filter(commands: all, query: draft) as Any)
    }

    private var sendEnabled: Bool {
        canSend && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: DesignTokens.Space.s) {
            if !matches.isEmpty {
                CommandPalette(commands: matches, enabled: canSend, onPick: onCommand)
            }
            if canKeys {
                KeyBar(onKey: onKey)
            }
            HStack(alignment: .bottom, spacing: 10) {
                TextField(observer ? "Observer — read only" : "Message, or / for commands", text: $draft, axis: .vertical)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(1...5)
                    .disabled(observer)
                Button(action: onSend) {
                    Image(systemName: "arrow.up")
                        .font(.body.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(sendEnabled ? DesignTokens.accent : Color.secondary.opacity(0.35), in: Circle())
                }
                .disabled(!sendEnabled)
                .buttonStyle(.plain)
                .accessibilityLabel("Send")
                if let onOpenTerminal {
                    Button(action: onOpenTerminal) {
                        Image(systemName: "apple.terminal")
                            .font(.body)
                            .foregroundStyle(DesignTokens.ink)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Terminal control")
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, 6)
            .padding(.vertical, 6)
            .luviaGlass(in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, DesignTokens.Space.m)
        }
        .padding(.bottom, DesignTokens.Space.s)
    }
}

private struct CommandPalette: View {
    let commands: [SlashCommand]
    let enabled: Bool
    let onPick: (SlashCommand) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(commands, id: \.name) { command in
                    Button { onPick(command) } label: {
                        HStack(spacing: DesignTokens.Space.m) {
                            Text(command.name)
                                .font(DesignTokens.Typography.mono)
                                .foregroundStyle(DesignTokens.ink)
                            Text(command.description_)
                                .font(.footnote)
                                .foregroundStyle(DesignTokens.inkMuted)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                            if command.needsTerminal {
                                Text("↗ terminal")
                                    .font(.caption2)
                                    .foregroundStyle(DesignTokens.inkMuted)
                            }
                        }
                        .padding(.horizontal, DesignTokens.Space.m)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                }
            }
        }
        .frame(maxHeight: 240)
        .fixedSize(horizontal: false, vertical: true)
        .luviaGlass()
        .padding(.horizontal, DesignTokens.Space.m)
    }
}

private struct KeyBar: View {
    let onKey: (KeyBarKey) -> Void
    private let keys: [KeyBarKey] = [.esc, .tab, .shiftTab, .ctrlC, .up, .down, .enter]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(keys, id: \.self) { key in
                    KeyChip(title: key.label) { onKey(key) }
                }
            }
            .padding(.horizontal, DesignTokens.Space.m)
        }
    }
}

extension KeyBarKey {
    var label: String {
        switch self {
        case .esc: "Esc"
        case .tab: "Tab"
        case .shiftTab: "⇧Tab"
        case .ctrlC: "^C"
        case .up: "↑"
        case .down: "↓"
        case .enter: "⏎"
        }
    }
}

/// Full-screen Terminal control. Observes only while shown (ADR 0001).
private struct TerminalControlView: View {
    @Bindable var model: AppModel
    let title: String
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                DesignTokens.Terminal.background.ignoresSafeArea()
                if let host = model.selectedHost {
                    TerminalPane(
                        host: host,
                        text: model.terminalText,
                        ansi: model.terminalAnsi,
                        status: model.terminalStatus,
                        holdsControl: model.holdsTerminalControl,
                        onSend: { text in _Concurrency.Task { await model.sendTerminal(text) } },
                        onSendKey: { key in _Concurrency.Task { await model.sendTerminalKey(key) } },
                        onRequestControl: { model.requestTerminalControl() }
                    )
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done", action: onDone)
                }
            }
        }
        .onAppear { model.setTerminalVisible(true) }
        .onDisappear { model.setTerminalVisible(false) }
    }
}
