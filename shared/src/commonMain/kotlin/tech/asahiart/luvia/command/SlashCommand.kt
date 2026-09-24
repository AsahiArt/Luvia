package tech.asahiart.luvia.command

public data class SlashCommand(
    public val name: String,
    public val description: String,
    public val needsTerminal: Boolean,
)

/**
 * Filters [commands] for command-palette search.
 * Prefix matches on [SlashCommand.name] come first, then substring matches on name or description.
 */
public fun filter(
    commands: List<SlashCommand>,
    query: String,
): List<SlashCommand> {
    val q = query.trim().lowercase().removePrefix("/")
    if (q.isEmpty()) return commands

    val prefix = mutableListOf<SlashCommand>()
    val substring = mutableListOf<SlashCommand>()
    val seen = mutableSetOf<SlashCommand>()

    for (command in commands) {
        val name = command.name.lowercase().removePrefix("/")
        val description = command.description.lowercase()
        when {
            name.startsWith(q) -> {
                if (seen.add(command)) prefix += command
            }
            name.contains(q) || description.contains(q) -> {
                if (seen.add(command)) substring += command
            }
        }
    }
    return prefix + substring
}
