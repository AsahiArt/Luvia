package tech.asahiart.luvia

import tech.asahiart.luvia.internal.Methods

/**
 * UHP method names the phone surface depends on, for `LuviaSession.supports`.
 * Gate features on these, never on a Host version string (ADR 0001).
 */
public object UhpMethods {
    public const val AGENT_PROMPT: String = Methods.AGENT_PROMPT
    public const val AGENT_READ: String = Methods.AGENT_READ
    public const val AGENT_KEYS: String = Methods.AGENT_KEYS
    public const val MISSION_SNAPSHOT: String = Methods.MISSION_SNAPSHOT
    public const val DIFF_LIST: String = Methods.DIFF_LIST
    public const val DIFF_GET: String = Methods.DIFF_GET
    public const val DIFF_NOTE_LIST: String = Methods.DIFF_NOTE_LIST
    public const val DIFF_NOTE_ADD: String = Methods.DIFF_NOTE_ADD
    public const val DIFF_NOTE_SEND: String = Methods.DIFF_NOTE_SEND
    public const val GIT_STATUS: String = Methods.GIT_STATUS
    public const val GIT_LOG: String = Methods.GIT_LOG
    public const val TASK_LIST: String = Methods.TASK_LIST
    public const val TASK_ADD: String = Methods.TASK_ADD
    public const val TASK_DONE: String = Methods.TASK_DONE
    public const val TERMINAL_OBSERVE: String = Methods.TERMINAL_OBSERVE
    public const val TERMINAL_CONTROL: String = Methods.TERMINAL_CONTROL
}
