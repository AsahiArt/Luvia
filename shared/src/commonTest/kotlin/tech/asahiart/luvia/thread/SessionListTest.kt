package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpSessionInfo
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AgentDetailState
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.HostUhpState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionListTest {
    @Test
    fun filtersAndCountsPendingReplies() {
        val threads = listOf(
            thread("a", AgentStatus.Blocked, ask = Ask("go?", emptyList())),
            thread("b", AgentStatus.Working),
            thread("c", AgentStatus.Idle),
            thread("d", AgentStatus.Done),
        )
        assertEquals(listOf("a"), filterSessions(threads, SessionFilter.NeedsYou).map { it.id })
        assertEquals(listOf("b"), filterSessions(threads, SessionFilter.Running).map { it.id })
        assertEquals(listOf("c", "d"), filterSessions(threads, SessionFilter.Idle).map { it.id })
        assertEquals(4, filterSessions(threads, SessionFilter.All).size)
        assertEquals(1, pendingReplyCount(threads))
    }

    @Test
    fun sortPutsDecisionsBeforeRunningWork() {
        val threads = listOf(
            thread("idle", AgentStatus.Idle, updated = 50),
            thread("run", AgentStatus.Working, updated = 10),
            thread("ask", AgentStatus.Blocked, ask = Ask(null, emptyList()), updated = 1),
            thread("done", AgentStatus.Done, updated = 99),
        )
        assertEquals(listOf("ask", "run", "idle", "done"), sortSessions(threads).map { it.id })
    }

    @Test
    fun toolKindMatchesWordsNotSubstrings() {
        assertEquals(ToolTrailKind.Read, toolTrailKind("read", "Cargo.toml"))
        assertEquals(ToolTrailKind.Edit, toolTrailKind(null, "Edit ReconnectPolicy"))
        assertEquals(ToolTrailKind.Run, toolTrailKind("execute", "gradle test"))
        assertEquals(ToolTrailKind.Other, toolTrailKind(null, "application"))
        assertEquals(ToolTrailKind.Other, toolTrailKind("other", "think"))
    }

    @Test
    fun runMeterAndDiffStayLiteral() {
        val meter = parseRunMeter("tests 127/128\nFooTest")
        assertEquals(127, meter?.passed)
        assertEquals(128, meter?.total)
        assertEquals(1, meter?.failed)
        assertNull(parseRunMeter("no numbers"))
        assertNull(parseRunMeter("9/2"))
        assertEquals(listOf("+ added", "- removed"), diffLines("+ added\n- removed"))
        assertNull(diffLines("edited the file"))
    }

    @Test
    fun askDefaultsStayCompatibleAndRecommendationIsExplicit() {
        val plain = Ask("ship?", listOf(AskOption("Yes", AskTone.Primary, AskAction.Prompt("y"))))
        assertEquals(1, plain.index)
        assertEquals(1, plain.total)
        assertEquals("Yes", recommendedOption(plain)?.label)
        val marked = plain.copy(
            index = 1,
            total = 2,
            options = listOf(
                AskOption("Keep", AskTone.Neutral, AskAction.Prompt("keep"), consequence = "no rewrite"),
                AskOption("Replace", AskTone.Primary, AskAction.Prompt("replace"), recommended = true),
            ),
        )
        assertEquals("Replace", recommendedOption(marked)?.label)
        assertTrue(marked.options[1].recommended)
    }

    @Test
    fun rosterCountsMatchTheFilters() {
        val threads = listOf(
            thread("a", AgentStatus.Blocked, ask = Ask("go?", emptyList())),
            thread("b", AgentStatus.Working),
            thread("c", AgentStatus.Idle),
            thread("d", AgentStatus.Done),
        )
        val roster = sessionRoster(threads)
        assertEquals(4, roster.agents)
        assertEquals(1, roster.running)
        assertEquals(1, roster.waiting)
        assertEquals(2, roster.idle)
    }

    @Test
    fun searchMatchesTitleAgentAndSummary() {
        val threads = listOf(
            thread("a", AgentStatus.Idle).copy(title = "修复 UHP", agentType = "codex", summary = "gradle"),
            thread("b", AgentStatus.Idle).copy(title = "配对", agentType = "claude-code"),
        )
        assertEquals(listOf("a"), searchSessions(threads, "uhp").map { it.id })
        assertEquals(listOf("b"), searchSessions(threads, "Claude").map { it.id })
        assertEquals(listOf("a"), searchSessions(threads, "Gradle").map { it.id })
        assertEquals(threads, searchSessions(threads, "  "))
    }

    @Test
    fun agentMarkAndAgeStayLiteral() {
        assertEquals("CC", agentMark("claude-code", "重构"))
        assertEquals("C", agentMark("codex", "修复"))
        assertEquals("O", agentMark("opencode", "终端"))
        assertEquals("G", agentMark("gemini", "迁移"))
        assertEquals("?", agentMark("  ", " "))
        assertEquals(agentColorSlot("codex", "x"), agentColorSlot("codex", "y"))
        val now = 1_700_000_000_000L
        assertEquals("刚刚", sessionAgeLabel(now - 10_000, now))
        assertEquals("2 分钟", sessionAgeLabel(now - 120_000, now))
        assertEquals("1 小时", sessionAgeLabel(now - 3_600_000, now))
        assertNull(sessionAgeLabel(null, now))
        assertNull(sessionAgeLabel(now + 1, now))
    }

    @Test
    fun trailThumbUsesRealCallsAndSkipsOtherPanes() {
        assertNull(trailThumb(emptyList()))
        val read = AcpToolCall("r", "Read Pairing.kt", "read", AcpToolStatus.Completed, null)
        val run = AcpToolCall("x", "./gradlew :shared:allTests", "shell", AcpToolStatus.Failed, "96/128")
        val state = HostUhpState(
            agentDetail = AgentDetailState(
                paneId = "p1",
                timeline = PaneTimeline(items = listOf(TimelineItem.Tool("r", read), TimelineItem.Tool("x", run))),
            ),
            acp = AcpState(
                open = true,
                info = AcpSessionInfo("s", "claude", "Claude", 1, "/work"),
                transcript = listOf(
                    AcpTranscriptItem.Tool(
                        "e",
                        AcpToolCall("e", "Edit TokenStore.kt", "edit", AcpToolStatus.Completed, null),
                    ),
                ),
            ),
        )
        val threads = listOf(
            thread("p1", AgentStatus.Working).copy(paneId = "p1"),
            thread("p2", AgentStatus.Idle).copy(paneId = "p2"),
            thread("acp:s", AgentStatus.Blocked, ask = Ask("q", emptyList())).copy(
                kind = AgentKind.Acp,
                acpSessionId = "s",
            ),
        )
        val trails = threadTrails(state, threads).associate { it.threadId to it.thumb }
        assertEquals("./gradlew :shared:allTests · 96/128", trails["p1"]?.label)
        assertEquals(listOf(ToolTrailKind.Read, ToolTrailKind.Run), trails["p1"]?.kinds)
        assertEquals(750, trails["p1"]?.progressPermille)
        assertNull(trails["p2"])
        assertEquals("Edit TokenStore.kt", trails["acp:s"]?.label)
        assertEquals(listOf(ToolTrailKind.Edit), trails["acp:s"]?.kinds)
        assertNull(trails["acp:s"]?.progressPermille)
    }

    @Test
    fun sessionAvatarSeparatesKnownAgentsFromGenericRuntimes() {
        assertEquals(AgentAvatar("C", AgentAvatarPalette.Blue), sessionAvatar("codex", "修复 UHP"))
        assertEquals(AgentAvatar("C", AgentAvatarPalette.Blue), sessionAvatar("Codex", "other title"))
        assertEquals(AgentAvatar("CC", AgentAvatarPalette.Orange), sessionAvatar("claude-code", "重构"))
        assertEquals(AgentAvatar("CC", AgentAvatarPalette.Orange), sessionAvatar("Claude Code", "x"))
        assertEquals(AgentAvatar("CC", AgentAvatarPalette.Orange), sessionAvatar("claude", "x"))
        assertEquals(AgentAvatar("O", AgentAvatarPalette.Mint), sessionAvatar("opencode", "终端"))
        assertEquals(AgentAvatar("O", AgentAvatarPalette.Mint), sessionAvatar("open_code", "终端"))
        assertEquals(AgentAvatar("G", AgentAvatarPalette.Gray), sessionAvatar("gemini", "迁移"))
        assertEquals(AgentAvatar("C", AgentAvatarPalette.Blue), sessionAvatar("pi", "codex"))
        val skills = sessionAvatar("pi", "skills")
        val proxy = sessionAvatar("pi", "proxy")
        assertEquals("S", skills.mark)
        assertEquals("P", proxy.mark)
        assertEquals(skills, sessionAvatar("PI", "skills"))
        assertTrue(skills.palette !in reservedPalettes)
        assertTrue(proxy.palette !in reservedPalettes)
        assertEquals("CE", sessionAvatar("pi", "cat-emacs").mark)
        assertEquals(sessionAvatar("pi", "cat-emacs").palette, sessionAvatar("pi", "cat-emacs").palette)
        val grok = sessionAvatar("grok", "Compose")
        assertEquals("G", grok.mark)
        assertEquals(grok.palette, sessionAvatar("grok", "other title").palette)
        assertTrue(grok.palette !in reservedPalettes)
        assertEquals(AgentAvatar("?", AgentAvatarPalette.Gray), sessionAvatar("  ", " "))
        assertEquals("P", agentMark("pi", "skills"))
        assertEquals(agentColorSlot("codex", "x"), agentColorSlot("codex", "y"))
    }

    @Test
    fun agentBrandLogoMapsKnownAgentsAndLeavesPiAsLetter() {
        val claude = AgentBrandLogo(AGENT_BRAND_ASSET_CLAUDE, "#D97757", "#FFFFFF")
        val codex = AgentBrandLogo(AGENT_BRAND_ASSET_OPENAI, "#000000", "#FFFFFF")
        val grok = AgentBrandLogo(AGENT_BRAND_ASSET_GROK, "#000000", "#FFFFFF")
        assertEquals(0.58, AGENT_BRAND_LOGO_INSET, 0.0)
        assertEquals(0.58, claude.inset, 0.0)
        assertEquals(claude, agentBrandLogo("claude-code", "重构"))
        assertEquals(claude, agentBrandLogo("claude", "x"))
        assertEquals(claude, agentBrandLogo("Claude Code", "x"))
        assertEquals(claude, agentBrandLogo("CLAUDE_CODE", "codex review"))
        assertEquals(codex, agentBrandLogo("codex", "修复"))
        assertEquals(codex, agentBrandLogo("Codex", "other title"))
        assertEquals(grok, agentBrandLogo("grok", "Compose"))
        assertEquals(grok, agentBrandLogo("Grok", "other title"))
        assertNull(agentBrandLogo("pi", "skills"))
        assertNull(agentBrandLogo("pi", "proxy"))
        assertNull(agentBrandLogo("opencode", "终端"))
        assertNull(agentBrandLogo("gemini", "迁移"))
        assertNull(agentBrandLogo("  ", " "))
        assertEquals(grok, agentBrandLogo("pi", "清理 grok token"))
        assertEquals(codex, agentBrandLogo(null, "codex"))
        assertEquals(claude, agentBrandLogo("claude-code", "compare with grok"))
    }

    @Test
    fun listTrailShowsQuestionMeterOrSummary() {
        val thumb = trailThumb(
            listOf(
                AcpToolCall("r", "Read TokenStore.kt", "read", AcpToolStatus.Completed, null),
                AcpToolCall("x", "allTests", "shell", AcpToolStatus.Failed, "96/128"),
            ),
        )
        val waiting = thread(
            "a",
            AgentStatus.Blocked,
            ask = Ask("要把 TokenStore 拆成两个 actual 吗？", emptyList()),
        ).copy(summary = "old summary")
        val waitingTrail = listTrail(waiting, thumb)
        assertEquals("要把 TokenStore 拆成两个 actual 吗？", waitingTrail.label)
        assertEquals(listOf(ListTrailDot.Read, ListTrailDot.Run, ListTrailDot.Decide), waitingTrail.dots)
        assertFalse(waitingTrail.useMono)
        assertNull(waitingTrail.progressPermille)

        val running = thread("b", AgentStatus.Working).copy(summary = "正在改 ReconnectPolicy")
        val runningTrail = listTrail(running, thumb)
        assertEquals("allTests · 96/128", runningTrail.label)
        assertEquals(listOf(ListTrailDot.Read, ListTrailDot.Run), runningTrail.dots)
        assertTrue(runningTrail.useMono)
        assertEquals(750, runningTrail.progressPermille)

        val done = thread("g", AgentStatus.Done).copy(summary = "12 个文件变更，等你 review。")
        val doneTrail = listTrail(done, thumb)
        assertEquals("12 个文件变更，等你 review。", doneTrail.label)
        assertEquals(listOf(ListTrailDot.Read, ListTrailDot.Run), doneTrail.dots)
        assertFalse(doneTrail.useMono)
        assertNull(doneTrail.progressPermille)
    }

    @Test
    fun summaryTrailDoesNotInventToolsFromProse() {
        assertNull(summaryTrail(null))
        assertNull(summaryTrail("12 个文件变更，等你 review。"))
        assertNull(summaryTrail("正在改 ReconnectPolicy"))
        val parsed = summaryTrail("Read Pairing.kt")
        assertEquals("Read Pairing.kt", parsed?.label)
        assertEquals(listOf(ToolTrailKind.Read), parsed?.kinds)
        val fromSummary = listTrail(
            thread("s", AgentStatus.Idle).copy(summary = "Read Pairing.kt"),
            null,
        )
        assertEquals("Read Pairing.kt", fromSummary.label)
        assertEquals(listOf(ListTrailDot.Read), fromSummary.dots)
        assertFalse(fromSummary.useMono)
    }

    @Test
    fun listTrailKeepsTheNewestDots() {
        val calls = (1..8).map { index ->
            AcpToolCall("r$index", "Read F$index.kt", "read", AcpToolStatus.Completed, null)
        }
        val thumb = trailThumb(calls, limit = 8)
        val waiting = thread("w", AgentStatus.Blocked, ask = Ask("go?", emptyList()))
        val dots = listTrail(waiting, thumb).dots
        assertEquals(MAX_LIST_DOTS, dots.size)
        assertEquals(ListTrailDot.Read, dots.first())
        assertEquals(ListTrailDot.Decide, dots.last())
    }

    @Test
    fun playtestListFixtureMatchesWowRows() {
        val now = 1_700_000_100_000L
        val threads = playtestListThreads(now)
        assertEquals(4, threads.size)
        assertEquals(3, playtestThreads().size)
        assertEquals(listOf("codex", "claude-code", "opencode", "gemini"), threads.map { it.agentType })
        val trails = playtestListTrails().associate { it.threadId to it.thumb }
        val codex = threads.first { it.id == PLAYTEST_LIST_CODEX }
        val claude = threads.first { it.id == PLAYTEST_LIST_CLAUDE }
        val opencode = threads.first { it.id == PLAYTEST_LIST_OPENCODE }
        val gemini = threads.first { it.id == PLAYTEST_LIST_GEMINI }
        assertEquals(AgentStatus.Working, codex.status)
        assertEquals("刚刚", sessionAgeLabel(codex.updatedEpochMs, now))
        assertEquals("2 分钟", sessionAgeLabel(claude.updatedEpochMs, now))
        assertEquals("9 分钟", sessionAgeLabel(opencode.updatedEpochMs, now))
        assertEquals("1 小时", sessionAgeLabel(gemini.updatedEpochMs, now))
        val codexTrail = listTrail(codex, trails[codex.id])
        assertEquals("allTests · 96/128", codexTrail.label)
        assertEquals(750, codexTrail.progressPermille)
        assertEquals(
            listOf(ListTrailDot.Other, ListTrailDot.Other, ListTrailDot.Read, ListTrailDot.Read, ListTrailDot.Run),
            codexTrail.dots,
        )
        assertEquals(AgentAvatarPalette.Blue, sessionAvatar(codex.agentType, codex.title).palette)
        val claudeTrail = listTrail(claude, trails[claude.id])
        assertTrue(claudeTrail.label!!.contains("TokenStore"))
        assertEquals(ListTrailDot.Decide, claudeTrail.dots.last())
        assertEquals(
            listOf(ListTrailDot.Other, ListTrailDot.Read, ListTrailDot.Read, ListTrailDot.Run, ListTrailDot.Decide),
            claudeTrail.dots,
        )
        assertEquals("拆成两个", claude.ask?.options?.first()?.label)
        assertEquals(AgentAvatar("CC", AgentAvatarPalette.Orange), sessionAvatar(claude.agentType, claude.title))
        val opencodeTrail = listTrail(opencode, trails[opencode.id])
        assertEquals(
            listOf(ListTrailDot.Other, ListTrailDot.Other, ListTrailDot.Run, ListTrailDot.Decide),
            opencodeTrail.dots,
        )
        assertEquals(AgentAvatar("O", AgentAvatarPalette.Mint), sessionAvatar(opencode.agentType, opencode.title))
        val geminiTrail = listTrail(gemini, trails[gemini.id])
        assertEquals("12 个文件变更，等你 review。", geminiTrail.label)
        assertFalse(geminiTrail.useMono)
        assertEquals(
            listOf(
                ListTrailDot.Other,
                ListTrailDot.Read,
                ListTrailDot.Read,
                ListTrailDot.Read,
                ListTrailDot.Read,
                ListTrailDot.Edit,
            ),
            geminiTrail.dots,
        )
        assertEquals(AgentAvatar("G", AgentAvatarPalette.Gray), sessionAvatar(gemini.agentType, gemini.title))
        assertTrue(threads.all { isPlaytestListThread(it.id) && isPlaytestThread(it.id) })
        assertFalse(isPlaytestThread("pane-1"))
        assertFalse(isPlaytestListThread(PLAYTEST_DIFF_ID))
    }

    @Test
    fun acpTrailWithoutSessionIdStaysUnattached() {
        val state = HostUhpState(
            acp = AcpState(
                open = true,
                info = null,
                transcript = listOf(
                    AcpTranscriptItem.Tool(
                        "e",
                        AcpToolCall("e", "Edit TokenStore.kt", "edit", AcpToolStatus.Completed, null),
                    ),
                ),
            ),
        )
        val threads = listOf(
            thread("acp", AgentStatus.Working).copy(kind = AgentKind.Acp),
            thread("acp:other", AgentStatus.Idle).copy(kind = AgentKind.Acp, acpSessionId = "other"),
        )
        assertTrue(threadTrails(state, threads).isEmpty())
    }

    private val reservedPalettes = setOf(
        AgentAvatarPalette.Blue,
        AgentAvatarPalette.Orange,
        AgentAvatarPalette.Mint,
        AgentAvatarPalette.Gray,
    )

    private fun thread(
        id: String,
        status: AgentStatus,
        ask: Ask? = null,
        updated: Long? = null,
    ) = AgentThread(
        id = id,
        kind = AgentKind.Pane,
        hostId = "h",
        projectKey = null,
        projectLabel = null,
        title = id,
        agentType = null,
        status = status,
        ask = ask,
        summary = null,
        updatedEpochMs = updated,
    )
}
