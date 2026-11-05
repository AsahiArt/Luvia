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
    var nextAgent: String? = nil

    @Environment(\.colorScheme) private var colorScheme
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
                VStack(alignment: .leading, spacing: 1) {
                    Text(thread.title).font(.headline).lineLimit(1)
                    headerSubtitle.font(.caption).lineLimit(1)
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
            if showsChrome, thread.ask == nil {
                composer
            }
        }
    }

    private var headerSubtitle: Text {
        if hostOffline, let host = model.selectedHost {
            return Text("\(connectionLabel(host.connection)) · 缓存").foregroundColor(.secondary)
        }
        let agent = thread.agentType?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = agent?.isEmpty == false ? agent : nil
        if thread.ask != nil {
            var line = Text("")
            if let name {
                line = line + Text(name).foregroundColor(.secondary) + Text(" · ").foregroundColor(.secondary)
            }
            return line + Text("等你决定").foregroundColor(.orange)
        }
        if let name {
            return Text("\(name) · \(sessionLabel(thread.status))").foregroundColor(.secondary)
        }
        return Text(sessionLabel(thread.status)).foregroundColor(.secondary)
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

    /// Paths already drawn as tool chips. A second copy in agent text stays out of the bubble.
    private var trailOwnedDiffs: Set<String> {
        var owned: Set<String> = []
        for item in visibleItems {
            guard case .tool(let row) = onEnum(of: item) else { continue }
            let view = ToolTrailKt.toolTrailView(call: row.call)
            let files: [DiffFileView] = KotlinLists.array(view.diffs as Any)
            for file in files {
                if let path = file.path, !path.isEmpty { owned.insert(path) }
                let name = ToolTrailKt.diffFileTitle(path: file.path)
                if name != "file" { owned.insert(name) }
            }
        }
        return owned
    }

    @ViewBuilder
    private func outputBlocks(_ text: String) -> some View {
        let kept = OutputRunsKt.proseWithoutTrail(text: text)
        let parsed: [DiffFileView] = KotlinLists.array(ToolTrailKt.structuredDiffFiles(text: text) as Any)
        let owned = trailOwnedDiffs
        let fresh = parsed.filter { file in
            let name = ToolTrailKt.diffFileTitle(path: file.path)
            if name == "file" { return false }
            if owned.contains(name) { return false }
            if let path = file.path, owned.contains(path) { return false }
            return true
        }
        let prose = kept.trimmingCharacters(in: .whitespacesAndNewlines)
        if fresh.isEmpty && prose.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(fresh.enumerated()), id: \.offset) { _, file in
                    XcodeDiffCard(file: file, scheme: colorScheme)
                }
                if !prose.isEmpty {
                    let runs: [OutputRun] = KotlinLists.array(OutputRunsKt.outputRuns(text: prose) as Any)
                    if runs.isEmpty {
                        bubble(prose, mine: false)
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
            Text(bubbleText(text, mine: mine))
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

    private func bubbleText(_ text: String, mine: Bool) -> AttributedString {
        let source = mine ? text : OutputRunsKt.proseWithoutTrail(text: text)
        if source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return AttributedString("")
        }
        let spans: [ProseSpan] = KotlinLists.array(OutputRunsKt.proseSpans(text: source) as Any)
        if spans.isEmpty || spans.allSatisfy({ $0.kind != .code }) {
            return AttributedString(source)
        }
        var result = AttributedString()
        for span in spans {
            var piece = AttributedString(span.text)
            if span.kind == .code {
                piece.font = .system(.body, design: .monospaced).weight(.semibold)
                piece.backgroundColor = Color.primary.opacity(0.08)
            }
            result += piece
        }
        return result
    }

    private var canCompose: Bool {
        model.selectedHost?.isController == true && !hostOffline
    }

    private func decision(_ ask: Ask) -> some View {
        let options: [AskOption] = KotlinLists.array(ask.options as Any)
        let recommended = SessionListKt.recommendedOption(ask: ask)
        let peek = queueTotal > queueIndex
        let agent = thread.agentType?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (agent?.isEmpty == false ? agent! : thread.title)
        return ZStack(alignment: .top) {
            if peek {
                HStack(spacing: 8) {
                    Circle().fill(Color.orange).frame(width: 8, height: 8)
                    Text(peekLabel)
                        .font(.subheadline)
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 28)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .padding(.horizontal, 18)
                .allowsHitTesting(false)
            }
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    decisionAvatar()
                    Text("\(name) 需要你决定")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text("\(queueIndex)/\(queueTotal)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text(ask.question ?? "有一个决定在等你")
                    .font(.title2.weight(.bold))
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                    let picked = optionID(option) == (selectedOptionID ?? recommended.map(optionID))
                    let suggested = option.recommended || optionID(option) == recommended.map(optionID)
                    Button {
                        selectedOptionID = optionID(option)
                    } label: {
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 6) {
                                    Text(option.label).font(.headline)
                                    if suggested {
                                        Text("推荐")
                                            .font(.caption2.weight(.semibold))
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(Color.primary.opacity(0.06), in: Capsule())
                                    }
                                }
                                if let consequence = option.consequence, !consequence.isEmpty {
                                    Text(consequence).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 8)
                            Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                                .font(.title3)
                                .foregroundStyle(picked ? Color.blue : Color.secondary.opacity(0.45))
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
                HStack(alignment: .center, spacing: 10) {
                    TextField("补充说明（可选）", text: $decisionNote, axis: .vertical)
                        .lineLimit(1...3)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .luviaGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                        .disabled(!canCompose)
                    Button("继续") {
                        let choice = options.first { optionID($0) == (selectedOptionID ?? recommended.map(optionID)) }
                        if let choice {
                            confirm(choice)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .tint(.blue)
                    .disabled(!canCompose)
                }
            }
            .padding(16)
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(Color.primary.opacity(0.06), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.08), radius: 16, y: 6)
            .padding(.top, peek ? 32 : 0)
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
    }

    private var peekLabel: String {
        let index = "\(queueIndex + 1)/\(queueTotal)"
        let agent = nextAgent?.trimmingCharacters(in: .whitespacesAndNewlines)
        let question = nextQuestion?.trimmingCharacters(in: .whitespacesAndNewlines)
        var parts = [index]
        if let agent, !agent.isEmpty { parts.append(agent) }
        if let question, !question.isEmpty {
            parts.append(question)
        } else if agent == nil || agent?.isEmpty != false {
            parts.append("还有一个决定")
        }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private func decisionAvatar() -> some View {
        let logo = SessionListKt.agentBrandLogo(agentType: thread.agentType, title: thread.title)
        if let logo, let name = decisionAssetName(logo.assetId) {
            let diameter: CGFloat = 22
            let glyph = diameter * CGFloat(logo.inset)
            if logo.preservesColor {
                Image(name)
                    .resizable()
                    .renderingMode(.original)
                    .scaledToFit()
                    .frame(width: glyph, height: glyph)
                    .frame(width: diameter, height: diameter)
                    .clipShape(Circle())
                    .accessibilityLabel(decisionAssetLabel(logo.assetId))
            } else {
                ZStack {
                    Circle().fill(Color(hex: logo.backgroundHex))
                    Image(name)
                        .resizable()
                        .renderingMode(.template)
                        .scaledToFit()
                        .foregroundStyle(Color(hex: logo.foregroundHex))
                        .frame(width: glyph, height: glyph)
                }
                .frame(width: diameter, height: diameter)
                .accessibilityLabel(decisionAssetLabel(logo.assetId))
            }
        } else {
            Image(systemName: "questionmark")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Color.blue, in: Circle())
        }
    }

    private func decisionAssetName(_ assetId: String) -> String? {
        switch assetId {
        case "claude": "agent-logo-claude"
        case "openai": "agent-logo-openai"
        case "lobe-grok": "agent-logo-lobe-grok"
        case "pi": "agent-logo-pi"
        default: nil
        }
    }

    private func decisionAssetLabel(_ assetId: String) -> String {
        switch assetId {
        case "claude": "Claude"
        case "openai": "OpenAI"
        case "lobe-grok": "Grok"
        case "pi": "Pi"
        default: assetId
        }
    }

    private func confirm(_ choice: AskOption) {
        #if DEBUG
        if PlaytestChatKt.isPlaytestThread(id: thread.id) {
            decisionNote = ""
            return
        }
        #endif
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
        #if DEBUG
        if PlaytestChatKt.isPlaytestThread(id: thread.id) { return }
        #endif
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
    /// Empty means every diff card is open. Tapping its file chip inserts the basename.
    @State private var collapsed: Set<String> = []

    var body: some View {
        let parts: [ToolStreamPart] = KotlinLists.array(ToolTrailKt.toolStreamParts(calls: calls) as Any)
        let views = calls.map { ToolTrailKt.toolTrailView(call: $0) }
        VStack(alignment: .leading, spacing: 8) {
            if !parts.isEmpty {
                TrailChipRow(spacing: 8) {
                    ForEach(Array(parts.enumerated()), id: \.offset) { _, part in
                        chip(part)
                            .yieldsForTestChip(chipYields(part))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            ForEach(Array(views.enumerated()), id: \.offset) { index, view in
                let files: [DiffFileView] = KotlinLists.array(view.diffs as Any)
                ForEach(Array(files.enumerated()), id: \.offset) { _, file in
                    let name = ToolTrailKt.diffFileTitle(path: file.path)
                    if name != "file" && !collapsed.contains(name) {
                        XcodeDiffCard(file: file, scheme: colorScheme)
                    }
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

    /// The file chip is the only one that gives up width. The test fraction stays whole.
    private func chipYields(_ part: ToolStreamPart) -> Bool {
        if case .edit = onEnum(of: part) {
            return true
        }
        return false
    }

    @ViewBuilder
    private func chip(_ part: ToolStreamPart) -> some View {
        switch onEnum(of: part) {
        case .reads(let row):
            chipChrome(Color.primary.opacity(0.06)) {
                Image(systemName: "doc")
                Text("读 ×\(row.count)")
                    .fixedSize(horizontal: true, vertical: false)
            }
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityLabel("读 \(row.count)")
        case .edit(let row):
            let label = chipChrome(Color.blue.opacity(0.12)) {
                Image(systemName: "pencil")
                    .foregroundStyle(.blue)
                    .fixedSize()
                fileChipName(row.file)
                Text("+\(row.additions)")
                    .foregroundStyle(Color.green)
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(1)
                Text("−\(row.deletions)")
                    .foregroundStyle(Color.red)
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(1)
            }
            .frame(minWidth: 0, alignment: .leading)
            .accessibilityLabel(ToolTrailKt.editChipLabel(file: row.file, additions: row.additions, deletions: row.deletions) ?? row.file)
            if row.hasDiff {
                label
                    .contentShape(Capsule())
                    .onTapGesture {
                        if collapsed.contains(row.file) {
                            collapsed.remove(row.file)
                        } else {
                            collapsed.insert(row.file)
                        }
                    }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityHint(collapsed.contains(row.file) ? "展开改动" : "收起改动")
            } else {
                label
            }
        case .meter(let row):
            let label = ToolTrailKt.meterChipLabel(passed: row.passed, total: row.total)
            chipChrome(Color.red.opacity(0.12)) {
                Image(systemName: "exclamationmark.circle.fill")
                    .foregroundStyle(.red)
                    .fixedSize()
                Text(label)
                    .foregroundStyle(.red)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(1)
            }
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityLabel(label)
        case .run(let row):
            chipChrome(Color.red.opacity(0.10)) {
                Image(systemName: "play.fill")
                    .font(.caption2)
                    .foregroundStyle(.red)
                Text(row.title.isEmpty ? "运行" : row.title)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
            .fixedSize(horizontal: true, vertical: false)
        case .other(let row):
            chipChrome(Color.primary.opacity(0.06)) {
                Image(systemName: "wrench")
                Text("工具 ×\(row.count)")
                    .fixedSize(horizontal: true, vertical: false)
            }
            .fixedSize(horizontal: true, vertical: false)
        }
    }

    /// Full basename, then no extension, then an abbreviated stem. The last copy may ellipsize.
    private func fileChipName(_ file: String) -> some View {
        ViewThatFits(in: .horizontal) {
            Text(ToolTrailKt.editChipFileName(file: file, tighten: Int32(0)))
                .fixedSize(horizontal: true, vertical: false)
            Text(ToolTrailKt.editChipFileName(file: file, tighten: Int32(1)))
                .fixedSize(horizontal: true, vertical: false)
            Text(ToolTrailKt.editChipFileName(file: file, tighten: Int32(2)))
                .fixedSize(horizontal: true, vertical: false)
            Text(ToolTrailKt.editChipFileName(file: file, tighten: Int32(2)))
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .foregroundStyle(.blue)
        .frame(minWidth: 0, alignment: .leading)
        .layoutPriority(-1)
    }

    private func chipChrome<Content: View>(_ fill: Color, @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 4) {
            content()
        }
        .font(.subheadline.weight(.semibold))
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(fill, in: Capsule())
    }
}

private struct ChipYieldsKey: LayoutValueKey {
    static let defaultValue = false
}

extension View {
    /// File chips report true so a tight row shortens them before the test chip.
    fileprivate func yieldsForTestChip(_ yields: Bool) -> some View {
        layoutValue(key: ChipYieldsKey.self, value: yields)
    }
}

/// Keeps read and test chips at their full width. Only a file chip is narrowed.
private struct TrailChipRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let ideals = idealSizes(subviews)
        let height = ideals.map(\.height).max() ?? 0
        let idealWidth = ideals.map(\.width).reduce(0, +) + gapWidth(subviews.count)
        let width = proposal.width.map { min(idealWidth, $0) } ?? idealWidth
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let ideals = idealSizes(subviews)
        let flex = subviews.indices.filter { subviews[$0][ChipYieldsKey.self] }
        let fixedWidth = subviews.indices.reduce(CGFloat(0)) { partial, index in
            subviews[index][ChipYieldsKey.self] ? partial : partial + ideals[index].width
        }
        let flexIdeal = flex.reduce(CGFloat(0)) { $0 + ideals[$1].width }
        let budget = max(0, bounds.width - fixedWidth - gapWidth(subviews.count))
        var widths = ideals.map(\.width)
        if !flex.isEmpty, flexIdeal > budget {
            var remaining = budget
            for (offset, index) in flex.enumerated() {
                if offset == flex.count - 1 {
                    widths[index] = max(0, remaining)
                } else {
                    let share = ideals[index].width / flexIdeal * budget
                    let width = min(max(0, share), remaining)
                    widths[index] = width
                    remaining -= width
                }
            }
        }
        var x = bounds.minX
        for index in subviews.indices {
            let width = widths[index]
            subviews[index].place(
                at: CGPoint(x: x, y: bounds.midY),
                anchor: .leading,
                proposal: ProposedViewSize(width: width, height: ideals[index].height)
            )
            x += width + spacing
        }
    }

    private func idealSizes(_ subviews: Subviews) -> [CGSize] {
        subviews.map { $0.sizeThatFits(.unspecified) }
    }

    private func gapWidth(_ count: Int) -> CGFloat {
        spacing * CGFloat(max(count - 1, 0))
    }
}

private struct CardWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct XcodeDiffCard: View {
    var file: DiffFileView
    var scheme: ColorScheme
    @State private var cardWidth: CGFloat = 0

    var body: some View {
        let lines: [DiffLineView] = KotlinLists.array(file.lines as Any)
        let module = ToolTrailKt.diffModulePath(path: file.path)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(ToolTrailKt.diffFileTitle(path: file.path))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer(minLength: 8)
                if let module, !module.isEmpty {
                    Text(module)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.prefix(40).enumerated()), id: \.offset) { _, line in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(gutter(line))
                                .foregroundStyle(.secondary)
                                .frame(width: 36, alignment: .trailing)
                            highlighted(line.text)
                        }
                        .font(.custom(TerminalFont.postScriptName, size: 12))
                        .padding(.vertical, 1)
                        .padding(.trailing, 10)
                        .frame(minWidth: cardWidth, alignment: .leading)
                        .background(lineFill(line.marker))
                    }
                }
                .padding(.vertical, 4)
            }
            .background {
                GeometryReader { geo in
                    Color.clear.preference(key: CardWidthKey.self, value: geo.size.width)
                }
            }
            .onPreferenceChange(CardWidthKey.self) { cardWidth = $0 }
        }
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
    }

    private func gutter(_ line: DiffLineView) -> String {
        if let number = kotlinInt(line.newNumber as Any?) { return String(number) }
        if let number = kotlinInt(line.oldNumber as Any?) { return String(number) }
        return ""
    }

    private func highlighted(_ text: String) -> Text {
        let tokens: [CodeToken] = KotlinLists.array(ToolTrailKt.highlightCode(text: text) as Any)
        guard let first = tokens.first else {
            return Text(text).foregroundColor(.primary)
        }
        var combined = Text(first.text).foregroundColor(tokenColor(first.kind))
        for token in tokens.dropFirst() {
            combined = combined + Text(token.text).foregroundColor(tokenColor(token.kind))
        }
        return combined
    }

    private func tokenColor(_ kind: CodeTokenKind) -> Color {
        let dark = scheme == .dark
        switch kind {
        case .keyword:
            return dark
                ? Color(red: 0.988, green: 0.416, blue: 0.635)
                : Color(red: 0.608, green: 0.137, blue: 0.576)
        case .type:
            return dark
                ? Color(red: 0.404, green: 0.847, blue: 1.0)
                : Color(red: 0.035, green: 0.216, blue: 0.388)
        case .number:
            return dark
                ? Color(red: 0.839, green: 0.643, blue: 0.424)
                : Color(red: 0.110, green: 0.0, blue: 0.812)
        case .string:
            return dark
                ? Color(red: 0.988, green: 0.416, blue: 0.365)
                : Color(red: 0.769, green: 0.102, blue: 0.086)
        case .comment:
            return dark
                ? Color(red: 0.424, green: 0.475, blue: 0.529)
                : Color(red: 0.0, green: 0.455, blue: 0.0)
        default:
            return .primary
        }
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
