import SwiftUI
import UIKit
import LuviaShared

struct AcpLaunchSheet: View {
    @Bindable var model: AppModel

    private var acp: AcpState? { model.acpState }
    private var agents: [AcpAgentKind] { KotlinLists.array(acp?.agents as Any) }

    private var canLaunch: Bool {
        guard let acp else { return false }
        let cwd = acp.launchCwd.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cwd.isEmpty, let id = acp.launchAgentId, !id.isEmpty else { return false }
        return agents.contains { $0.id == id && $0.available }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.Space.l) {
                    VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                        Text("Launch an agent")
                            .font(DesignTokens.Typography.title)
                            .foregroundStyle(DesignTokens.ink)
                        Text("Runs on the host over ACP. Approvals arrive here as buttons.")
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.inkMuted)
                    }

                    if acp?.agentsLoading == true && agents.isEmpty {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, DesignTokens.Space.l)
                    } else if agents.isEmpty {
                        Text("No agents on this host.")
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.inkMuted)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(agents, id: \.id) { agent in
                                agentRow(agent)
                                if agent.id != agents.last?.id {
                                    Divider()
                                }
                            }
                        }
                        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
                    }

                    VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                        Text("Directory")
                            .font(.caption)
                            .foregroundStyle(DesignTokens.inkMuted)
                        TextField(
                            "Working directory",
                            text: Binding(
                                get: { acp?.launchCwd ?? "" },
                                set: { model.setLaunchAcpCwd($0) }
                            )
                        )
                        .font(.system(.body, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(DesignTokens.Space.m)
                        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
                    }

                    Button("Launch") {
                        model.launchAcp()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(DesignTokens.accent)
                    .disabled(!canLaunch || acp?.agentsLoading == true)
                    .frame(maxWidth: .infinity)
                }
                .padding(DesignTokens.Space.l)
            }
            .background(DesignTokens.canvas)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.setShowLaunchAcp(false) }
                }
            }
        }
        .task {
            model.loadAcpAgents()
        }
    }

    private func agentRow(_ agent: AcpAgentKind) -> some View {
        let selected = acp?.launchAgentId == agent.id
        return Button {
            if agent.available {
                model.setLaunchAcpAgent(agent.id)
            }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Space.m) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(agent.name)
                        .font(.headline)
                        .foregroundStyle(agent.available ? DesignTokens.ink : DesignTokens.inkMuted)
                    Text(agent.command)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(2)
                }
                Spacer(minLength: DesignTokens.Space.s)
                if !agent.available {
                    Text("Not installed")
                        .font(.caption)
                        .foregroundStyle(DesignTokens.inkMuted)
                } else if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(DesignTokens.accent)
                        .accessibilityLabel("Selected")
                }
            }
            .padding(DesignTokens.Space.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!agent.available)
        .opacity(agent.available ? 1 : 0.55)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct AcpSessionView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var acp: AcpState? { model.acpState }
    private var info: AcpSessionInfo? { acp?.info }
    private var transcript: [AcpTranscriptItem] { KotlinLists.array(acp?.transcript as Any) }
    private var plan: [AcpPlanEntry] { KotlinLists.array(acp?.plan as Any) }
    private var cwdLeaf: String? {
        guard let cwd = info?.cwd, !cwd.isEmpty else { return nil }
        let leaf = URL(fileURLWithPath: cwd).lastPathComponent
        return leaf.isEmpty ? cwd : leaf
    }

    private var canStop: Bool {
        switch acp?.run {
        case .working, .awaitingPermission: true
        default: false
        }
    }

    private var canSend: Bool {
        guard let acp else { return false }
        let draft = acp.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !draft.isEmpty, acp.permission == nil else { return false }
        switch acp.run {
        case .starting, .exited: return false
        default: return true
        }
    }

    private var scrollToken: String {
        let last = transcript.last
        let tail: String
        if let message = last as? AcpTranscriptItem.Message {
            tail = "\(message.id):\(message.text.count):\(message.streaming)"
        } else {
            tail = last?.id ?? ""
        }
        return "\(transcript.count)|\(tail)"
    }

    var body: some View {
        ZStack {
            DesignTokens.canvas.ignoresSafeArea()
            sessionBody
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text(info?.agentName ?? "Agent")
                        .font(.headline)
                        .foregroundStyle(DesignTokens.ink)
                    if let cwdLeaf {
                        Text(cwdLeaf)
                            .font(.caption)
                            .foregroundStyle(DesignTokens.inkMuted)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    if canStop {
                        Button("Cancel turn") { model.cancelAcp() }
                    }
                    Button("End session", role: .destructive) {
                        model.closeAcp()
                        dismiss()
                    }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if !plan.isEmpty {
                AcpPlanCard(entries: plan)
                    .padding(.horizontal, DesignTokens.Space.m)
                    .padding(.bottom, DesignTokens.Space.s)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            bottomBar
        }
        .tint(DesignTokens.accent)
        .onDisappear {
            model.hideAcp()
        }
    }

    @ViewBuilder
    private var sessionBody: some View {
        switch acp?.run {
        case .starting:
            ProgressView("Starting agent…")
                .foregroundStyle(DesignTokens.ink)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        default:
            VStack(spacing: 0) {
                if acp?.run == .exited {
                    AcpExitBanner(message: acp?.exitMessage) {
                        model.closeAcp()
                        dismiss()
                    }
                    .padding(.horizontal, DesignTokens.Space.m)
                    .padding(.top, DesignTokens.Space.s)
                }
                transcriptScroll
            }
        }
    }

    private var transcriptScroll: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: DesignTokens.Space.m) {
                    ForEach(transcript, id: \.id) { item in
                        AcpTranscriptRow(item: item)
                            .id(item.id)
                    }
                    Color.clear.frame(height: 1).id("acp-end")
                }
                .padding(.horizontal, DesignTokens.Space.m)
                .padding(.vertical, DesignTokens.Space.s)
            }
            .scrollDismissesKeyboard(.interactively)
            .onAppear {
                proxy.scrollTo("acp-end", anchor: .bottom)
            }
            .onChange(of: scrollToken) { _, _ in
                proxy.scrollTo("acp-end", anchor: .bottom)
            }
        }
    }

    private var bottomBar: some View {
        VStack(spacing: DesignTokens.Space.s) {
            if let permission = acp?.permission {
                let options: [AcpPermissionOption] = KotlinLists.array(permission.options as Any)
                BlockedCard(
                    title: permission.title,
                    message: permission.description_?.isEmpty == false
                        ? (permission.description_ ?? "")
                        : (permission.toolTitle ?? "This Agent is waiting."),
                    observer: model.uhp.isController == false,
                    options: options.map { option in
                        (id: option.optionId, title: option.name, kind: blockedKind(option.kind))
                    },
                    onOption: { optionId in
                        model.answerAcpPermission(optionId)
                    }
                )
                .padding(.horizontal, DesignTokens.Space.m)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if let error = acp?.errorText, !error.isEmpty {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, DesignTokens.Space.m)
            }
            composer
        }
        .animation(.easeOut(duration: 0.22), value: acp?.permission?.requestId)
        .padding(.bottom, DesignTokens.Space.s)
        .background(DesignTokens.canvas)
    }

    private func blockedKind(_ kind: AcpPermissionKind) -> BlockedOptionKind {
        switch kind {
        case .allowOnce, .allowAlways: .allow
        case .rejectOnce, .rejectAlways: .reject
        default: .other
        }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField(
                "Agent prompt",
                text: Binding(
                    get: { acp?.draft ?? "" },
                    set: { model.setAcpDraft($0) }
                ),
                axis: .vertical
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .lineLimit(1...5)
            .onSubmit {
                if canSend { model.promptAcp() }
            }
            Button {
                model.promptAcp()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title)
                    .foregroundStyle(canSend ? DesignTokens.accent : DesignTokens.inkMuted.opacity(0.45))
            }
            .disabled(!canSend)
            .buttonStyle(.plain)
            .accessibilityLabel("Send")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, DesignTokens.Space.m)
    }
}

private struct AcpTranscriptRow: View {
    let item: AcpTranscriptItem

    var body: some View {
        if let message = item as? AcpTranscriptItem.Message {
            AcpMessageBubble(message: message)
        } else if let tool = item as? AcpTranscriptItem.Tool {
            AcpToolRow(call: tool.call)
        } else if let turn = item as? AcpTranscriptItem.Turn {
            AcpTurnDivider(reason: turn.stopReason)
        }
    }
}

private struct AcpMessageBubble: View {
    let message: AcpTranscriptItem.Message

    var body: some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: 48)
                Text(message.text)
                    .foregroundStyle(DesignTokens.ink)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        DesignTokens.accent.opacity(0.18),
                        in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous)
                    )
            }
        case .thought:
            AcpThoughtBlock(text: message.text)
        default:
            HStack(alignment: .bottom, spacing: 0) {
                HStack(alignment: .bottom, spacing: 4) {
                    Text(message.text)
                        .foregroundStyle(DesignTokens.ink)
                        .textSelection(.enabled)
                    if message.streaming {
                        AcpStreamingCursor()
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    DesignTokens.surface,
                    in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous)
                )
                Spacer(minLength: 48)
            }
        }
    }
}

private struct AcpThoughtBlock: View {
    let text: String
    @State private var expanded = false

    var body: some View {
        DisclosureGroup("Thinking", isExpanded: $expanded) {
            Text(text)
                .font(.body.italic())
                .foregroundStyle(DesignTokens.inkMuted)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.subheadline)
        .foregroundStyle(DesignTokens.inkMuted)
        .tint(DesignTokens.inkMuted)
    }
}

private struct AcpToolRow: View {
    let call: AcpToolCall

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Space.s) {
            Image(systemName: toolSymbol(call.kind))
                .foregroundStyle(DesignTokens.inkMuted)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: DesignTokens.Space.s) {
                    Text(call.title)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.ink)
                        .lineLimit(2)
                    Circle()
                        .fill(statusColor(call.status))
                        .frame(width: 8, height: 8)
                        .accessibilityLabel(statusLabel(call.status))
                }
                if let summary = call.summary, !summary.isEmpty {
                    Text(summary)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(DesignTokens.inkMuted)
                        .textSelection(.enabled)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    private func toolSymbol(_ kind: String?) -> String {
        switch kind?.lowercased() {
        case "read": "doc.text"
        case "edit": "pencil"
        case "execute": "terminal"
        case "search": "magnifyingglass"
        case "fetch": "arrow.down.circle"
        case "think": "brain"
        default: "wrench"
        }
    }

    private func statusColor(_ status: AcpToolStatus) -> Color {
        switch status {
        case .pending: DesignTokens.inkMuted
        case .inProgress: DesignTokens.connecting
        case .completed: DesignTokens.live
        case .failed: Color.red
        default: DesignTokens.inkMuted
        }
    }

    private func statusLabel(_ status: AcpToolStatus) -> String {
        switch status {
        case .pending: "Pending"
        case .inProgress: "In progress"
        case .completed: "Completed"
        case .failed: "Failed"
        default: "Unknown"
        }
    }
}

private struct AcpTurnDivider: View {
    let reason: AcpStopReason

    var body: some View {
        HStack(spacing: DesignTokens.Space.s) {
            Rectangle()
                .fill(DesignTokens.inkMuted.opacity(0.3))
                .frame(height: 1)
            Text(label)
                .font(.caption)
                .foregroundStyle(DesignTokens.inkMuted)
                .fixedSize()
            Rectangle()
                .fill(DesignTokens.inkMuted.opacity(0.3))
                .frame(height: 1)
        }
        .padding(.vertical, DesignTokens.Space.xs)
    }

    private var label: String {
        switch reason {
        case .endTurn: "Turn ended"
        case .maxTokens: "Max tokens"
        case .maxTurnRequests: "Max turn requests"
        case .refusal: "Refusal"
        case .cancelled: "Cancelled"
        default: "Turn ended"
        }
    }
}

private struct AcpPlanCard: View {
    let entries: [AcpPlanEntry]
    @State private var expanded = true

    var body: some View {
        DisclosureGroup("Plan", isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
                ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                    HStack(alignment: .firstTextBaseline, spacing: DesignTokens.Space.s) {
                        Image(systemName: planSymbol(entry.status))
                            .foregroundStyle(planColor(entry.status))
                        Text(entry.content)
                            .font(.subheadline)
                            .foregroundStyle(DesignTokens.ink)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding(.top, DesignTokens.Space.s)
        }
        .font(DesignTokens.Typography.heading)
        .foregroundStyle(DesignTokens.ink)
        .padding(DesignTokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass()
    }

    private func planSymbol(_ status: AcpPlanStatus) -> String {
        switch status {
        case .completed: "checkmark.circle"
        case .inProgress: "circle.dotted"
        default: "circle"
        }
    }

    private func planColor(_ status: AcpPlanStatus) -> Color {
        switch status {
        case .completed: DesignTokens.live
        case .inProgress: DesignTokens.connecting
        default: DesignTokens.inkMuted
        }
    }
}

private struct AcpPermissionCard: View {
    let request: AcpPermissionRequest
    let onSelect: (String) -> Void

    private var options: [AcpPermissionOption] {
        KotlinLists.array(request.options as Any)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
            Text(request.title)
                .font(.headline)
                .foregroundStyle(DesignTokens.ink)
            if let description = request.description_, !description.isEmpty {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.inkMuted)
            } else if let toolTitle = request.toolTitle, !toolTitle.isEmpty {
                Text(toolTitle)
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.inkMuted)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: DesignTokens.Space.s) {
                    ForEach(options, id: \.optionId) { option in
                        permissionButton(option)
                    }
                }
                VStack(spacing: DesignTokens.Space.s) {
                    ForEach(options, id: \.optionId) { option in
                        permissionButton(option)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .padding(DesignTokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass()
        .padding(.horizontal, DesignTokens.Space.m)
    }

    @ViewBuilder
    private func permissionButton(_ option: AcpPermissionOption) -> some View {
        switch option.kind {
        case .allowOnce, .allowAlways:
            Button(option.name) { onSelect(option.optionId) }
                .buttonStyle(.borderedProminent)
                .tint(DesignTokens.accent)
        case .rejectOnce, .rejectAlways:
            Button(option.name) { onSelect(option.optionId) }
                .buttonStyle(.bordered)
                .tint(Color.red)
        default:
            Button(option.name) { onSelect(option.optionId) }
                .buttonStyle(.bordered)
                .tint(DesignTokens.inkMuted)
        }
    }
}

private struct AcpExitBanner: View {
    let message: String?
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Space.s) {
            if let message, !message.isEmpty {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.ink)
            } else {
                Text("The agent has exited.")
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.ink)
            }
            Button("Close", action: onClose)
                .buttonStyle(.bordered)
        }
        .padding(DesignTokens.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            DesignTokens.stale.opacity(0.16),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous)
        )
    }
}

private struct AcpStreamingCursor: View {
    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.53)) { timeline in
            let on = Int(timeline.date.timeIntervalSinceReferenceDate / 0.53) % 2 == 0
            RoundedRectangle(cornerRadius: 1, style: .continuous)
                .fill(DesignTokens.ink)
                .frame(width: 7, height: 16)
                .opacity(on ? 1 : 0.12)
                .accessibilityHidden(true)
        }
    }
}
