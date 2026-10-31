import LuviaShared
import SwiftUI

struct ChatView: View {
    @Bindable var model: AppModel
    var thread: AgentThread
    var items: [TimelineItem]
    var queueIndex: Int
    var queueTotal: Int
    var onBack: () -> Void
    var onTerminal: () -> Void
    var glass: Namespace.ID

    @State private var draft = ""
    @State private var selectedOptionID: String?
    @FocusState private var composerFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                }
                .accessibilityLabel("返回")
                VStack(alignment: .leading) {
                    Text(thread.title).font(.headline).lineLimit(1)
                    Text(sessionLabel(thread.status)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(">_")
                    .font(.system(.body, design: .monospaced).weight(.bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .terminalGlass(namespace: glass)
                    .onTapGesture(perform: onTerminal)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(visibleItems.enumerated()), id: \.offset) { index, item in
                            timelineRow(item)
                                .id(index)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: visibleItems.count) { _, count in
                    if count > 0 {
                        withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) }
                    }
                }
            }
            if let ask = thread.ask {
                decision(ask)
            }
            composer
        }
    }

    private var visibleItems: [TimelineItem] {
        items.filter { item in
            if case .askCard = onEnum(of: item) { return false }
            return true
        }
    }

    @ViewBuilder
    private func timelineRow(_ item: TimelineItem) -> some View {
        switch onEnum(of: item) {
        case .output(let row):
            outputBlocks(row.text)
        case .mine(let row):
            bubble(row.text, mine: true)
        case .thought(let row):
            ThoughtBlock(text: row.text)
        case .tool(let row):
            ToolTrailView(call: row.call, canAsk: model.selectedHost?.isController == true) {
                send("请修这个失败：\n\($0)")
            }
        case .plan(let row):
            PlanBlock(entries: KotlinLists.array(row.entries as Any))
        case .status(let row):
            statusBar(statusTitle(label: row.label, status: row.status))
        case .unconfirmed(let row):
            VStack(alignment: .leading, spacing: 4) {
                Text("未确认").font(.headline)
                Text(row.text)
                Text("结果丢了，不会再次发送。")
                    .font(.caption)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        case .askCard:
            EmptyView()
        }
    }

    private func outputBlocks(_ text: String) -> some View {
        let runs: [OutputRun] = KotlinLists.array(OutputRunsKt.outputRuns(text: text) as Any)
        return VStack(alignment: .leading, spacing: 8) {
            if runs.isEmpty {
                bubble(text, mine: false)
            } else {
                ForEach(Array(runs.enumerated()), id: \.offset) { _, run in
                    if run.mono {
                        Text(run.text)
                            .font(.caption.monospaced())
                            .foregroundStyle(Color.primary)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    } else {
                        bubble(run.text, mine: false)
                    }
                }
            }
        }
    }

    private func statusTitle(label: String?, status: AgentStatus?) -> String {
        if let label, !label.isEmpty {
            return label
        }
        return status.map(sessionLabel) ?? "更新"
    }

    private func statusBar(_ title: String) -> some View {
        HStack(spacing: 8) {
            Rectangle().fill(Color.secondary.opacity(0.35)).frame(height: 1)
            Text(title)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(2)
            Rectangle().fill(Color.secondary.opacity(0.35)).frame(height: 1)
        }
    }

    private func bubble(_ text: String, mine: Bool) -> some View {
        HStack {
            if mine { Spacer(minLength: 48) }
            Text(text)
                .foregroundStyle(mine ? Color.white : Color.primary)
                .padding(12)
                .background(
                    mine ? Color.blue : Color.primary.opacity(0.06),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                )
            if !mine { Spacer(minLength: 48) }
        }
    }

    private func decision(_ ask: Ask) -> some View {
        let options: [AskOption] = KotlinLists.array(ask.options as Any)
        let recommended = SessionListKt.recommendedOption(ask: ask)
        return VStack(alignment: .leading, spacing: 10) {
            Capsule().fill(Color.primary.opacity(0.25)).frame(width: 36, height: 4).frame(maxWidth: .infinity)
            Text("\(queueIndex) / \(queueTotal)").font(.caption.weight(.semibold))
            Text(ask.question ?? "有一个决定在等你").font(.title3.weight(.semibold))
            ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                let picked = optionID(option) == (selectedOptionID ?? recommended.map(optionID))
                Button {
                    selectedOptionID = optionID(option)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(option.label).font(.headline)
                            Spacer()
                            if option.recommended || optionID(option) == recommended.map(optionID) {
                                Text("agent 推荐").font(.caption2)
                            }
                        }
                        if let consequence = option.consequence, !consequence.isEmpty {
                            Text(consequence).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(picked ? Color.blue : .clear, lineWidth: 2)
                    )
                }
                .buttonStyle(.plain)
            }
            Button("选定并继续") {
                let choice = options.first { optionID($0) == (selectedOptionID ?? recommended.map(optionID)) }
                if let choice {
                    model.uhpRegistry.answer(thread: thread, option: choice)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(.blue)
            .disabled(model.selectedHost?.isController != true)
        }
        .padding(16)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 32, style: .continuous), tint: .blue)
    }

    private var composer: some View {
        HStack {
            TextField(model.selectedHost?.isController == true ? "消息" : "观察者只能看，不能发", text: $draft, axis: .vertical)
                .focused($composerFocused)
            Button {
                send(draft)
            } label: {
                Image(systemName: "arrow.up.circle.fill").font(.title2)
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.selectedHost?.isController != true)
        }
        .padding(12)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        model.hostUhp()?.sendToThread(text: trimmed)
        if trimmed == draft.trimmingCharacters(in: .whitespacesAndNewlines) {
            draft = ""
        }
    }

    private func optionID(_ option: AskOption) -> String {
        switch onEnum(of: option.action) {
        case .prompt(let prompt):
            "p:\(prompt.text)"
        case .keys(_):
            "k:\(option.label)"
        case .acpOption(let option):
            "a:\(option.optionId)"
        }
    }
}

private struct ThoughtBlock: View {
    var text: String
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(open ? "思考" : "思考 · 轻点展开") { open.toggle() }
                .font(.caption)
                .foregroundStyle(.secondary)
            if open {
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

struct ToolTrailView: View {
    var call: AcpToolCall
    var canAsk: Bool
    var onFix: (String) -> Void

    var body: some View {
        let kind = SessionListKt.toolTrailKind(kind: call.kind, title: call.title)
        let lines: [String] = KotlinLists.array(SessionListKt.diffLines(summary: call.summary) as Any)
        let meter = SessionListKt.parseRunMeter(summary: call.summary)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(chipTitle(kind))
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .luviaGlass(in: Capsule(), tint: chipColor(kind))
                Text(call.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(4)
            }
            if !lines.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.prefix(40).enumerated()), id: \.offset) { _, line in
                        HStack(spacing: 0) {
                            Rectangle()
                                .fill(diffBar(line))
                                .frame(width: 4)
                            Text(line)
                                .font(.caption.monospaced())
                                .foregroundStyle(Color.primary)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(diffFill(line))
                        }
                    }
                }
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            if let meter {
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(meter.passed) / \(meter.total)").font(.caption.monospaced())
                    let cells = min(32, Int(meter.total))
                    let failed = max(0, Int(meter.total) - Int(meter.passed))
                    HStack(spacing: 2) {
                        ForEach(0..<max(cells, 0), id: \.self) { index in
                            let bad = cells > 0 && index >= cells - min(cells, failed)
                            RoundedRectangle(cornerRadius: 1)
                                .fill(bad ? Color.red : Color.blue)
                                .frame(width: 6, height: 14)
                        }
                    }
                }
                if failedCount(meter) > 0 && canAsk {
                    Button("交给 claude 修") {
                        onFix(call.summary ?? call.title)
                    }
                    .font(.caption)
                }
            } else if lines.isEmpty, let summary = call.summary, !summary.isEmpty {
                Text(summary).font(.caption.monospaced())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func failedCount(_ meter: RunMeter) -> Int {
        max(0, Int(meter.total) - Int(meter.passed))
    }

    private func chipTitle(_ kind: ToolTrailKind) -> String {
        switch kind {
        case .read: "读"
        case .edit: "改"
        case .run: "跑"
        default: "工具"
        }
    }

    private func chipColor(_ kind: ToolTrailKind) -> Color {
        switch kind {
        case .read: .secondary
        case .edit: .blue
        case .run: .red
        default: .gray
        }
    }

    private func diffBar(_ line: String) -> Color {
        if line.hasPrefix("+") { return .green }
        if line.hasPrefix("-") { return .red }
        return .clear
    }

    private func diffFill(_ line: String) -> Color {
        if line.hasPrefix("+") { return Color.green.opacity(0.14) }
        if line.hasPrefix("-") { return Color.red.opacity(0.14) }
        return .clear
    }
}

private struct PlanBlock: View {
    var entries: [AcpPlanEntry]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("计划").font(.caption.weight(.semibold))
            ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                Text("· \(entry.content)").font(.subheadline)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
