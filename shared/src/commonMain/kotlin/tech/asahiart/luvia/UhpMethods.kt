package tech.asahiart.luvia

import tech.asahiart.luvia.internal.Methods

/**
 * UHP method names the phone surface depends on, for `LuviaSession.supports`.
 * Gate features on these, never on a Host version string (ADR 0001).
 */
public object UhpMethods {
    public const val AGENT_LIST: String = Methods.AGENT_LIST
    public const val AGENT_GET: String = Methods.AGENT_GET
    public const val DIFF_NOTE_EDIT: String = Methods.DIFF_NOTE_EDIT
    public const val DIFF_NOTE_RESOLVE: String = Methods.DIFF_NOTE_RESOLVE
    public const val DIFF_NOTE_REOPEN: String = Methods.DIFF_NOTE_REOPEN
    public const val DIFF_NOTE_REMOVE: String = Methods.DIFF_NOTE_REMOVE
    public const val TASK_GET: String = Methods.TASK_GET
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
    public const val TASK_CLAIM: String = "task.claim"
    public const val TASK_DELETE: String = "task.delete"
    public const val TERMINAL_OBSERVE: String = Methods.TERMINAL_OBSERVE
    public const val TERMINAL_CONTROL: String = Methods.TERMINAL_CONTROL
    public const val AGENT_SESSIONS: String = Methods.AGENT_SESSIONS
    public const val AGENT_RESUME: String = "agent.resume"
    public const val AGENT_FORK: String = "agent.fork"
    public const val AGENT_NAME: String = "agent.name"
    public const val FILES_TREE: String = "files.tree"
    public const val FILES_OPEN: String = "files.open"
    public const val FILES_REVEAL: String = "files.reveal"
    public const val FILES_REFRESH: String = "files.refresh"
    public const val SEARCH_QUERY: String = "search.query"
    public const val SEARCH_ACTIVATE: String = "search.activate"
    public const val WORKTREE_LIST: String = "worktree.list"
    public const val WORKTREE_CREATE: String = "worktree.create"
    public const val WORKTREE_OPEN: String = "worktree.open"
    public const val WORKTREE_REMOVE: String = "worktree.remove"
    public const val AUTOMATION_LIST: String = "automation.list"
    public const val AUTOMATION_ENABLE: String = "automation.enable"
    public const val AUTOMATION_DISABLE: String = "automation.disable"
    public const val AUTOMATION_RUN: String = "automation.run"
    public const val AUTOMATION_HEALTH: String = "automation.health"
    public const val WORKSPACE_LIST: String = Methods.WORKSPACE_LIST
    public const val WORKSPACE_FOCUS: String = Methods.WORKSPACE_FOCUS
    public const val WORKSPACE_CLOSE: String = "workspace.close"
    public const val PANE_LIST: String = "pane.list"
    public const val PANE_FOCUS: String = "pane.focus"
    public const val PANE_CLOSE: String = "pane.close"
    public const val PANE_RENAME: String = "pane.rename"
    public const val ACP_AGENTS: String = Methods.ACP_AGENTS
    public const val ACP_SESSION_OPEN: String = Methods.ACP_SESSION_OPEN
}
