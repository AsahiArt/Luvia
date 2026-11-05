package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpHistorySession
import tech.asahiart.luvia.AcpRunState
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
    fun sortPutsAcpHistoryAfterLiveRows() {
        val threads = listOf(
            thread("idle", AgentStatus.Idle, updated = 50),
            thread("run", AgentStatus.Working, updated = 10),
            thread("ask", AgentStatus.Blocked, ask = Ask(null, emptyList()), updated = 1),
            thread("done", AgentStatus.Done, updated = 99),
            history("sess_old", "pi", "/work").copy(updatedEpochMs = 1),
            history("sess_new", "pi", "/work").copy(updatedEpochMs = 1_000),
            thread("live-pane", AgentStatus.Idle, updated = 5),
            thread("acp:sess_live", AgentStatus.Idle, updated = 4).copy(
                kind = AgentKind.Acp,
                acpSessionId = "sess_live",
                acpHistory = false,
            ),
        )
        assertEquals(
            listOf(
                "ask",
                "run",
                "idle",
                "live-pane",
                "acp:sess_live",
                "done",
                "acp:sess_new",
                "acp:sess_old",
            ),
            sortSessions(threads).map { it.id },
        )
    }

    @Test
    fun awaitingAcpBindsRealSessionIdAndKeepsHistoryTitle() {
        val history =
            AcpHistorySession(
                agentId = "pi",
                agentName = "Pi",
                sessionId = "sess_9",
                cwd = "/work",
                title = "Ship the bridge",
                updatedAt = "2026-11-05T00:00:00Z",
            )
        val pending =
            HostUhpState(
                acp =
                    AcpState(
                        open = true,
                        viewing = true,
                        run = AcpRunState.Starting,
                        info = null,
                        history = listOf(history),
                    ),
            )
        assertEquals("acp", pending.threads("phone").first { it.kind == AgentKind.Acp && !it.acpHistory }.id)
        assertNull(awaitingAcpThread(pending, "phone"))
        val acked =
            pending.copy(
                acp =
                    pending.acp.copy(
                        info = AcpSessionInfo("sess_9", "pi", "Pi", 1, "/work"),
                        run = AcpRunState.Ready,
                    ),
            )
        val bound = awaitingAcpThread(acked, "phone")
        assertEquals("acp:sess_9", bound?.id)
        assertEquals("sess_9", bound?.acpSessionId)
        assertEquals(false, bound?.acpHistory)
        assertEquals("Ship the bridge", bound?.title)
        assertEquals(AgentStatus.Idle, bound?.status)
        val blankTitle =
            acked.copy(
                acp = acked.acp.copy(history = listOf(history.copy(title = " "))),
            )
        assertEquals("Pi", awaitingAcpThread(blankTitle, "phone")?.title)
        val launched =
            HostUhpState(
                acp =
                    AcpState(
                        open = true,
                        run = AcpRunState.Ready,
                        info = AcpSessionInfo("sess_new", "pi", "Pi", 1, "/work"),
                    ),
            )
        assertEquals("acp:sess_new", awaitingAcpThread(launched, "phone")?.id)
        assertEquals("Pi", awaitingAcpThread(launched, "phone")?.title)
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
    fun agentBrandLogoMapsKnownAgentsAndPiMark() {
        val claude = AgentBrandLogo(AGENT_BRAND_ASSET_CLAUDE, "#D97757", "#FFFFFF")
        val codex = AgentBrandLogo(AGENT_BRAND_ASSET_OPENAI, "#000000", "#FFFFFF")
        val grok = AgentBrandLogo(AGENT_BRAND_ASSET_GROK, "#000000", "#FFFFFF")
        val pi = AgentBrandLogo(
            AGENT_BRAND_ASSET_PI,
            "#00000000",
            "#00000000",
            AGENT_BRAND_PI_INSET,
            preservesColor = true,
        )
        assertEquals(0.58, AGENT_BRAND_LOGO_INSET, 0.0)
        assertEquals(0.58, claude.inset, 0.0)
        assertEquals(0.70, pi.inset, 0.0)
        assertTrue(pi.preservesColor)
        assertFalse(claude.preservesColor)
        assertEquals(claude, agentBrandLogo("claude-code", "重构"))
        assertEquals(claude, agentBrandLogo("claude", "x"))
        assertEquals(claude, agentBrandLogo("Claude Code", "x"))
        assertEquals(claude, agentBrandLogo("CLAUDE_CODE", "codex review"))
        assertEquals(codex, agentBrandLogo("codex", "修复"))
        assertEquals(codex, agentBrandLogo("Codex", "other title"))
        assertEquals(grok, agentBrandLogo("grok", "Compose"))
        assertEquals(grok, agentBrandLogo("Grok", "other title"))
        assertEquals(pi, agentBrandLogo("pi", "skills"))
        assertEquals(pi, agentBrandLogo("pi", "proxy"))
        assertEquals(pi, agentBrandLogo("PI", "logos-pi"))
        assertEquals(pi, agentBrandLogo("logos-pi", "quote0"))
        assertNull(agentBrandLogo("opencode", "终端"))
        assertNull(agentBrandLogo("gemini", "迁移"))
        assertNull(agentBrandLogo("api", "spike"))
        assertNull(agentBrandLogo("  ", " "))
        assertEquals(pi, agentBrandLogo("pi", "清理 grok token"))
        assertEquals(codex, agentBrandLogo(null, "codex"))
        assertEquals(claude, agentBrandLogo("claude-code", "compare with grok"))
        assertEquals(claude, agentBrandLogo("claude-code", "pi review"))
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

    @Test
    fun mergeDropsAcpHistoryWhenPaneHasSameAgentAndCwd() {
        val pane =
            thread("pane", AgentStatus.Working).copy(
                agentType = "claude-code",
                cwd = "/repo/",
            )
        val same =
            history("sess_same", "claude", "/repo")
        val otherDir =
            history("sess_other", "claude", "/else")
        val otherAgent =
            history("sess_pi", "pi", "/repo")
        val merged = mergeAcpHistory(listOf(pane), listOf(same, otherDir, otherAgent))
        assertEquals(listOf("pane", "acp:sess_other", "acp:sess_pi"), merged.map { it.id })
        assertEquals(true, merged.first { it.id == "acp:sess_other" }.acpHistory)
    }

    @Test
    fun mergeKeepsDistinctAcpSessionsAndSkipsTheOpenOne() {
        val live =
            thread("acp:sess_live", AgentStatus.Working).copy(
                kind = AgentKind.Acp,
                acpSessionId = "sess_live",
                agentType = "codex",
                cwd = "/repo",
            )
        val duplicate = history("sess_live", "codex", "/repo")
        val sibling = history("sess_next", "codex", "/repo")
        val merged = mergeAcpHistory(listOf(live), listOf(duplicate, sibling))
        assertEquals(listOf("acp:sess_live", "acp:sess_next"), merged.map { it.id })
    }

    @Test
    fun rfc3339SortKeyParsesZuluAndOffset() {
        assertEquals(1_762_300_800_000L, rfc3339EpochMs("2025-11-05T00:00:00Z"))
        assertEquals(1_762_300_800_000L, rfc3339EpochMs("2025-11-05T08:00:00+08:00"))
        assertEquals(null, rfc3339EpochMs("yesterday"))
    }

    private fun history(sessionId: String, agent: String, cwd: String) =
        AgentThread(
            id = "acp:$sessionId",
            kind = AgentKind.Acp,
            hostId = "h",
            projectKey = cwd,
            projectLabel = null,
            title = sessionId,
            agentType = agent,
            status = AgentStatus.Idle,
            ask = null,
            summary = null,
            updatedEpochMs = null,
            acpSessionId = sessionId,
            cwd = cwd,
            acpHistory = true,
            acpAgentId = agent,
        )

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
