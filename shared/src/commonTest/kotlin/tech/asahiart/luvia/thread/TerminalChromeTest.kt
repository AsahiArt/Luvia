package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalChromeTest {
    @Test
    fun gradleCompletedAlreadyIncludesFailures() {
        val banner = terminalFailureBanner(WOW_LOG)
        checkNotNull(banner)
        assertEquals(126, banner.passed)
        assertEquals(127, banner.total)
        assertEquals(1, banner.failed)
        assertEquals("41s", banner.duration)
        assertEquals("AnsiParserTest > sgr 38;5 truecolor", banner.failedTitle)
        val lines = terminalLines(WOW_LOG)
        val failed = lines.indexOfFirst { it.contains("truecolor FAILED") }
        val expected = lines.indexOfFirst { it.contains("expected:") }
        assertEquals(listOf(failed, expected), banner.outlinedLines)
        assertTrue(lines.indexOfFirst { it.startsWith("BUILD FAILED") } !in banner.outlinedLines)
        assertTrue(banner.outlinedLines.none { lines[it].contains("PASSED") })
        assertTrue(banner.excerpt.contains("expected:<#2EE6B6>"))
        assertEquals('1', banner.outlinedMask[failed])
        assertEquals('0', banner.outlinedMask[lines.indexOfFirst { it.startsWith("BUILD FAILED") }])
    }

    @Test
    fun laterRatioReplacesGradlePair() {
        val banner = terminalFailureBanner(
            """
            127 tests completed, 1 failed
            tests 10/12
            """.trimIndent(),
        )
        checkNotNull(banner)
        assertEquals(10, banner.passed)
        assertEquals(12, banner.total)
        assertEquals(2, banner.failed)
    }

    @Test
    fun gradleAfterRatioWins() {
        val banner = terminalFailureBanner(
            """
            tests 10/12
            4 tests completed, 1 failed
            BUILD FAILED in 3s
            """.trimIndent(),
        )
        checkNotNull(banner)
        assertEquals(3, banner.passed)
        assertEquals(4, banner.total)
        assertEquals(1, banner.failed)
        assertEquals("3s", banner.duration)
    }

    @Test
    fun junitSummaryIsPassedOverTotal() {
        val banner = terminalFailureBanner("Tests run: 128, Failures: 1, Skipped: 0")
        checkNotNull(banner)
        assertEquals(127, banner.passed)
        assertEquals(128, banner.total)
        assertEquals(1, banner.failed)
        assertNull(banner.duration)
    }

    @Test
    fun successAndEmptyStayQuiet() {
        assertNull(
            terminalFailureBanner(
                """
                FooTest > ok PASSED
                128 tests completed, 0 failed
                BUILD SUCCESSFUL in 4s
                """.trimIndent(),
            ),
        )
        assertNull(terminalFailureBanner(""))
        assertNull(terminalFailureBanner("caps at 30s"))
        assertNull(terminalFailureBanner("3 tests completed, 9 failed"))
    }

    @Test
    fun failedCaseWithoutSummaryStillOutlines() {
        val banner = terminalFailureBanner(
            """
            FooTest > ok PASSED
            FooTest > bad FAILED
              expected: 1 but was: 2
            """.trimIndent(),
        )
        checkNotNull(banner)
        assertEquals(1, banner.passed)
        assertEquals(2, banner.total)
        assertEquals(1, banner.failed)
        assertEquals("FooTest > bad", banner.failedTitle)
        assertEquals(listOf(1, 2), banner.outlinedLines)
        assertNull(banner.duration)
    }

    @Test
    fun summaryUsesParsedCountsAndDuration() {
        val banner = terminalFailureBanner(WOW_LOG)
        checkNotNull(banner)
        assertEquals("126/127 · 1 failed · 41s", terminalFailureSummary(banner))
        assertEquals("126/127 · 1 failed", terminalFailureSummaryHead(banner))
    }

    @Test
    fun failureProgressIsPassedOverTotal() {
        assertEquals(126f / 127f, terminalFailureProgress(126, 127))
        assertEquals(0f, terminalFailureProgress(0, 4))
        assertEquals(1f, terminalFailureProgress(4, 4))
        assertEquals(1f, terminalFailureProgress(8, 4))
        assertEquals(0f, terminalFailureProgress(-1, 4))
        assertEquals(0f, terminalFailureProgress(3, 0))
        assertEquals(0f, terminalFailureProgress(1, -2))
    }

    @Test
    fun durationStaysOnTheSummaryUntilThatLineCannotHoldIt() {
        assertEquals(
            TerminalFailureDurationPlace.Summary,
            terminalFailureDurationPlace("41s", summaryFitsWithDuration = true),
        )
        assertEquals(
            TerminalFailureDurationPlace.Bar,
            terminalFailureDurationPlace("41s", summaryFitsWithDuration = false),
        )
        assertEquals(
            TerminalFailureDurationPlace.Summary,
            terminalFailureDurationPlace(null, summaryFitsWithDuration = false),
        )
        assertEquals(
            TerminalFailureDurationPlace.Summary,
            terminalFailureDurationPlace("  ", summaryFitsWithDuration = false),
        )
    }

    @Test
    fun summaryOmitsDurationWhenTheBuildDidNotReportOne() {
        val banner = terminalFailureBanner("Tests run: 128, Failures: 1, Skipped: 0")
        checkNotNull(banner)
        assertEquals("127/128 · 1 failed", terminalFailureSummary(banner))
    }

    @Test
    fun actualAndExpectedLinesStayWithTheFailure() {
        val banner = terminalFailureBanner(
            """
            Case > mismatch FAILED
            actual:<2>
            expected:<1>
            ${'$'} next
            """.trimIndent(),
        )
        checkNotNull(banner)
        assertEquals(listOf(0, 1, 2), banner.outlinedLines)
        assertEquals("0/1 · 1 failed", terminalFailureSummary(banner))
    }

    @Test
    fun gridAndStatusLineUseTheMeasuredViewport() {
        assertEquals(80 to 32, terminalGrid(800, 512, 10, 16))
        assertNull(terminalGrid(0, 512, 10, 16))
        assertNull(terminalGrid(800, 512, 0, 16))
        assertNull(terminalGrid(8, 10, 10, 16))
        assertEquals(
            "claude-code · 80×32 · 已同步",
            terminalStatusLine("claude-code", 80, 32, synced = true, syncedWord = "已同步"),
        )
        assertEquals(
            "claude-code · 80×32",
            terminalStatusLine(" claude-code ", 80, 32, synced = false, syncedWord = "已同步"),
        )
        assertEquals(
            "claude-code · 已同步",
            terminalStatusLine("claude-code", 0, 0, synced = true, syncedWord = "已同步"),
        )
    }

    @Test
    fun paneRunningPiShowsTheFixingChip() {
        val offer = terminalFailureOffer(paneForeground = "pi", sessionAgentType = "claude")
        assertEquals(TerminalFailureMode.Fixing, offer.mode)
        assertEquals("pi", offer.agentName)
        assertEquals("pi", offer.avatarAgent)
        assertEquals("pi 在修…", offer.fixingLabel)
        assertTrue(offer.choices.isEmpty())
        val claude = terminalFailureOffer("claude-code", null)
        assertEquals(TerminalFailureMode.Fixing, claude.mode)
        assertEquals("claude 在修…", claude.fixingLabel)
    }

    @Test
    fun gradlePaneHandsOffToTheSessionAgent() {
        val offer = terminalFailureOffer(
            paneForeground = "gradle",
            sessionAgentType = "pi",
            sessionDisplayName = "gradle",
        )
        assertEquals(TerminalFailureMode.Handoff, offer.mode)
        assertEquals("pi", offer.agentName)
        assertEquals("交给 pi 修", offer.handoffLabel)
        assertEquals(listOf("pi"), offer.choices.map { it.name })
        assertEquals("交给 claude 修", terminalFailureOffer("zsh", "claude-code").handoffLabel)
    }

    @Test
    fun fixCandidatesPreferHostAgentsThenRecognizedThreads() {
        val fromHost = terminalFixCandidates(
            hostAgentIds = listOf("pi", "claude-code", "zsh"),
            threadAgentTypes = listOf("codex"),
        )
        assertEquals(listOf("pi", "claude"), fromHost.map { it.name })
        assertEquals(listOf("pi", "claude-code"), fromHost.map { it.agentType })
        assertTrue(fromHost.none { it.agentType == "zsh" || it.name == "zsh" })
        val fromThreads = terminalFixCandidates(
            hostAgentIds = emptyList(),
            threadAgentTypes = listOf("codex", "gradle", "pi", "opencode"),
        )
        assertEquals(listOf("codex", "pi"), fromThreads.map { it.name })
        val shellsFallThrough = terminalFixCandidates(
            hostAgentIds = listOf("zsh", "gradle"),
            threadAgentTypes = listOf("pi", "zsh"),
        )
        assertEquals(listOf("pi"), shellsFallThrough.map { it.agentType })
        assertTrue(terminalFixCandidates(emptyList(), emptyList()).isEmpty())
        assertTrue(terminalFixCandidates(listOf("zsh"), listOf("gradle")).isEmpty())
    }

    @Test
    fun newAgentChoiceIsOnlyADifferentRecognizedAgent() {
        assertFalse(terminalFixOpensNewAgent(null, "pi"))
        assertFalse(terminalFixOpensNewAgent("  ", "pi"))
        assertFalse(terminalFixOpensNewAgent("zsh", "pi"))
        assertFalse(terminalFixOpensNewAgent("pi", "pi"))
        assertFalse(terminalFixOpensNewAgent("claude", "claude-code"))
        assertTrue(terminalFixOpensNewAgent("codex", "pi"))
        assertTrue(terminalFixOpensNewAgent("pi", null))
        assertTrue(sameTerminalAgent("claude-code", "Claude"))
        assertFalse(sameTerminalAgent("pi", "codex"))
        assertTrue(sameTerminalAgent("zsh", "ZSH"))
        assertFalse(terminalFixOpensNewAgent("zsh", null))
    }

    @Test
    fun missingSessionAgentUsesTheGenericHandoff() {
        val offer = terminalFailureOffer(paneForeground = "gradle", sessionAgentType = null, sessionDisplayName = "gradle")
        assertEquals(TerminalFailureMode.Handoff, offer.mode)
        assertEquals("agent", offer.agentName)
        assertNull(offer.avatarAgent)
        assertEquals("交给 agent 修", offer.handoffLabel)
        assertTrue(offer.choices.isEmpty())
    }

    @Test
    fun paneNumbersMatchTheSegmentAndFiveShareTheRow() {
        assertEquals(1, terminalPaneNumber(0))
        assertEquals(3, terminalPaneNumber(2))
        assertEquals("3 claude", terminalSegmentLabel(2, "claude"))
        assertEquals(terminalPaneNumber(2).toString(), terminalSegmentLabel(2, "claude").substringBefore(' '))
        assertTrue(paneSegmentsShareWidth(2))
        assertTrue(paneSegmentsShareWidth(5))
        assertFalse(paneSegmentsShareWidth(6))
        assertFalse(paneSegmentsShareWidth(0))
    }

    @Test
    fun segmentLabelKeepsIndexAndName() {
        assertEquals("1 zsh", terminalSegmentLabel(0, "zsh"))
        assertEquals("2 gradle", terminalSegmentLabel(1, "gradle"))
        assertEquals("3 claude-code", terminalSegmentLabel(2, "claude-code"))
        assertEquals("1 pane", terminalSegmentLabel(0, "7"))
        assertEquals("1 pane", terminalSegmentLabel(0, "   "))
        assertEquals("4 gradle", terminalSegmentLabel(3, "/tmp/gradle"))
    }

    private companion object {
        val WOW_LOG =
            """
            ~/code/luvus-mobile feat/reconnect
            ${'$'} ./gradlew :shared:allTests
            > Task :shared:compileKotlinIosSimulatorArm64
            > Task :shared:iosSimulatorArm64Test
            ReconnectPolicyTest > backoff caps at 30s PASSED
            ReconnectPolicyTest > 401 stops retry PASSED
            SnapshotSyncTest > resync on seq gap PASSED
            AnsiParserTest > sgr 38;5 truecolor FAILED
              expected:<#2EE6B6> but was:<#2EE6B7>
            > Task :shared:testDebugUnitTest
            CompanionClientTest > pairs with code PASSED
            CompanionClientTest > rejects expired PASSED

            127 tests completed, 1 failed
            BUILD FAILED in 41s
            ${'$'} git diff --stat
            shared/.../ReconnectPolicy.kt | 42 ++++++++--
            shared/.../AnsiParser.kt | 6 +-
            ${'$'}
            """.trimIndent()
    }
}
