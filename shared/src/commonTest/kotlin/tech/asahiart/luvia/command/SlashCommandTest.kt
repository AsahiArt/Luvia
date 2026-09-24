package tech.asahiart.luvia.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlashCommandTest {
    private val sample =
        listOf(
            SlashCommand("/compact", "Compact context", needsTerminal = false),
            SlashCommand("/clear", "Clear session", needsTerminal = false),
            SlashCommand("/model", "Switch model", needsTerminal = true),
            SlashCommand("/help", "Show help", needsTerminal = false),
        )

    @Test
    fun catalogMatchesAgentTypeCaseInsensitiveAndBySubstring() {
        assertEquals(8, SlashCommandCatalog.forAgent("claude").size)
        assertEquals(8, SlashCommandCatalog.forAgent("Claude Code").size)
        assertEquals(7, SlashCommandCatalog.forAgent("CODEX").size)
        assertEquals(7, SlashCommandCatalog.forAgent("openai-codex").size)
        assertEquals(7, SlashCommandCatalog.forAgent("gemini-cli").size)
    }

    @Test
    fun catalogReturnsEmptyForUnknownOrNullAgent() {
        assertTrue(SlashCommandCatalog.forAgent(null).isEmpty())
        assertTrue(SlashCommandCatalog.forAgent("").isEmpty())
        assertTrue(SlashCommandCatalog.forAgent("pi").isEmpty())
    }

    @Test
    fun catalogFlagsNeedsTerminalCommands() {
        val claude = SlashCommandCatalog.forAgent("claude")
        assertEquals(true, claude.single { it.name == "/model" }.needsTerminal)
        assertEquals(true, claude.single { it.name == "/resume" }.needsTerminal)
        val codex = SlashCommandCatalog.forAgent("codex")
        assertEquals(true, codex.single { it.name == "/approvals" }.needsTerminal)
    }

    @Test
    fun filterOrdersPrefixMatchesBeforeSubstringMatches() {
        assertEquals(listOf("/compact", "/clear", "/model"), filter(sample, "c").map { it.name })
        assertEquals(listOf("/compact", "/clear", "/model"), filter(sample, "/c").map { it.name })
        assertEquals(listOf("/model"), filter(sample, "/mo").map { it.name })
    }

    @Test
    fun filterMatchesDescriptionSubstringAfterPrefixTier() {
        val result = filter(sample, "switch")
        assertEquals(listOf("/model"), result.map { it.name })
    }

    @Test
    fun filterEmptyQueryReturnsFullList() {
        assertEquals(sample, filter(sample, ""))
        assertEquals(sample, filter(sample, "   "))
    }
}
