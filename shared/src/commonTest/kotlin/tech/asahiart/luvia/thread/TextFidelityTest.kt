package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFidelityTest {
    @Test
    fun askReplacesOnlyItsOwnQuestion() {
        val raw = "部署检查：\n> 备份已验证吗？\n> 继续迁移吗？\n"
        val shown = PaneTimeline().ingestTranscript(raw).render(Ask("继续迁移吗？", emptyList()))
        val prose = shown.filterIsInstance<TimelineItem.Output>().joinToString("\n") { it.text }
        assertTrue(prose.contains("部署检查："))
        assertTrue(prose.contains("> 备份已验证吗？"))
        assertFalse(prose.contains("继续迁移吗？"))
        assertTrue(shown.filterIsInstance<TimelineItem.AskCard>().single().ask.question == "继续迁移吗？")
    }

    @Test
    fun numberedPlanSurvivesNullFallbackAndADifferentAsk() {
        val raw = "Deployment plan:\n1. Back up DB\n2. Roll traffic\n"
        val asks = listOf(null, DECISION_QUESTION_FALLBACK, "别的问题？")
        for (question in asks) {
            val prose =
                PaneTimeline()
                    .ingestTranscript(raw)
                    .render(Ask(question, emptyList()))
                    .filterIsInstance<TimelineItem.Output>()
                    .joinToString("\n") { it.text }
            assertTrue(prose.contains("Deployment plan:"), question ?: "null")
            assertTrue(prose.contains("1. Back up DB"), question ?: "null")
            assertTrue(prose.contains("2. Roll traffic"), question ?: "null")
        }
    }

    @Test
    fun matchingAskStillHidesTheNumberedMenuOnce() {
        val raw = "Run the tests?\n1. Yes\n2. No\n"
        val prose =
            PaneTimeline()
                .ingestTranscript(raw)
                .render(Ask("Run the tests?", emptyList()))
                .filterIsInstance<TimelineItem.Output>()
                .joinToString("\n") { it.text }
        assertTrue(prose.contains("Run the tests?"))
        assertFalse(prose.contains("1. Yes"))
        assertFalse(prose.contains("2. No"))
    }

    @Test
    fun repeatedQuestionKeepsTheEarlierCopy() {
        val raw = "说明：\n> 继续迁移吗？\n> 继续迁移吗？\n"
        val prose = PaneTimeline().ingestTranscript(raw).render(Ask("继续迁移吗？", emptyList()))
            .filterIsInstance<TimelineItem.Output>()
            .joinToString("\n") { it.text }
        assertEquals("说明：\n> 继续迁移吗？", prose.trim())
    }

    @Test
    fun aDifferentAskDoesNotHideThisOutput() {
        val raw = "部署检查：\n> 备份已验证吗？\n> 继续迁移吗？\n"
        val prose = PaneTimeline().ingestTranscript(raw).render(Ask("要不要回滚？", emptyList()))
            .filterIsInstance<TimelineItem.Output>()
            .joinToString("\n") { it.text }
        assertTrue(prose.contains("备份已验证吗？"))
        assertTrue(prose.contains("继续迁移吗？"))
        assertTrue(prose.contains("部署检查："))
    }

    @Test
    fun hardWrapInsideAPathDoesNotGainASpace() {
        val transcript = "> Remove /srv/pro\nject?\n"
        assertEquals("Remove /srv/project?", decisionQuestionText(null, transcript))
        val slash = "> Remove /srv/\nproject?\n"
        assertEquals("Remove /srv/project?", decisionQuestionText(null, slash))
        val dashed = "> Remove term-\nprobe.txt?\n"
        assertEquals("Remove term-probe.txt?", decisionQuestionText(null, dashed))
        val words = "> Remove the\nfile?\n"
        assertEquals("Remove the file?", decisionQuestionText(null, words))
    }

    @Test
    fun consequenceKeepsAWrappedPathAndAWrappedWord() {
        val path = """
            |Continue?
            |┃  1 (○) Allow  delete /srv/pro
            |┃              ject now
            |""".trimMargin()
        val word = """
            |Continue?
            |┃  1 (○) Allow  delete the
            |┃              file now
            |""".trimMargin()
        val pathAsk = paneDecision(path, allowQuestionFallback = true)
        val wordAsk = paneDecision(word, allowQuestionFallback = true)
        assertEquals("delete /srv/project now", pathAsk.options.single().consequence)
        assertEquals("delete the file now", wordAsk.options.single().consequence)
    }
}
