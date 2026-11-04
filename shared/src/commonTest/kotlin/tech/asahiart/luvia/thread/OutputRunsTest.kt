package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputRunsTest {
    @Test
    fun hardWrappedProseIsJoined() {
        val runs = outputRuns("The terminal wraps this sentence\nat its own width.\n\nNext paragraph.")
        assertEquals(listOf(OutputRun("The terminal wraps this sentence at its own width.\nNext paragraph.", mono = false)), runs)
    }

    @Test
    fun listItemsKeepTheirBreaks() {
        val runs = outputRuns("Plan:\n- first step\n  continues here\n- second")
        assertEquals("Plan:\n- first step continues here\n- second", runs.single().text)
    }

    @Test
    fun boxDrawingStaysMono() {
        val runs = outputRuns("Intro\n╭──╮\n│ab│\n╰──╯\nOutro")
        assertEquals(listOf(false, true, false), runs.map { it.mono })
        assertEquals("╭──╮\n│ab│\n╰──╯", runs[1].text)
    }

    @Test
    fun chineseHardWrapDoesNotInsertASpace() {
        val runs = outputRuns("用户同意清理 bun、uv、Gradle 和日\n志等约 22GB 的安全缓存。")
        assertEquals(
            listOf(OutputRun("用户同意清理 bun、uv、Gradle 和日志等约 22GB 的安全缓存。", mono = false)),
            runs,
        )
    }

    @Test
    fun fencedCodeKeepsLinesAndDropsFences() {
        val runs = outputRuns("```\nval a = 1\nval b = 2\n```")
        assertEquals(listOf(OutputRun("val a = 1\nval b = 2", mono = true)), runs)
    }

    @Test
    fun unifiedDiffStaysASeparateMonoRun() {
        val text = "Look at this.\ndiff --git a/A.kt b/A.kt\n--- a/A.kt\n+++ b/A.kt\n@@ -1 +1 @@\n-old\n+new\nDone."
        val runs = outputRuns(text)
        assertEquals(listOf(false, true, false), runs.map { it.mono })
        assertEquals("Look at this.", runs[0].text)
        assertTrue(runs[1].text.contains("@@ -1 +1 @@"))
        assertTrue(runs[1].text.contains("\n-old\n"))
        assertEquals("Done.", runs[2].text)
    }

    @Test
    fun proseSpansChipSymbolsAndKeepShortTokens() {
        assertEquals(
            listOf(
                ProseSpan(ProseSpanKind.Text, "先看现有 "),
                ProseSpan(ProseSpanKind.Code, "CompanionClient"),
                ProseSpan(ProseSpanKind.Text, "，改 "),
                ProseSpan(ProseSpanKind.Code, "token"),
                ProseSpan(ProseSpanKind.Text, "。"),
            ),
            proseSpans("先看现有 CompanionClient，改 `token`。"),
        )
        assertEquals(listOf(ProseSpan(ProseSpanKind.Text, "用 iOS 就好")), proseSpans("用 iOS 就好"))
    }
}
