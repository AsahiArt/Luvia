package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolTrailFormatTest {
    @Test
    fun diffUsesHunkLineNumbersAndSeparateMarkers() {
        val summary =
            """
            diff --git a/shared/ReconnectPolicy.kt b/shared/ReconnectPolicy.kt
            --- a/shared/ReconnectPolicy.kt
            +++ b/shared/ReconnectPolicy.kt
            @@ -12,3 +12,4 @@
             class ReconnectPolicy {
            -    fun retry()
            +    fun reconnect()
            +    fun backoff()
             }
            """.trimIndent()
        val file = toolTrailView(call("edit", "Edit ReconnectPolicy", summary)).diffs.single()
        assertEquals("shared/ReconnectPolicy.kt", file.path)
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
        val deletion = file.lines.first { it.marker == "-" }
        assertEquals(13, deletion.oldNumber)
        assertNull(deletion.newNumber)
        assertEquals("    fun retry()", deletion.text)
        val additions = file.lines.filter { it.marker == "+" }
        assertEquals(listOf(13, 14), additions.map { it.newNumber })
        assertTrue(additions.all { it.oldNumber == null })
    }

    @Test
    fun diffWithoutHunkDoesNotInventLineNumbers() {
        val file = toolTrailView(call("edit", "Edit", "+ added\n- removed")).diffs.single()
        assertEquals("+", file.lines[0].marker)
        assertEquals(" added", file.lines[0].text)
        assertNull(file.lines[0].oldNumber)
        assertNull(file.lines[0].newNumber)
        assertEquals(" removed", file.lines[1].text)
        assertNull(file.lines[1].oldNumber)
    }

    @Test
    fun pathDurationAndFailedNamesStayLiteral() {
        val read = toolTrailView(
            call("read", "% read", "  ~/.local/share/chezmoi → 22 lines"),
        )
        assertEquals("~/.local/share/chezmoi", read.path)
        assertEquals(ToolTrailKind.Read, read.kind)

        val timed = toolTrailView(call("bash", "$: curl", "EXIT; curl → done · 23.4s"))
        assertEquals("23.4s", timed.duration)

        val tests = toolTrailView(
            AcpToolCall("x", "gradle test", "execute", AcpToolStatus.Failed, "tests 127/128\nReconnectTest"),
        )
        assertEquals(127, tests.meter?.passed)
        assertEquals(128, tests.meter?.total)
        assertEquals(listOf("ReconnectTest"), tests.failedNames)
        assertTrue(tests.markFailed)
        assertNull(toolTrailView(call("other", "application", null)).path)
    }

    @Test
    fun explicitPathAndDurationWin() {
        val view = toolTrailView(
            AcpToolCall(
                toolCallId = "r",
                title = "Read",
                kind = "read",
                status = AcpToolStatus.Completed,
                summary = "notes",
                path = "shared/src/Pairing.kt",
                duration = "120ms",
            ),
        )
        assertEquals("shared/src/Pairing.kt", view.path)
        assertEquals("120ms", view.duration)
    }

    @Test
    fun streamPartsMatchTheChipFacts() {
        val read = AcpToolCall("r", "Read Pairing.kt", "read", AcpToolStatus.Completed, null, "shared/Pairing.kt")
        val edit = call(
            "edit",
            "Edit ReconnectPolicy",
            """
            diff --git a/shared/ReconnectPolicy.kt b/shared/ReconnectPolicy.kt
            +++ b/shared/ReconnectPolicy.kt
            @@ -12,1 +12,2 @@
            -    fun retry()
            +    fun reconnect()
            """.trimIndent(),
        )
        val run = AcpToolCall("x", "gradle test", "execute", AcpToolStatus.Failed, "tests 127/128\nReconnectTest")
        val parts = toolStreamParts(listOf(read, edit, run))
        val reads = assertIs<ToolStreamPart.Reads>(parts[0])
        assertEquals(1, reads.count)
        val file = assertIs<ToolStreamPart.Edit>(parts[1])
        assertEquals("ReconnectPolicy.kt", file.file)
        assertEquals(1, file.additions)
        assertEquals(1, file.deletions)
        assertTrue(file.hasDiff)
        val meter = assertIs<ToolStreamPart.Meter>(parts[2])
        assertEquals(127, meter.passed)
        assertEquals(128, meter.total)
    }

    @Test
    fun highlightKeepsTheLineAndMarksTheWowTokens() {
        val line = "private val delayMs: Long = 1000"
        val tokens = highlightCode(line)
        assertEquals(line, tokens.joinToString("") { it.text })
        assertEquals(CodeTokenKind.Keyword, tokens.first { it.text == "private" }.kind)
        assertEquals(CodeTokenKind.Keyword, tokens.first { it.text == "val" }.kind)
        assertEquals(CodeTokenKind.Plain, tokens.first { it.text == "delayMs" }.kind)
        assertEquals(CodeTokenKind.Type, tokens.first { it.text == "Long" }.kind)
        assertEquals(CodeTokenKind.Number, tokens.first { it.text == "1000" }.kind)

        val next = "fun next(n: Int) = minOf(base * (1 shl n), cap)"
        val nextTokens = highlightCode(next)
        assertEquals(next, nextTokens.joinToString("") { it.text })
        assertEquals(CodeTokenKind.Keyword, nextTokens.first { it.text == "fun" }.kind)
        assertEquals(CodeTokenKind.Type, nextTokens.first { it.text == "Int" }.kind)
        assertEquals(CodeTokenKind.Number, nextTokens.first { it.text == "1" }.kind)
        assertEquals(CodeTokenKind.Plain, nextTokens.first { it.text == "minOf" }.kind)

        val dotted = highlightCode("1.seconds")
        assertEquals("1.seconds", dotted.joinToString("") { it.text })
        assertEquals(CodeTokenKind.Number, dotted.first().kind)
        assertEquals("seconds", dotted.last().text)
        assertEquals(CodeTokenKind.Plain, dotted.last().kind)
        assertEquals(CodeTokenKind.String, highlightCode("\"a\\\"b\"").single().kind)
        assertEquals(CodeTokenKind.Comment, highlightCode("// note").single().kind)
        assertEquals(CodeTokenKind.Type, highlightCode("class ReconnectPolicy(").first { it.text == "ReconnectPolicy" }.kind)
    }

    @Test
    fun diffHeaderSplitsFileAndModule() {
        assertEquals("ReconnectPolicy.kt", diffFileTitle("shared/commonMain/ReconnectPolicy.kt"))
        assertEquals("shared/commonMain", diffModulePath("shared/commonMain/ReconnectPolicy.kt"))
        assertEquals("file", diffFileTitle(null))
        assertEquals("file", diffFileTitle("  "))
        assertNull(diffModulePath("ReconnectPolicy.kt"))
    }

    @Test
    fun structuredDiffRequiresARealHeader() {
        assertTrue(structuredDiffFiles("- first\n- second").isEmpty())
        val text =
            """
            diff --git a/shared/commonMain/ReconnectPolicy.kt b/shared/commonMain/ReconnectPolicy.kt
            --- a/shared/commonMain/ReconnectPolicy.kt
            +++ b/shared/commonMain/ReconnectPolicy.kt
            @@ -12,2 +12,2 @@
             class ReconnectPolicy(
            -    private val delayMs: Long = 1000
            +    private val base: Duration = 1.seconds
            """.trimIndent()
        val file = structuredDiffFiles(text).single()
        assertEquals("ReconnectPolicy.kt", diffFileTitle(file.path))
        assertEquals("shared/commonMain", diffModulePath(file.path))
        assertEquals(13, file.lines.first { it.marker == "-" }.oldNumber)
        assertNull(file.lines.first { it.marker == "-" }.newNumber)
    }

    @Test
    fun runThatAlsoEditsEmitsBothChips() {
        val summary =
            """
            diff --git a/shared/ReconnectPolicy.kt b/shared/ReconnectPolicy.kt
            +++ b/shared/ReconnectPolicy.kt
            @@ -12,1 +12,1 @@
            -    fun retry()
            +    fun reconnect() // 127/128
            """.trimIndent()
        val parts = toolStreamParts(
            listOf(AcpToolCall("x", "gradle test", "execute", AcpToolStatus.Failed, summary)),
        )
        val file = assertIs<ToolStreamPart.Edit>(parts[0])
        assertEquals("ReconnectPolicy.kt", file.file)
        assertEquals(1, file.additions)
        assertEquals(1, file.deletions)
        assertTrue(file.hasDiff)
        val meter = assertIs<ToolStreamPart.Meter>(parts[1])
        assertEquals(127, meter.passed)
        assertEquals(128, meter.total)
    }

    @Test
    fun testBarIsAlways64Cells() {
        val cells = testBarCells(127, 128, markFailed = true)
        assertEquals(TEST_BAR_CELLS, cells.size)
        assertEquals(1, cells.count { it })
        assertTrue(cells.last())
        assertEquals(cells.joinToString("") { if (it) "1" else "0" }, testBarMask(127, 128, true))
        assertEquals(TEST_BAR_CELLS, testBarCells(0, 0, false).size)
    }

    @Test
    fun unifiedDiffRoundTripsLineNumbers() {
        val rendered = unifiedDiff(
            "shared/ReconnectPolicy.kt",
            "class ReconnectPolicy {\n    fun retry()\n}\n",
            "class ReconnectPolicy {\n    fun reconnect()\n    fun backoff()\n}\n",
        )
        val file = toolTrailView(call("edit", "Edit", rendered)).diffs.single()
        assertEquals("shared/ReconnectPolicy.kt", file.path)
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
        assertEquals(1, file.lines.first().oldNumber)
        assertEquals("class ReconnectPolicy {", file.lines.first().text)
    }

    private fun call(kind: String, title: String, summary: String?) =
        AcpToolCall(kind, title, kind, AcpToolStatus.Completed, summary)
}
