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
    var showsChrome: Bool = true
    var glass: Namespace.ID
    var nextQuestion: String? = nil

    @State private var draft = ""
    @State private var decisionNote = ""
    @State private var selectedOptionID: String?
    @FocusState private var composerFocused: Bool

    private var hostOffline: Bool {
        model.selectedHost?.connection != .live
    }

    var body: some View {
        VStack(spacing: 0) {
            if showsChrome {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                }
                .accessibilityLabel("返回")
                VStack(alignment: .leading) {
                    Text(thread.title).font(.headline).lineLimit(1)
                    Text(headerStatus).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if hostOffline, let host = model.selectedHost {
                    Button("重试") { model.connect(host.id) }
                        .buttonStyle(.bordered)
                        .tint(.blue)
                }
                Label("终端", systemImage: "terminal")
                    .font(.subheadline.weight(.semibold))
                    .labelStyle(.titleAndIcon)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .terminalGlass(namespace: glass)
                    .onTapGesture(perform: onTerminal)
                    .accessibilityLabel("终端")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            if hostOffline, let host = model.selectedHost {
                VStack(alignment: .leading, spacing: 4) {
                    Text(connectionLabel(host.connection)).font(.headline)
                    Text("缓存").font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(Array(chatRows.enumerated()), id: \.offset) { index, row in
                            Group {
                                switch row {
                                case .single(let item):
                                    timelineRow(item)
                                case .tools(let calls):
                                    ToolStream(calls: calls, canAsk: model.selectedHost?.isController == true && !hostOffline) {
                                        send("请修这个失败：\n\($0)")
                                    }
                                }
                            }
                            .id(index)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: chatRows.count) { _, count in
                    if count > 0 {
                        withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) }
                    }
                }
            }
            if showsChrome, let ask = thread.ask {
                decision(ask)
            }
            if showsChrome {
                composer
            }
        }
    }

    private var headerStatus: String {
        if hostOffline, let host = model.selectedHost {
            return "\(connectionLabel(host.connection)) · 缓存"
        }
        return sessionLabel(thread.status)
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
            ToolStream(calls: [row.call], canAsk: model.selectedHost?.isController == true && !hostOffline) {
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
                            .font(.custom(TerminalFont.postScriptName, size: 12))
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
                    in: UnevenRoundedRectangle(
                        topLeadingRadius: 20,
                        bottomLeadingRadius: mine ? 20 : 6,
                        bottomTrailingRadius: mine ? 6 : 20,
                        topTrailingRadius: 20,
                        style: .continuous
                    )
                )
            if !mine { Spacer(minLength: 48) }
        }
    }

    private var canCompose: Bool {
        model.selectedHost?.isController == true && !hostOffline
    }

    private func decision(_ ask: Ask) -> some View {
        let options: [AskOption] = KotlinLists.array(ask.options as Any)
        let recommended = SessionListKt.recommendedOption(ask: ask)
        let peek = queueTotal > queueIndex
        return ZStack(alignment: .top) {
            if peek {
                VStack(alignment: .leading, spacing: 6) {
                    Text(nextQuestion ?? "还有一个决定")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 22)
                .frame(maxWidth: .infinity, alignment: .leading)
                .luviaGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .padding(.horizontal, 22)
                .allowsHitTesting(false)
            }
            VStack(alignment: .leading, spacing: 10) {
                let agent = thread.agentType?.trimmingCharacters(in: .whitespacesAndNewlines)
                let name = (agent?.isEmpty == false ? agent! : thread.title)
                HStack(spacing: 8) {
                    Text(String(name.prefix(1)).uppercased())
                        .font(.caption.weight(.bold))
                        .frame(width: 28, height: 28)
                        .background(Color.blue.opacity(0.18), in: Circle())
                    Text("\(name) 需要你决定 · \(queueIndex)/\(queueTotal)")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
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
                TextField("补充说明", text: $decisionNote, axis: .vertical)
                    .padding(12)
                    .luviaGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .disabled(!canCompose)
                Button("继续") {
                    let choice = options.first { optionID($0) == (selectedOptionID ?? recommended.map(optionID)) }
                    if let choice {
                        confirm(choice)
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(.blue)
                .disabled(!canCompose)
            }
            .padding(16)
            .luviaGlass(in: RoundedRectangle(cornerRadius: 32, style: .continuous), tint: .blue)
            .padding(.top, peek ? 36 : 0)
        }
        .padding(.horizontal, 8)
    }

    private func confirm(_ choice: AskOption) {
        let note = decisionNote.trimmingCharacters(in: .whitespacesAndNewlines)
        if !note.isEmpty, thread.kind == .pane, case .prompt(let prompt) = onEnum(of: choice.action) {
            let base = prompt.text.trimmingCharacters(in: .whitespacesAndNewlines)
            let text = base.isEmpty ? note : "\(base)\n\(note)"
            model.hostUhp()?.sendToThread(text: text)
        } else {
            model.uhpRegistry.answer(thread: thread, option: choice)
        }
        decisionNote = ""
    }

    private var composer: some View {
        HStack {
            TextField(composerPlaceholder, text: $draft, axis: .vertical)
                .focused($composerFocused)
                .disabled(!canCompose)
            Button {
                send(draft)
            } label: {
                Image(systemName: "arrow.up.circle.fill").font(.title2)
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !canCompose)
        }
        .padding(12)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .opacity(canCompose ? 1 : 0.45)
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private var composerPlaceholder: String {
        if hostOffline { return "离线，无法发送" }
        if model.selectedHost?.isController == true { return "消息" }
        return "观察者只能看，不能发"
    }

    private func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        model.hostUhp()?.sendToThread(text: trimmed)
        if trimmed == draft.trimmingCharacters(in: .whitespacesAndNewlines) {
            draft = ""
        }
    }

    private enum ChatRow {
        case single(TimelineItem)
        case tools([AcpToolCall])
    }

    private var chatRows: [ChatRow] {
        var rows: [ChatRow] = []
        var calls: [AcpToolCall] = []
        func flush() {
            if !calls.isEmpty {
                rows.append(.tools(calls))
                calls = []
            }
        }
        for item in visibleItems {
            if case .tool(let row) = onEnum(of: item) {
                calls.append(row.call)
            } else {
                flush()
                rows.append(.single(item))
            }
        }
        flush()
        return rows
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

struct ToolStream: View {
    var calls: [AcpToolCall]
    var canAsk: Bool
    var onFix: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let parts: [ToolStreamPart] = KotlinLists.array(ToolTrailKt.toolStreamParts(calls: calls) as Any)
        let views = calls.map { ToolTrailKt.toolTrailView(call: $0) }
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(parts.enumerated()), id: \.offset) { _, part in
                        Text(chipText(part))
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .luviaGlass(in: Capsule(), tint: chipTint(part))
                    }
                }
            }
            ForEach(Array(views.enumerated()), id: \.offset) { index, view in
                let files: [DiffFileView] = KotlinLists.array(view.diffs as Any)
                ForEach(Array(files.enumerated()), id: \.offset) { _, file in
                    XcodeDiffCard(file: file, scheme: colorScheme)
                }
                if let meter = view.meter {
                    TestBar(meter: meter, markFailed: view.markFailed, names: KotlinLists.array(view.failedNames as Any))
                    let failed = max(0, Int(meter.total) - Int(meter.passed))
                    if failed > 0 && canAsk {
                        Button("交给 claude 修") {
                            onFix(calls[index].summary ?? calls[index].title)
                        }
                        .font(.caption)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chipText(_ part: ToolStreamPart) -> String {
        switch onEnum(of: part) {
        case .reads(let row):
            "读 ×\(row.count)"
        case .edit(let row):
            if row.hasDiff {
                "\(row.file) +\(row.additions) −\(row.deletions)"
            } else {
                row.file
            }
        case .meter(let row):
            "\(row.passed)/\(row.total)"
        case .run:
            "运行"
        case .other(let row):
            "工具 ×\(row.count)"
        }
    }

    private func chipTint(_ part: ToolStreamPart) -> Color {
        switch onEnum(of: part) {
        case .reads: .secondary
        case .edit: .blue
        case .meter, .run: .red
        case .other: .gray
        }
    }
}

private struct XcodeDiffCard: View {
    var file: DiffFileView
    var scheme: ColorScheme

    var body: some View {
        let lines: [DiffLineView] = KotlinLists.array(file.lines as Any)
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(file.path ?? "file")
                    .font(.custom(TerminalFont.postScriptName, size: 12))
                    .lineLimit(1)
                Spacer()
                Text("+\(file.additions)")
                    .foregroundStyle(Color.green)
                Text("−\(file.deletions)")
                    .foregroundStyle(Color.red)
            }
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            ForEach(Array(lines.prefix(40).enumerated()), id: \.offset) { _, line in
                HStack(spacing: 0) {
                    Text(gutter(line.oldNumber as Any?))
                        .frame(width: 32, alignment: .trailing)
                    Text(gutter(line.newNumber as Any?))
                        .frame(width: 32, alignment: .trailing)
                    Text(line.marker)
                        .frame(width: 16)
                        .foregroundStyle(markerColor(line.marker))
                    Text(line.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.custom(TerminalFont.postScriptName, size: 12))
                .foregroundStyle(Color.primary)
                .padding(.vertical, 1)
                .padding(.trailing, 8)
                .background(lineFill(line.marker))
            }
        }
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
    }

    private func gutter(_ value: Any?) -> String {
        guard let number = kotlinInt(value) else { return "" }
        return String(number)
    }

    private func markerColor(_ marker: String) -> Color {
        if marker == "+" { return .green }
        if marker == "-" { return .red }
        return .secondary
    }

    private func lineFill(_ marker: String) -> Color {
        let dark = scheme == .dark
        if marker == "+" { return Color.green.opacity(dark ? 0.28 : 0.16) }
        if marker == "-" { return Color.red.opacity(dark ? 0.28 : 0.16) }
        return .clear
    }
}

private struct TestBar: View {
    var meter: RunMeter
    var markFailed: Bool
    var names: [String]

    var body: some View {
        let mask = ToolTrailKt.testBarMask(passed: meter.passed, total: meter.total, markFailed: markFailed)
        VStack(alignment: .leading, spacing: 6) {
            Text("\(meter.passed)/\(meter.total)").font(.custom(TerminalFont.postScriptName, size: 12))
            HStack(spacing: 1) {
                ForEach(Array(mask.enumerated()), id: \.offset) { _, bit in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(String(bit) == "1" ? Color.red : Color.blue)
                        .frame(height: 14)
                        .frame(maxWidth: .infinity)
                }
            }
            ForEach(Array(names.enumerated()), id: \.offset) { _, name in
                Text(name)
                    .font(.custom(TerminalFont.postScriptName, size: 12))
                    .foregroundStyle(.red)
            }
        }
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
