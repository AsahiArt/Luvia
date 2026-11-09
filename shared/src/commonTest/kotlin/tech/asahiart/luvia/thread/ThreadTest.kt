package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpPermissionKind
import tech.asahiart.luvia.AcpPermissionOption
import tech.asahiart.luvia.AcpPermissionRequest
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AcpSessionInfo
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpStopReason
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AcpTranscriptRole
import tech.asahiart.luvia.AgentDetailState
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentReadResult
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.ConnectionFreshness
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.StoredPanePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadTest {
    private fun hasTuiBlock(text: String?): Boolean =
        text?.any { it.code in 0x2580..0x259F } == true

    private val piStatusBar =
        "↑248k ↓59k R11M CH99.6% " + "$" + "12.824 … grok-4.7-build-fast * high"

    private fun pane(id: String, status: AgentStatus, agent: String = "claude") =
        AgentSummary(paneId = id, name = null, status = status, agent = agent, workspaceId = "w1", workspaceName = "luvus")

    private fun acp(run: AcpRunState, permission: AcpPermissionRequest? = null) =
        AcpState(
            open = true,
            info = AcpSessionInfo("s1", "codex", "Codex", 1, "/src/luvia"),
            run = run,
            permission = permission,
            transcript = listOf(
                AcpTranscriptItem.Message("m1", AcpTranscriptRole.User, "hi", false),
                AcpTranscriptItem.Message("m2", AcpTranscriptRole.Thought, "hmm", false),
                AcpTranscriptItem.Message("m3", AcpTranscriptRole.Agent, "done\n", false),
                AcpTranscriptItem.Turn("t1", AcpStopReason.entries.first()),
            ),
        )

    @Test
    fun blockedPaneAsksWithCachedQuestion() {
        val state = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            agentDetail = AgentDetailState(paneId = "p1", transcript = AgentReadResult("p1", "work\n\u001B[1mRun migration? (y/n)\u001B[0m\n\n", null)),
        )
        val thread = state.threads("h").single()
        assertEquals("luvus", thread.projectLabel)
        assertEquals("Run migration? (y/n)", thread.ask?.question)
        assertEquals(listOf("Yes", "No"), thread.ask?.options?.map { it.label })
        assertEquals(AskAction.Prompt("y"), thread.ask?.options?.first()?.action)
        assertTrue(thread.ask?.options?.none { it.recommended } == true)
        assertTrue(thread.commands().any { it.name == "/compact" })
    }

    @Test
    fun blockedPaneWithoutTranscriptAsksToConfirm() {
        val thread = HostUhpState(agents = listOf(pane("p1", AgentStatus.Blocked))).threads("h").single()
        assertEquals(DECISION_QUESTION_FALLBACK, thread.ask?.question)
        assertEquals(DECISION_QUESTION_FALLBACK, listTrail(thread, null).label)
        assertNull(HostUhpState(agents = listOf(pane("p2", AgentStatus.Working))).threads("h").single().ask)
    }

    @Test
    fun decisionTextUsesPermissionTitle() {
        val request = AcpPermissionRequest(
            "r1", "Run command", null, null, null,
            listOf(AcpPermissionOption("a", "Allow once", AcpPermissionKind.AllowOnce)),
        )
        val state = acp(AcpRunState.AwaitingPermission, request).copy(
            transcript = listOf(
                AcpTranscriptItem.Message("m", AcpTranscriptRole.Agent, piStatusBar, false),
            ),
        )
        val thread = HostUhpState(acp = state).threads("h").single()
        assertEquals("Run command", thread.ask?.question)
        assertEquals("Run command", listTrail(thread, null).label)
        assertEquals(piStatusBar, thread.summary)
    }

    @Test
    fun decisionTextUsesAgentQuestionNotStatusBar() {
        val question = "要把 TokenStore 拆成两个 actual 吗？"
        val transcript = "先看代码。\n$question\n~/src/luvia (main)\n$piStatusBar\n"
        val state = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        )
        val thread = state.threads("h").single()
        assertEquals(question, thread.ask?.question)
        assertEquals(question, listTrail(thread, null).label)
        assertEquals(piStatusBar, thread.summary)
    }

    @Test
    fun decisionTextStripsLeadingPromptMarkers() {
        val question = "要把 TokenStore 拆成两个 actual 并补齐 iOS 深色帧吗？"
        val transcript = "› $question\n~/src/luvia (main)\n$piStatusBar\n"
        assertEquals(question, decisionQuestionText(null, transcript))
        assertEquals(
            "Run migration now?",
            decisionQuestionText(null, "? Run migration now?\n$piStatusBar\n"),
        )
        assertEquals(
            "Allow editing build.gradle.kts?",
            decisionQuestionText(null, "❯ Allow editing build.gradle.kts?\n$piStatusBar\n"),
        )
    }

    @Test
    fun decisionTextSkipsBareQuestionMarkLines() {
        val question = "继续跑 assembleDebug 吗？"
        val transcript = "$question\n?\n？\n? gradle\n~/src/luvia (main)\n$piStatusBar\n"
        assertEquals(question, decisionQuestionText(null, transcript))
        assertEquals(
            DECISION_QUESTION_FALLBACK,
            decisionQuestionText(null, "?\n？\n? gradle\n$piStatusBar\n"),
        )
    }

    @Test
    fun decisionTextSkipsClaudeOptionLines() {
        val question = "Claude wants to edit shared/src/commonMain/kotlin/tech/asahiart/luvia/thread/AgentThread.kt. Allow?"
        val transcript = """
            |Looking at AgentThread.kt…
            |$question
            |
            |❯ 1. Yes
            |  2. No
            |  3. Tell Claude what to do differently
            |~/src/luvia (main)
            |$piStatusBar
            |""".trimMargin()
        val state = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        )
        val thread = state.threads("h").single()
        assertEquals(question, thread.ask?.question)
        assertEquals(question, listTrail(thread, null).label)
        assertEquals(question, decisionQuestionText(null, transcript))
    }

    @Test
    fun decisionTextSkipsCodexOptionLines() {
        val question = "Run ./gradlew :shared:jvmTest?"
        val transcript = """
            |$question
            |❯ 1. Allow once
            |  2. Allow always
            |  3. Deny
            |1. Allow
            |$piStatusBar
            |""".trimMargin()
        assertEquals(question, decisionQuestionText(null, transcript))
    }

    @Test
    fun grokAskJoinsWrappedQuestionAndParsesOptions() {
        val transcript = """
            |  ┃
            |  ┃  是否允许在这个仅用于终端帧探测的 /tmp 沙箱里创建仅含一行 probe 的 term-
            |  ┃  probe.txt，且不会影响系统其他位置？
            |  ┃
            |  ┃
            |  ┃  1 (○) 允许 (Recommended)    允许在该 /tmp 沙箱中创建仅含一行 probe 的 term
            |  ┃                            probe.txt，不改动系统其他位置。
            |  ┃  2 (○) 不允许                 不创建 term-probe.txt，也不对沙箱或系统其他位
            |  ┃  z (○) Type your answer here
            |  ┃
            |  ┃  ↑/↓ navigate · y copy                                       Enter:submit
            |  ┃
            |  Tab:next answer  │  Esc:scrollback  │  Shift+x:dismiss
            |""".trimMargin()
        val question = "是否允许在这个仅用于终端帧探测的 /tmp 沙箱里创建仅含一行 probe 的 term-probe.txt，且不会影响系统其他位置？"
        assertEquals(question, decisionQuestionText(null, transcript))
        assertTrue(question.contains("term-probe.txt"))
        assertTrue(question.contains("其他位置？"))
        assertTrue(question.length > 40)
        assertFalse(question.contains("┃"))
        assertFalse(question.contains("│"))
        val state = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        )
        val ask = state.threads("h").single().ask!!
        assertEquals(question, ask.question)
        assertEquals(listOf("允许", "不允许"), ask.options.map { it.label })
        assertEquals(AskAction.Keys(listOf(AgentKey.ENTER)), ask.options[0].action)
        assertEquals(AskAction.Keys(listOf(AgentKey.DOWN, AgentKey.ENTER)), ask.options[1].action)
        assertTrue(ask.options[0].recommended)
        assertTrue(!ask.options[0].consequence.isNullOrBlank())
        assertTrue(ask.options[0].consequence!!.contains("probe.txt"))
        assertFalse(ask.options[1].recommended)
        assertEquals(AskTone.Primary, ask.options[0].tone)
        assertEquals(AskTone.Destructive, ask.options[1].tone)
        val banned = listOf("Yes", "No", "Enter", "Esc", "z", "Tab", "submit", "scrollback", "dismiss", "navigate", "copy")
        assertTrue(ask.options.none { opt -> banned.any { opt.label.contains(it, ignoreCase = true) } })
        assertTrue(ask.options.none { it.label.contains("Shift") })
    }

    @Test
    fun grokAskStripsBlockElementsFromOptions() {
        val transcript = """
            |  ┃
            |  ┃  是否允许在这个仅用于终端帧探测的 /tmp 沙箱里创建仅含一行 probe 的 term-
            |  ┃  probe.txt，且不会影响系统其他位置？
            |  ┃
            |  ┃  1 (○) 允许 (Recommended)    允许在该 /tmp 沙箱中创建仅含一行 probe 的 term
            |  ┃                            probe.txt，不改动系统其他位置。█
            |  ┃  2 (○) 不允许                 不创建 term-probe.txt█
            |  ┃  z (○) Type your answer here
            |""".trimMargin()
        val question = "是否允许在这个仅用于终端帧探测的 /tmp 沙箱里创建仅含一行 probe 的 term-probe.txt，且不会影响系统其他位置？"
        val ask = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        ).threads("h").single().ask!!
        assertEquals(question, ask.question)
        assertEquals(question, decisionQuestionText(null, transcript))
        assertFalse(ask.question!!.contains("┃"))
        assertFalse(ask.question!!.contains("│"))
        assertFalse(hasTuiBlock(ask.question))
        assertEquals(listOf("允许", "不允许"), ask.options.map { it.label })
        assertEquals("不创建 term-probe.txt", ask.options[1].consequence)
        assertTrue(ask.options[0].consequence!!.contains("term probe.txt"))
        assertTrue(ask.options[0].consequence!!.contains("其他位置。"))
        assertTrue(ask.options.all { opt ->
            !opt.label.contains("█") &&
                opt.consequence?.contains("█") != true &&
                !hasTuiBlock(opt.label) &&
                !hasTuiBlock(opt.consequence)
        })
    }

    @Test
    fun askOptionsStayInsideTheCurrentBlock() {
        val transcript = """
            |要删掉缓存吗？
            |1 (○) Keep
            |2 (○) Archive
            |3 (○) Wipe
            |已选择 Keep。
            |
            |是否继续跑测试？
            |1 (○) 允许 (Recommended)    只跑当前模块
            |2 (○) 不允许                 先停下来
            |""".trimMargin()
        val ask = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        ).threads("h").single().ask!!
        assertEquals("是否继续跑测试？", ask.question)
        assertEquals(listOf("允许", "不允许"), ask.options.map { it.label })
        assertEquals(
            listOf(
                AskAction.Keys(listOf(AgentKey.ENTER)),
                AskAction.Keys(listOf(AgentKey.DOWN, AgentKey.ENTER)),
            ),
            ask.options.map { it.action },
        )
        assertEquals(2, ask.options.size)
        assertTrue(ask.options.none { it.label in setOf("Keep", "Archive", "Wipe") })
    }

    @Test
    fun currentClaudeAskIgnoresOlderGrokOptions() {
        val transcript = """
            |要删掉缓存吗？
            |1 (○) 保留
            |2 (○) 覆盖
            |3 (○) 稍后
            |已拒绝。
            |
            |Run ./gradlew :shared:jvmTest?
            |❯ 1. Yes
            |  2. No
            |""".trimMargin()
        val ask = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        ).threads("h").single().ask!!
        assertEquals("Run ./gradlew :shared:jvmTest?", ask.question)
        assertEquals(listOf("Yes", "No"), ask.options.map { it.label })
        assertEquals(AskAction.Keys(listOf(AgentKey.ENTER)), ask.options[0].action)
        assertEquals(AskAction.Keys(listOf(AgentKey.DOWN, AgentKey.ENTER)), ask.options[1].action)
        assertTrue(ask.options.none { it.label in setOf("保留", "覆盖", "稍后") })
    }

    @Test
    fun tuiChromeKeepsInteriorPipesAndDropsBlocks() {
        val transcript = """
            |Ship a | b now?
            |1 (○) 允许    keep a | b█
            |2 (○) 不允许    不创建
            |""".trimMargin()
        val ask = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(transcript, AgentStatus.Blocked)),
        ).threads("h").single().ask!!
        assertEquals("Ship a | b now?", ask.question)
        assertTrue(ask.question!!.contains("a | b"))
        assertEquals("keep a | b", ask.options[0].consequence)
        assertFalse(hasTuiBlock(ask.options[0].consequence))
        assertFalse(ask.options[0].consequence!!.contains("█"))
    }

    @Test
    fun decisionTextDoesNotTunnelThroughUnrelatedProse() {
        val transcript = """
            |要把 TokenStore 拆成两个 actual 吗？
            |我已经改完了，等编译结果。
            |?
            |? gradle
            |1. Allow
            |$piStatusBar
            |""".trimMargin()
        assertEquals(DECISION_QUESTION_FALLBACK, decisionQuestionText(null, transcript))
    }

    @Test
    fun decisionTextFallsBackWhenOnlyStatusBar() {
        val state = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            panePreviews = mapOf("p1" to StoredPanePreview(piStatusBar, AgentStatus.Blocked)),
        )
        val thread = state.threads("h").single()
        assertEquals(DECISION_QUESTION_FALLBACK, thread.ask?.question)
        assertEquals(DECISION_QUESTION_FALLBACK, listTrail(thread, null).label)
        assertEquals(piStatusBar, thread.summary)
    }

    @Test
    fun acpPermissionMapsToAsk() {
        val request = AcpPermissionRequest(
            "r1", "Write build.gradle.kts", null, null, null,
            listOf(
                AcpPermissionOption("a", "Allow once", AcpPermissionKind.AllowOnce),
                AcpPermissionOption("b", "Reject", AcpPermissionKind.RejectOnce),
            ),
        )
        val thread = HostUhpState(acp = acp(AcpRunState.AwaitingPermission, request)).threads("h").single()
        assertEquals(AgentKind.Acp, thread.kind)
        assertEquals(AgentStatus.Blocked, thread.status)
        assertEquals("luvia", thread.projectLabel)
        assertEquals("done", thread.summary)
        assertEquals(AskAction.AcpOption("r1", "b"), thread.ask!!.options[1].action)
        assertEquals(AskTone.Destructive, thread.ask!!.options[1].tone)
    }

    @Test
    fun acpTimelineMapsItems() {
        val state = acp(AcpRunState.Ready)
        val items = state.timeline(null)
        assertIs<TimelineItem.Mine>(items[0])
        assertIs<TimelineItem.Thought>(items[1])
        assertIs<TimelineItem.Output>(items[2])
        assertIs<TimelineItem.Status>(items[3])
        assertIs<TimelineItem.AskCard>(state.timeline(paneAsk(null)).last())
    }

    @Test
    fun transcriptChunksOnlyNewLines() {
        var t = PaneTimeline().ingestTranscript("a\nb\n")
        t = t.ingestTranscript("a\nb\nc\n")
        t = t.ingestTranscript("b\nc\nd\ne")
        t = t.ingestTranscript("b\nc\nd\ne\n\n")
        assertEquals(listOf("a\nb", "c", "d\ne"), t.items.map { (it as TimelineItem.Output).text })
    }

    @Test
    fun statusAndMineAndUnconfirmed() {
        var t = PaneTimeline().recordStatus(AgentStatus.Working).recordStatus(AgentStatus.Working)
        assertTrue(t.items.isEmpty())
        t = t.recordMine("go").recordStatus(AgentStatus.Idle).markLastUnconfirmed()
        assertEquals(TimelineItem.Unconfirmed("p1", "go"), t.items[0])
        assertEquals(AgentStatus.Idle, (t.items[1] as TimelineItem.Status).status)
    }

    @Test
    fun transcriptSplitsProseToolsAndCost() {
        val raw =
            "Session title auto · grok\n" +
                "10:43 PM\n" +
                "用户同意清理缓存。\n" +
                "这一段换行\n" +
                "还在同一段。\n" +
                "\n" +
                "% read\n" +
                "  ~/.local/share/chezmoi → 22 lines\n" +
                "• " + "$" + ": tmp=mktemp\n" +
                "EXIT; curl → done · 23.4s\n" +
                "↑75k " + "$" + "0.279\n" +
                "准备开始执行清理。\n"
        val items = PaneTimeline().ingestTranscript(raw).items
        assertIs<TimelineItem.Status>(items[0])
        assertEquals("Session title auto · grok · 10:43 PM", (items[0] as TimelineItem.Status).label)
        val prose = items[1] as TimelineItem.Output
        assertTrue(prose.text.contains("用户同意清理缓存。"))
        assertTrue(prose.text.contains("这一段换行\n还在同一段。"))
        val read = items[2] as TimelineItem.Tool
        assertEquals("read", read.call.kind)
        assertEquals("% read", read.call.title)
        assertTrue(read.call.summary!!.contains("chezmoi"))
        val shell = items[3] as TimelineItem.Tool
        assertEquals("bash", shell.call.kind)
        assertTrue(shell.call.summary!!.contains("EXIT"))
        val cost = items[4] as TimelineItem.Status
        assertEquals("↑75k \$0.279", cost.label)
        assertEquals("准备开始执行清理。", (items[5] as TimelineItem.Output).text)
        assertEquals(6, items.size)
    }

    @Test
    fun labeledToolsAndDiffsLeaveProse() {
        val raw =
            "先看现有代码。\n" +
                "Read the notes before editing.\n" +
                "Cursor shell: git status\n" +
                "  On branch main\n" +
                "Read shared/src/Pairing.kt\n" +
                "  22 lines\n" +
                "diff --git a/shared/commonMain/ReconnectPolicy.kt b/shared/commonMain/ReconnectPolicy.kt\n" +
                "--- a/shared/commonMain/ReconnectPolicy.kt\n" +
                "+++ b/shared/commonMain/ReconnectPolicy.kt\n" +
                "@@ -12,2 +12,3 @@\n" +
                " class ReconnectPolicy(\n" +
                "-    fun retry()\n" +
                "+    fun reconnect()\n" +
                "+    fun backoff()\n" +
                "准备跑测试。\n"
        val items = PaneTimeline().ingestTranscript(raw).items
        val prose = items[0] as TimelineItem.Output
        assertTrue(prose.text.contains("先看现有代码"))
        assertTrue(prose.text.contains("Read the notes"))
        val shell = items[1] as TimelineItem.Tool
        assertEquals("bash", shell.call.kind)
        assertTrue(shell.call.title.contains("Cursor shell"))
        assertTrue(shell.call.summary!!.contains("On branch main"))
        val read = items[2] as TimelineItem.Tool
        assertEquals("read", read.call.kind)
        val edit = items[3] as TimelineItem.Tool
        assertEquals("edit", edit.call.kind)
        val file = toolTrailView(edit.call).diffs.single()
        assertEquals("shared/commonMain/ReconnectPolicy.kt", file.path)
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
        assertEquals(13, file.lines.first { it.marker == "-" }.oldNumber)
        assertEquals("准备跑测试。", (items[4] as TimelineItem.Output).text)
    }

    @Test
    fun percentDollarShellLeavesTheProseBubble() {
        val raw =
            "先提交。\n" +
                "% \$: git add README.org android/apk-device-install/SKILL.md && git commit → done\n" +
                "已提交。\n"
        val items = PaneTimeline().ingestTranscript(raw).items
        val prose = items.filterIsInstance<TimelineItem.Output>().joinToString("\n") { it.text }
        assertTrue(!prose.contains("% \$:"))
        assertTrue(!prose.contains("git add"))
        assertTrue(prose.contains("先提交。"))
        assertTrue(prose.contains("已提交。"))
        val tool = items.filterIsInstance<TimelineItem.Tool>().single()
        assertEquals("bash", tool.call.kind)
        assertEquals("git", runChipLabel(tool.call.title, tool.call.summary))
        assertTrue(runChipLabel(tool.call.title, tool.call.summary).length <= RUN_CHIP_LIMIT)
    }

    @Test
    fun transcriptKeepsNerdGlyphs() {
        val t = PaneTimeline().ingestTranscript("\u001B[32m\uE0A0 main\u001B[0m \u25CF ok\n")
        assertEquals("\uE0A0 main \u25CF ok", (t.items.single() as TimelineItem.Output).text)
    }

    @Test
    fun rejectedSendDropsMine() {
        val t = PaneTimeline().recordMine("a").recordMine("b").dropLastMine()
        assertEquals(listOf("a"), t.items.map { (it as TimelineItem.Mine).text })
    }

    @Test
    fun unreadChatCountIgnoresRowsAlreadyOnScreen() {
        val seen = setOf("a", "b")
        assertEquals(0, unreadChatCount(seen, listOf("a", "b")))
        assertEquals(1, unreadChatCount(seen, listOf("a", "b", "c")))
        assertEquals(2, unreadChatCount(seen, listOf("c", "d")))
        assertEquals(0, unreadChatCount(seen, emptyList()))
        assertEquals(3, unreadChatCount(emptySet(), listOf("a", "b", "c")))
    }

    @Test
    fun timelineIsBounded() {
        var t = PaneTimeline()
        repeat(PaneTimeline.MAX_ITEMS + 20) { t = t.recordMine("m$it") }
        assertEquals(PaneTimeline.MAX_ITEMS, t.items.size)
        assertEquals("m20", (t.items.first() as TimelineItem.Mine).text)
    }

    @Test
    fun nowGroupsByHostAndExpiresDone() {
        val a = NowHost("a", "studio", ConnectionFreshness.Live, false,
            HostUhpState(agents = listOf(pane("p1", AgentStatus.Blocked), pane("p2", AgentStatus.Working), pane("p3", AgentStatus.Done))))
        val b = NowHost("b", "nas", ConnectionFreshness.Stale, true,
            HostUhpState(agents = listOf(pane("q1", AgentStatus.Blocked))))
        val threads = listOf(a, b).flatMap { it.state!!.threads(it.hostId) }
        var ledger = DoneLedger().observe(threads, nowMs = 0)

        val now = buildNowState(listOf(b, a), ledger, nowMs = 1)
        assertEquals(listOf("a", "b"), now.needsYou.map { it.hostId })
        assertEquals(2, now.needsYouCount)
        assertEquals(listOf("p2"), now.inProgress.single().threads.map { it.id })
        assertEquals(listOf("p3"), now.recentlyDone.single().threads.map { it.id })

        assertTrue(buildNowState(listOf(a), ledger, DoneLedger.DAY_MS).recentlyDone.isEmpty())
        ledger = ledger.markViewed(threads.first { it.id == "p3" })
        assertTrue(buildNowState(listOf(a), ledger, 1).recentlyDone.isEmpty())
    }
}
