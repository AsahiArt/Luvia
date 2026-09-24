package tech.asahiart.luvia.command

public object SlashCommandCatalog {
    private val claude: List<SlashCommand> =
        listOf(
            SlashCommand("/compact", "Compact context", needsTerminal = false),
            SlashCommand("/clear", "Clear session", needsTerminal = false),
            SlashCommand("/model", "Switch model", needsTerminal = true),
            SlashCommand("/resume", "Resume session", needsTerminal = true),
            SlashCommand("/cost", "Show usage cost", needsTerminal = false),
            SlashCommand("/init", "Initialize project", needsTerminal = false),
            SlashCommand("/review", "Request code review", needsTerminal = false),
            SlashCommand("/help", "Show help", needsTerminal = false),
        )

    private val codex: List<SlashCommand> =
        listOf(
            SlashCommand("/model", "Switch model", needsTerminal = true),
            SlashCommand("/approvals", "Configure approvals", needsTerminal = true),
            SlashCommand("/new", "Start new task", needsTerminal = false),
            SlashCommand("/compact", "Compact context", needsTerminal = false),
            SlashCommand("/diff", "Show diff", needsTerminal = false),
            SlashCommand("/status", "Show status", needsTerminal = false),
            SlashCommand("/init", "Initialize project", needsTerminal = false),
        )

    private val gemini: List<SlashCommand> =
        listOf(
            SlashCommand("/help", "Show help", needsTerminal = false),
            SlashCommand("/clear", "Clear session", needsTerminal = false),
            SlashCommand("/compress", "Compress context", needsTerminal = false),
            SlashCommand("/chat", "Start chat mode", needsTerminal = false),
            SlashCommand("/memory", "Manage memory", needsTerminal = false),
            SlashCommand("/stats", "Show statistics", needsTerminal = false),
            SlashCommand("/tools", "List tools", needsTerminal = false),
        )

    private val pi: List<SlashCommand> =
        listOf(
            SlashCommand("/model", "Switch model", needsTerminal = true),
            SlashCommand("/compact", "Compact context", needsTerminal = false),
            SlashCommand("/new", "Start new session", needsTerminal = false),
            SlashCommand("/resume", "Resume session", needsTerminal = true),
            SlashCommand("/settings", "Open settings", needsTerminal = true),
            SlashCommand("/help", "Show help", needsTerminal = false),
        )

    /** Commands most terminal Agents share; used when the Agent type is unknown. */
    private val common: List<SlashCommand> =
        listOf(
            SlashCommand("/help", "Show help", needsTerminal = false),
            SlashCommand("/model", "Switch model", needsTerminal = true),
            SlashCommand("/compact", "Compact context", needsTerminal = false),
            SlashCommand("/clear", "Clear session", needsTerminal = false),
            SlashCommand("/resume", "Resume session", needsTerminal = true),
        )

    public fun forAgent(agentType: String?): List<SlashCommand> {
        val normalized = agentType?.lowercase()?.trim().orEmpty()
        return when {
            "claude" in normalized -> claude
            "codex" in normalized -> codex
            "gemini" in normalized -> gemini
            normalized == "omp" || normalized == "pi" || "oh-my-pi" in normalized -> pi
            else -> common
        }
    }
}
