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
    fun fixButtonNamesTheSessionAgent() {
        assertEquals("codex", terminalFixAgentName("codex"))
        assertEquals("claude-code", terminalFixAgentName("  claude-code  "))
        assertEquals("pi", terminalFixAgentName("pi"))
        assertEquals("grok", terminalFixAgentName("grok"))
        assertEquals("agent", terminalFixAgentName("   "))
        assertEquals("交给 codex 修", terminalFixLabel("codex"))
        assertEquals("交给 pi 修", terminalFixLabel("pi"))
        assertEquals("交给 claude-code 修", terminalFixLabel("claude-code"))
        assertEquals("交给 claude 修", terminalFixLabel("claude"))
        assertFalse(terminalFixLabel("codex").contains("claude"))
        assertFalse(terminalFixLabel("grok").contains("claude"))
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
