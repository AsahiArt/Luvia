package tech.asahiart.luvia.command

import tech.asahiart.luvia.AgentKey

public enum class KeyBarKey {
    Esc,
    Tab,
    ShiftTab,
    CtrlC,
    Up,
    Down,
    Enter,
    ;

    /**
     * Wire payloads for [tech.asahiart.luvia.Client.sendAgentKeys] / [AgentKey.wire].
     * [ShiftTab] sends the CSI back-tab sequence `\u001B[Z` (no named `backtab` on [AgentKey]).
     */
    public fun toAgentKeys(): List<AgentKey> =
        when (this) {
            Esc -> listOf(AgentKey.ESC)
            Tab -> listOf(AgentKey.TAB)
            ShiftTab ->
                listOf(
                    AgentKey.Char('\u001B'),
                    AgentKey.Char('['),
                    AgentKey.Char('Z'),
                )
            CtrlC -> listOf(AgentKey.Ctrl('c'))
            Up -> listOf(AgentKey.UP)
            Down -> listOf(AgentKey.DOWN)
            Enter -> listOf(AgentKey.ENTER)
        }
}
