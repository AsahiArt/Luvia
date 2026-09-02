package tech.asahiart.luvia.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.AgentAuthorityLease
import tech.asahiart.luvia.AgentExplainResult
import tech.asahiart.luvia.AgentExplainSession
import tech.asahiart.luvia.AgentGetResult
import tech.asahiart.luvia.AgentIdentityEvidence
import tech.asahiart.luvia.AgentListResult
import tech.asahiart.luvia.AgentPromptResult
import tech.asahiart.luvia.AgentReadResult
import tech.asahiart.luvia.AgentReadSource
import tech.asahiart.luvia.AgentSessionEntry
import tech.asahiart.luvia.AgentStateEvidence
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.BusEvent
import tech.asahiart.luvia.Capabilities
import tech.asahiart.luvia.DiffFile
import tech.asahiart.luvia.DiffHunk
import tech.asahiart.luvia.DiffLayer
import tech.asahiart.luvia.DiffLine
import tech.asahiart.luvia.DiffListResult
import tech.asahiart.luvia.EventSubscriptionAck
import tech.asahiart.luvia.GitCommit
import tech.asahiart.luvia.GitFileChange
import tech.asahiart.luvia.GitStatus
import tech.asahiart.luvia.Luvia
import tech.asahiart.luvia.MissionRow
import tech.asahiart.luvia.MissionRowKind
import tech.asahiart.luvia.MissionScope
import tech.asahiart.luvia.MissionSnapshot
import tech.asahiart.luvia.MissionSummary
import tech.asahiart.luvia.MissionUsage
import tech.asahiart.luvia.PaneSplitResult
import tech.asahiart.luvia.PaneSummary
import tech.asahiart.luvia.ProcessIdentity
import tech.asahiart.luvia.ReviewNote
import tech.asahiart.luvia.ReviewNoteDelivery
import tech.asahiart.luvia.ReviewNoteKind
import tech.asahiart.luvia.ReviewNoteSendResult
import tech.asahiart.luvia.ReviewNoteState
import tech.asahiart.luvia.SessionSnapshot
import tech.asahiart.luvia.SplitDirection
import tech.asahiart.luvia.Task
import tech.asahiart.luvia.TaskDoneResult
import tech.asahiart.luvia.TaskHeartbeatResult
import tech.asahiart.luvia.TaskListResult
import tech.asahiart.luvia.TaskMutationResult
import tech.asahiart.luvia.TaskNextResult
import tech.asahiart.luvia.TaskStartResult
import tech.asahiart.luvia.TaskStatus
import tech.asahiart.luvia.TaskSummary
import tech.asahiart.luvia.TerminalBackendSnapshot
import tech.asahiart.luvia.TerminalCaptureMode
import tech.asahiart.luvia.TerminalCaptureResult
import tech.asahiart.luvia.TerminalFrame
import tech.asahiart.luvia.TerminalIdentity
import tech.asahiart.luvia.TerminalInventoryEntry
import tech.asahiart.luvia.TerminalInventoryResult
import tech.asahiart.luvia.TerminalTabRef
import tech.asahiart.luvia.TerminalWorkspaceRef
import tech.asahiart.luvia.WorkspaceGetResult
import tech.asahiart.luvia.WorkspaceListEntry
import tech.asahiart.luvia.WorkspaceListResult
import tech.asahiart.luvia.WorkspaceOpenResult
import tech.asahiart.luvia.WorkspaceSummary
import tech.asahiart.luvia.WorkspaceWorker

internal object Methods {
    const val CAPABILITIES: String = "uhp.capabilities"
    const val SNAPSHOT: String = "session.snapshot"
    const val EVENTS_SUBSCRIBE: String = "events.subscribe"
    const val WORKSPACE_LIST: String = "workspace.list"
    const val WORKSPACE_GET: String = "workspace.get"
    const val WORKSPACE_OPEN: String = "workspace.open"
    const val WORKSPACE_FOCUS: String = "workspace.focus"
    const val PANE_SPLIT: String = "pane.split"
    const val AGENT_LIST: String = "agent.list"
    const val AGENT_GET: String = "agent.get"
    const val AGENT_EXPLAIN: String = "agent.explain"
    const val AGENT_START: String = "agent.start"
    const val AGENT_PROMPT: String = "agent.prompt"
    const val AGENT_READ: String = "agent.read"
    const val AGENT_KEYS: String = "agent.keys"
    const val AGENT_SESSIONS: String = "agent.sessions"
    const val MISSION_SNAPSHOT: String = "mission.snapshot"
    const val DIFF_LIST: String = "diff.list"
    const val DIFF_GET: String = "diff.get"
    const val DIFF_REFRESH: String = "diff.refresh"
    const val DIFF_NOTE_LIST: String = "diff.note.list"
    const val DIFF_NOTE_ADD: String = "diff.note.add"
    const val DIFF_NOTE_EDIT: String = "diff.note.edit"
    const val DIFF_NOTE_RESOLVE: String = "diff.note.resolve"
    const val DIFF_NOTE_REOPEN: String = "diff.note.reopen"
    const val DIFF_NOTE_REMOVE: String = "diff.note.remove"
    const val DIFF_NOTE_SEND: String = "diff.note.send"
    const val GIT_STATUS: String = "git.status"
    const val GIT_LOG: String = "git.log"
    const val TASK_LIST: String = "task.list"
    const val TASK_GET: String = "task.get"
    const val TASK_ADD: String = "task.add"
    const val TASK_NEXT: String = "task.next"
    const val TASK_START: String = "task.start"
    const val TASK_HEARTBEAT: String = "task.heartbeat"
    const val TASK_DONE: String = "task.done"
    const val TERMINAL_INVENTORY: String = "terminal.backend.inventory"
    const val TERMINAL_SNAPSHOT: String = "terminal.backend.snapshot"
    const val TERMINAL_CAPTURE: String = "terminal.backend.capture"
    const val TERMINAL_OBSERVE: String = "terminal.backend.observe"
    const val TERMINAL_CONTROL: String = "terminal.backend.control"
    const val TERMINAL_TYPE: String = "terminal.backend.type_literal"
    const val TERMINAL_SUBMIT: String = "terminal.backend.submit_text"
    const val TERMINAL_KEY: String = "terminal.backend.send_key"

    val MUTATIONS: Set<String> =
        setOf(
            WORKSPACE_OPEN,
            WORKSPACE_FOCUS,
            PANE_SPLIT,
            AGENT_START,
            AGENT_PROMPT,
            AGENT_KEYS,
            TASK_ADD,
            TASK_NEXT,
            TASK_START,
            TASK_HEARTBEAT,
            TASK_DONE,
            DIFF_REFRESH,
            DIFF_NOTE_ADD,
            DIFF_NOTE_EDIT,
            DIFF_NOTE_RESOLVE,
            DIFF_NOTE_REOPEN,
            DIFF_NOTE_REMOVE,
            DIFF_NOTE_SEND,
            TERMINAL_CONTROL,
            TERMINAL_TYPE,
            TERMINAL_SUBMIT,
            TERMINAL_KEY,
        )

    val REVISION_GUARDED: Set<String> =
        setOf(
            WORKSPACE_OPEN,
            WORKSPACE_FOCUS,
            PANE_SPLIT,
            TASK_ADD,
            TASK_START,
            TASK_HEARTBEAT,
            TASK_DONE,
        )
}

internal fun subscribeParams(afterSequence: Long?): JsonObject {
    if (afterSequence == null) return JsonObject(emptyMap())
    return buildJsonObject { put("after_sequence", afterSequence) }
}

internal fun withIfRevision(params: JsonObject, ifRevision: Long?): JsonObject {
    if (ifRevision == null) return params
    return buildJsonObject {
        params.forEach { (key, value) -> put(key, value) }
        put("if_revision", ifRevision)
    }
}

internal fun SplitDirection.wireName(): String =
    when (this) {
        SplitDirection.Right -> "right"
        SplitDirection.Down -> "down"
        SplitDirection.Stack -> "stack"
    }

internal fun AgentReadSource.wireName(): String =
    when (this) {
        AgentReadSource.VISIBLE -> "visible"
        AgentReadSource.RECENT -> "recent"
    }

internal fun MissionScope.wireName(): String =
    when (this) {
        MissionScope.ALL -> "all"
        MissionScope.WORKSPACE -> "workspace"
    }

internal fun DiffLayer.wireName(): String =
    when (this) {
        DiffLayer.STAGED -> "staged"
        DiffLayer.WORKTREE -> "worktree"
        DiffLayer.UNTRACKED -> "untracked"
        DiffLayer.CONFLICT -> "conflict"
    }

internal fun ReviewNoteState.wireName(): String =
    when (this) {
        ReviewNoteState.OPEN -> "open"
        ReviewNoteState.RESOLVED -> "resolved"
        ReviewNoteState.OUTDATED -> "outdated"
        ReviewNoteState.ORPHANED -> "orphaned"
    }

internal fun ReviewNoteKind.wireName(): String =
    when (this) {
        ReviewNoteKind.QUESTION -> "question"
        ReviewNoteKind.ISSUE -> "issue"
        ReviewNoteKind.SUGGESTION -> "suggestion"
        ReviewNoteKind.PRAISE -> "praise"
    }

internal fun AgentStatus.wireName(): String =
    when (this) {
        AgentStatus.Idle -> "idle"
        AgentStatus.Working -> "working"
        AgentStatus.Blocked -> "blocked"
        AgentStatus.Done -> "done"
        AgentStatus.Unknown -> "unknown"
    }

internal fun mapCapabilities(result: JsonObject): Capabilities {
    val protocol = result.optionalObject("protocol")
        ?: throw CodecException(CodecException.Kind.Schema, "capabilities missing protocol")
    val name = protocol.string("name")
    val major = protocol.strictLong("major").toInt()
    val minor = protocol.optionalStrictLong("minor")?.toInt() ?: 0
    if (name != Luvia.protocolName || major != Luvia.protocolMajor) {
        throw UnknownMajorException(name, major)
    }
    val methods =
        if ("methods" in result && result["methods"] !is JsonNull) {
            result.stringList("methods")
        } else {
            emptyList()
        }
    val agentStates =
        if ("agent_states" in result && result["agent_states"] !is JsonNull) {
            result.stringList("agent_states")
        } else {
            emptyList()
        }
    return Capabilities(
        protocolName = name,
        protocolMajor = major,
        protocolMinor = minor,
        methods = methods,
        sessionName = result.optionalString("session"),
        eventSequence = result.optionalStrictLong("event_sequence") ?: 0L,
        serverGeneration = result.optionalString("server_generation"),
        agentStates = agentStates,
    )
}

internal fun mapSnapshot(result: JsonObject): SessionSnapshot {
    val protocol = result.optionalObject("protocol")
    if (protocol != null) {
        val name = protocol.string("name")
        val major = protocol.strictLong("major").toInt()
        if (name != Luvia.protocolName || major != Luvia.protocolMajor) {
            throw UnknownMajorException(name, major)
        }
    }
    val workspaces = ArrayList<WorkspaceSummary>()
    val panes = ArrayList<PaneSummary>()
    val agents = ArrayList<AgentSummary>()
    val workspaceArray = result["workspaces"] as? JsonArray ?: JsonArray(emptyList())
    workspaceArray.forEachIndexed { fallbackIndex, el ->
        val ws = el as? JsonObject ?: return@forEachIndexed
        val index = ws.optionalStrictLong("index")?.toInt() ?: (fallbackIndex + 1)
        val tabs = ws["tabs"] as? JsonArray
        workspaces +=
            WorkspaceSummary(
                index = index,
                name = ws.optionalString("name") ?: "",
                pinned = ws.booleanOrFalse("pinned"),
                active = ws.booleanOrFalse("active"),
                tabCount = tabs?.size ?: 0,
                branch = ws.optionalString("branch"),
                cwd = ws.optionalString("cwd"),
            )
        tabs?.forEach { tabEl ->
            val tab = tabEl as? JsonObject ?: return@forEach
            val paneArray = tab["panes"] as? JsonArray ?: return@forEach
            paneArray.forEach { paneEl ->
                val pane = paneEl as? JsonObject ?: return@forEach
                val paneId = pane.optionalString("pane_id") ?: return@forEach
                val root = pane.optionalObject("root_process")
                panes +=
                    PaneSummary(
                        paneId = paneId,
                        terminalId = pane.optionalString("terminal_id"),
                        kind = pane.optionalString("kind") ?: "terminal",
                        focused = pane.booleanOrFalse("focused"),
                        cwd = pane.optionalString("cwd"),
                        contentRevision = pane.optionalStrictLong("content_revision"),
                        agentAuthority = pane.optionalString("agent_authority"),
                        agentSession = pane.optionalString("agent_session"),
                        rootProcessPid = root?.optionalStrictLong("pid"),
                        rootProcessStartMarker = root?.optionalString("start_marker"),
                    )
                val agentKind = pane.optionalString("agent")
                val agentStatus = pane.optionalString("agent_status")
                // Match agent.list (dispatch.rs "Only real agent sessions, not the
                // shells behind tabs"): a pane whose agent is a shell is not an
                // Agent unless Luvus has bound a session to it.
                val isAgentPane =
                    (agentKind != null || agentStatus != null) &&
                        (!agentKind.isShellIdentity() || pane.optionalString("agent_session") != null)
                if (isAgentPane) {
                    agents +=
                        AgentSummary(
                            paneId = paneId,
                            name = pane.optionalString("name"),
                            status = parseAgentStatus(agentStatus),
                            agent = agentKind,
                            authority = pane.optionalString("agent_authority"),
                            session = pane.optionalString("agent_session"),
                            focused = pane.booleanOrFalse("focused"),
                            cwd = pane.optionalString("cwd"),
                        )
                }
            }
        }
    }
    return SessionSnapshot(
        sessionName = result.optionalString("session") ?: "",
        serverGeneration = result.optionalString("server_generation") ?: "",
        eventSequence = result.optionalStrictLong("event_sequence") ?: 0L,
        workspaces = workspaces,
        panes = panes,
        agents = agents,
    )
}

internal fun mapWorkspaceList(result: JsonObject): WorkspaceListResult =
    WorkspaceListResult(
        workspaces = result.optionalObjectList("workspaces").map { mapWorkspaceListEntry(it) },
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapWorkspaceListAsSummaries(result: JsonObject): List<WorkspaceSummary> =
    mapWorkspaceList(result).workspaces.map { entry ->
        WorkspaceSummary(
            index = entry.workspace.toIntOrNull() ?: 0,
            name = entry.name,
            pinned = entry.pinned,
            active = entry.active,
            tabCount = entry.tabs,
            cwd = entry.cwd,
        )
    }

internal fun mapWorkspaceGet(result: JsonObject): WorkspaceGetResult =
    WorkspaceGetResult(
        workspace = result.optionalWireString("workspace") ?: "",
        workspaceId = result.optionalString("workspace_id"),
        name = result.optionalString("name") ?: "",
        active = result.booleanOrFalse("active"),
        activeTab = result.optionalWireString("active_tab"),
        tabs = result.optionalStrictLong("tabs")?.toInt() ?: 0,
        pinned = result.booleanOrFalse("pinned"),
        branch = result.optionalString("branch"),
        ahead = result.optionalStrictLong("ahead"),
        behind = result.optionalStrictLong("behind"),
        cwd = result.optionalString("cwd"),
        terminalCwd = result.optionalString("terminal_cwd"),
        displayPosition = result.optionalWireString("display_position"),
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapWorkspaceOpen(result: JsonObject): WorkspaceOpenResult =
    WorkspaceOpenResult(
        workspace = result.optionalWireString("workspace") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapPaneSplit(result: JsonObject): PaneSplitResult =
    PaneSplitResult(
        pane = result.optionalWireString("pane") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapAgentList(result: JsonObject): AgentListResult =
    AgentListResult(
        agents = result.optionalObjectList("agents").map { mapAgentSummary(it) },
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapAgentGet(result: JsonObject): AgentGetResult =
    AgentGetResult(
        pane = result.optionalWireString("pane") ?: "",
        name = result.optionalString("name"),
        agent = result.optionalString("agent"),
        status = parseAgentStatus(result.optionalString("status")),
        authority = result.optionalString("authority"),
        stateSource = result.optionalString("state_source"),
        session = result.optionalString("session"),
        cwd = result.optionalString("cwd"),
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapAgentExplain(result: JsonObject): AgentExplainResult {
    val authority = result["authority"]
    val authorityLease =
        when (authority) {
            null, is JsonNull, is JsonPrimitive -> null
            is JsonObject ->
                AgentAuthorityLease(
                    source = authority.optionalString("source"),
                    sequence = authority.optionalStrictLong("sequence"),
                    message = authority.optionalString("message"),
                    expiresInMs = authority.optionalStrictLong("expires_in_ms"),
                )
            else -> null
        }
    val sessionEl = result["session"]
    val session =
        when (sessionEl) {
            null, is JsonNull -> null
            is JsonObject ->
                AgentExplainSession(
                    agent = sessionEl.optionalString("agent"),
                    id = sessionEl.optionalString("id"),
                )
            is JsonPrimitive -> AgentExplainSession(agent = null, id = sessionEl.content)
            else -> null
        }
    val identity = result.optionalObject("identity")
    val evidence = result.optionalObject("state_evidence")
    return AgentExplainResult(
        pane = result.optionalWireString("pane") ?: "",
        agent = result.optionalString("agent"),
        status = parseAgentStatus(result.optionalString("status")),
        available = result.booleanOrFalse("available"),
        authority = authorityLease,
        session = session,
        identity =
            identity?.let {
                AgentIdentityEvidence(
                    confidence = it.optionalString("confidence"),
                    source = it.optionalString("source"),
                )
            },
        stateEvidence =
            evidence?.let {
                AgentStateEvidence(
                    source = it.optionalString("source"),
                    confidence = it.optionalString("confidence"),
                    blockedHint = it.optionalString("blocked_hint"),
                    rulePriority = it.optionalStrictLong("rule_priority"),
                    ruleRegion = it.optionalString("rule_region"),
                )
            },
        revision = result.optionalStrictLong("revision"),
    )
}

internal fun mapAgentRead(result: JsonObject): AgentReadResult =
    AgentReadResult(
        pane = result.optionalWireString("pane") ?: "",
        text = result.optionalString("text") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapAgentPrompt(result: JsonObject): AgentPromptResult =
    AgentPromptResult(
        pane = result.optionalWireString("pane") ?: "",
        submitted = result.booleanOrFalse("submitted"),
        matched = result.booleanOrFalse("matched"),
        status = result.optionalString("status")?.let { parseAgentStatus(it) },
        baselineRevision = result.optionalStrictLong("baseline_revision") ?: 0L,
        contentRevision = result.optionalStrictLong("content_revision") ?: 0L,
        evidence = result.optionalString("evidence") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapAgentSessions(result: JsonObject): List<AgentSessionEntry> =
    result.optionalObjectList("sessions").mapNotNull { obj ->
        val agent = obj.optionalString("agent") ?: return@mapNotNull null
        val sessionId = obj.optionalString("session_id") ?: return@mapNotNull null
        val cwd = obj.optionalString("cwd") ?: return@mapNotNull null
        AgentSessionEntry(agent = agent, sessionId = sessionId, cwd = cwd)
    }

internal fun mapMissionSnapshot(result: JsonObject): MissionSnapshot {
    val summaryObj = result.optionalObject("summary")
    val summary =
        MissionSummary(
            agents = summaryObj?.optionalStrictLong("agents") ?: 0L,
            tokens = summaryObj?.optionalStrictLong("tokens") ?: 0L,
            costUsd = summaryObj?.optionalDouble("cost_usd") ?: 0.0,
            burnUsdPerHour = summaryObj?.optionalDouble("burn_usd_per_hour") ?: 0.0,
        )
    val rows =
        result.optionalObjectList("rows").mapNotNull { obj ->
            val kind =
                when (obj.optionalString("kind")) {
                    "live" -> MissionRowKind.LIVE
                    "resumable" -> MissionRowKind.RESUMABLE
                    else -> return@mapNotNull null
                }
            val usageObj = obj.optionalObject("usage")
            MissionRow(
                kind = kind,
                pane = obj.optionalWireString("pane"),
                agent = obj.optionalString("agent"),
                state = obj.optionalString("state"),
                workspace = obj.optionalWireString("workspace"),
                workspaceId = obj.optionalString("workspace_id"),
                workspaceName = obj.optionalString("workspace_name"),
                tab = obj.optionalWireString("tab"),
                location = obj.optionalString("location"),
                usage =
                    usageObj?.let {
                        MissionUsage(
                            model = it.optionalString("model"),
                            tokensIn = it.optionalStrictLong("tokens_in"),
                            tokensOut = it.optionalStrictLong("tokens_out"),
                            cacheTokens = it.optionalStrictLong("cache_tokens"),
                            totalTokens = it.optionalStrictLong("total_tokens"),
                            context = it.optionalDouble("context"),
                            costUsd = it.optionalDouble("cost_usd"),
                        )
                    },
            )
        }
    return MissionSnapshot(
        scope = result.optionalString("scope"),
        workspace = result.optionalWireString("workspace"),
        workspaceId = result.optionalString("workspace_id"),
        refreshing = result.booleanOrFalse("refreshing"),
        summary = summary,
        rows = rows,
    )
}

internal fun mapDiffList(result: JsonObject): DiffListResult =
    DiffListResult(
        repo = result.optionalString("repo"),
        branch = result.optionalString("branch"),
        generation = result.optionalStrictLong("generation"),
        fingerprint = result.optionalString("fingerprint"),
        omitted = result.optionalStrictLong("omitted"),
        refreshing = result.booleanOrFalse("refreshing"),
        files = result.optionalObjectList("files").map { mapDiffFileMeta(it) },
    )

internal fun mapDiffGet(result: JsonObject): DiffFile {
    val file = result.optionalObject("file")?.let { mapDiffFileMeta(it) } ?: mapDiffFileMeta(result)
    return file.copy(
        additions = result.optionalStrictLong("additions") ?: file.additions,
        deletions = result.optionalStrictLong("deletions") ?: file.deletions,
        binary = result.optionalBoolean("binary") ?: file.binary,
        truncated = result.optionalBoolean("truncated"),
        omittedLines = result.optionalStrictLong("omitted_lines"),
        hunks = mapDiffHunks(result),
    )
}

internal fun mapReviewNotes(result: JsonObject): List<ReviewNote> =
    result.optionalObjectList("notes").mapNotNull { mapReviewNote(it) }

internal fun mapReviewNoteResult(result: JsonObject): ReviewNote {
    val note = result.optionalObject("note")?.let { mapReviewNote(it) }
        ?: mapReviewNote(result)
        ?: throw CodecException(CodecException.Kind.Schema, "review note payload missing")
    return note
}

internal fun mapReviewNoteSend(result: JsonObject): ReviewNoteSendResult =
    ReviewNoteSendResult(
        pane = result.optionalWireString("pane"),
        target = result.optionalString("target"),
        count = result.optionalStrictLong("count") ?: 0L,
    )

internal fun mapGitStatus(result: JsonObject): GitStatus =
    GitStatus(
        branch = result.optionalString("branch"),
        upstream = result.optionalString("upstream"),
        ahead = result.optionalStrictLong("ahead"),
        behind = result.optionalStrictLong("behind"),
        staged = result.optionalObjectList("staged").mapNotNull { mapGitFileChange(it) },
        unstaged = result.optionalObjectList("unstaged").mapNotNull { mapGitFileChange(it) },
        untracked = result.optionalStringList("untracked"),
        stashes = result.optionalStringList("stashes"),
    )

internal fun mapGitLog(result: JsonObject): List<GitCommit> =
    result.optionalObjectList("commits").mapNotNull { obj ->
        val sha = obj.optionalString("sha") ?: return@mapNotNull null
        GitCommit(
            sha = sha,
            subject = obj.optionalString("subject") ?: "",
            author = obj.optionalString("author"),
            whenText = obj.optionalString("when"),
            refs = obj.optionalString("refs"),
        )
    }


internal fun mapTaskList(result: JsonObject): TaskListResult =
    TaskListResult(
        tasks = result.optionalObjectList("tasks").mapNotNull { mapTask(it) },
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapTaskSummaries(result: JsonObject): List<TaskSummary> =
    mapTaskList(result).tasks.map { it.toSummary() }

internal fun mapTaskMutation(result: JsonObject): TaskMutationResult {
    val task = result.optionalObject("task")?.let { mapTask(it) }
        ?: throw CodecException(CodecException.Kind.Schema, "task payload missing")
    return TaskMutationResult(task = task, revision = result.optionalStrictLong("revision"))
}

internal fun mapTaskStart(result: JsonObject): TaskStartResult {
    val task = result.optionalObject("task")?.let { mapTask(it) }
        ?: throw CodecException(CodecException.Kind.Schema, "task payload missing")
    return TaskStartResult(
        task = task,
        pane = result.optionalWireString("pane"),
        worktree = result.optionalString("worktree"),
        revision = result.optionalStrictLong("revision"),
    )
}

internal fun mapTaskDone(result: JsonObject): TaskDoneResult {
    val task = result.optionalObject("task")?.let { mapTask(it) }
        ?: throw CodecException(CodecException.Kind.Schema, "task payload missing")
    return TaskDoneResult(
        task = task,
        gateRunning = result.booleanOrFalse("gate_running"),
        revision = result.optionalStrictLong("revision"),
    )
}

internal fun mapTaskHeartbeat(result: JsonObject): TaskHeartbeatResult =
    TaskHeartbeatResult(
        overThreshold = result.booleanOrFalse("over_threshold"),
        revision = result.optionalStrictLong("revision"),
    )

internal fun mapTaskNext(result: JsonObject): TaskNextResult {
    val type = result.optionalString("type")
    val revision = result.optionalStrictLong("revision")
    return if (type == "none") {
        TaskNextResult.None(
            message = result.optionalString("message") ?: "",
            revision = revision,
        )
    } else {
        val task = result.optionalObject("task")?.let { mapTask(it) }
            ?: throw CodecException(CodecException.Kind.Schema, "task.next missing task")
        TaskNextResult.Ready(
            task = task,
            pane = result.optionalWireString("pane"),
            worktree = result.optionalString("worktree"),
            revision = revision,
        )
    }
}

internal fun mapTerminalInventory(result: JsonObject): TerminalInventoryResult =
    TerminalInventoryResult(
        serverGeneration = result.optionalString("server_generation") ?: "",
        terminals = result.optionalObjectList("terminals").mapNotNull { mapTerminalEntry(it) },
        truncated = result.booleanOrFalse("truncated"),
    )

internal fun mapTerminalBackendSnapshot(result: JsonObject): TerminalBackendSnapshot =
    TerminalBackendSnapshot(
        serverGeneration = result.optionalString("server_generation") ?: "",
        eventSequence = result.optionalStrictLong("event_sequence") ?: 0L,
        terminals = result.optionalObjectList("terminals").mapNotNull { mapTerminalEntry(it) },
        truncated = result.booleanOrFalse("truncated"),
    )

internal fun mapTerminalCapture(result: JsonObject, identity: TerminalIdentity): TerminalCaptureResult {
    val mode =
        when (result.optionalString("mode")) {
            "recent_unwrapped" -> TerminalCaptureMode.RecentUnwrapped
            else -> TerminalCaptureMode.Visible
        }
    return TerminalCaptureResult(
        identity = identity,
        text = result.optionalString("text") ?: "",
        lines = result.optionalStrictLong("lines")?.toInt() ?: 0,
        bytes = result.optionalStrictLong("bytes")?.toInt() ?: 0,
        mode = mode,
        ansi = result.booleanOrFalse("ansi"),
        truncated = result.booleanOrFalse("truncated"),
        contentRevision = result.optionalStrictLong("content_revision") ?: 0L,
    )
}

internal fun mapTerminalCaptureFrame(result: JsonObject, identity: TerminalIdentity): TerminalFrame {
    val mapped = mapTerminalCapture(result, identity)
    return TerminalFrame(
        identity = mapped.identity,
        contentRevision = mapped.contentRevision,
        mode = mapped.mode,
        ansi = mapped.ansi,
        text = mapped.text,
        lines = mapped.lines,
        bytes = mapped.bytes,
        truncated = mapped.truncated,
    )
}

internal fun mapSubscribeAck(result: JsonObject): EventSubscriptionAck =
    EventSubscriptionAck(
        sequence = result.optionalStrictLong("sequence") ?: 0L,
        replayed = result.optionalStrictLong("replayed"),
        queueCapacity = result.optionalStrictLong("queue_capacity"),
        lossBehavior = result.optionalString("loss_behavior"),
    )

internal fun parseAgentStatus(raw: String?): AgentStatus =
    when (raw) {
        "idle" -> AgentStatus.Idle
        "working" -> AgentStatus.Working
        "blocked" -> AgentStatus.Blocked
        "done" -> AgentStatus.Done
        else -> AgentStatus.Unknown
    }

internal fun parseTaskStatus(raw: String?): TaskStatus =
    when (raw) {
        "queued" -> TaskStatus.Queued
        "claimed" -> TaskStatus.Claimed
        "running" -> TaskStatus.Running
        "blocked" -> TaskStatus.Blocked
        "review" -> TaskStatus.Review
        "done" -> TaskStatus.Done
        "merging" -> TaskStatus.Merging
        "merged" -> TaskStatus.Merged
        "failed" -> TaskStatus.Failed
        else -> TaskStatus.Unknown
    }

internal fun Task.toSummary(): TaskSummary =
    TaskSummary(id = id, title = title, status = status.wireName())

internal fun TaskStatus.wireName(): String =
    when (this) {
        TaskStatus.Queued -> "queued"
        TaskStatus.Claimed -> "claimed"
        TaskStatus.Running -> "running"
        TaskStatus.Blocked -> "blocked"
        TaskStatus.Review -> "review"
        TaskStatus.Done -> "done"
        TaskStatus.Merging -> "merging"
        TaskStatus.Merged -> "merged"
        TaskStatus.Failed -> "failed"
        TaskStatus.Unknown -> "unknown"
    }

internal fun parseBusEvent(event: UhpEvent): BusEvent {
    val data = event.data
    return when (event.name) {
        "events.resync_required", "terminal.resync_required" ->
            BusEvent.ResyncRequired(
                sequence = event.sequence,
                name = event.name,
                reason = data.optionalString("reason"),
            )
        "pane.agent_status_changed" ->
            BusEvent.AgentStatusChanged(
                sequence = event.sequence,
                pane = data.optionalWireString("pane"),
                status = parseAgentStatus(data.optionalString("status")),
                agent = data.optionalString("agent"),
                cwd = data.optionalString("cwd"),
                project = data.optionalString("project"),
                branch = data.optionalString("branch"),
                authority = data.optionalString("authority"),
                stateSource = data.optionalString("state_source"),
            )
        "task.added", "task.claimed", "task.updated", "task.done", "task.released",
        "task.deleted", "task.started", "task.ready", "task.needs_compaction",
        "task.gate_running", "task.gate_passed", "task.gate_failed",
        "task.merged", "task.merge_conflict", "task.merge_started", "task.merge_failed",
        ->
            BusEvent.TaskPayload(
                sequence = event.sequence,
                name = event.name,
                task = mapTask(data) ?: data.optionalObject("task")?.let { mapTask(it) },
                id = data.optionalString("id") ?: data.optionalObject("task")?.optionalString("id"),
                pane = data.optionalWireString("pane"),
                worktree = data.optionalString("worktree"),
                branch = data.optionalString("branch"),
                context = data.optionalDouble("context"),
                gate = data.optionalString("gate"),
                code = data.optionalStrictLong("code"),
                files = data.optionalStringList("files"),
                into = data.optionalString("into"),
                mode = data.optionalString("mode"),
                workspaceId = data.optionalString("workspace_id"),
                tabId = data.optionalString("tab_id"),
                cwd = data.optionalString("cwd"),
                commit = data.optionalString("commit"),
                message = data.optionalString("message"),
            )
        "pane.created", "pane.closed", "pane.focused", "pane.moved", "pane.forked" ->
            BusEvent.PaneChanged(
                sequence = event.sequence,
                name = event.name,
                pane = data.optionalWireString("pane"),
                terminalId = data.optionalString("terminal_id"),
                workspace = data.optionalWireString("workspace"),
                tab = data.optionalWireString("tab"),
                from = data.optionalWireString("from"),
                to = data.optionalWireString("to"),
                module = data.optionalString("module"),
            )
        "pane.renamed" ->
            BusEvent.PaneRenamed(
                sequence = event.sequence,
                pane = data.optionalWireString("pane"),
                name = data.optionalString("name"),
            )
        "agent.hook" ->
            BusEvent.AgentHook(
                sequence = event.sequence,
                pane = data.optionalWireString("pane"),
                agent = data.optionalString("agent"),
                kind = data.optionalString("kind"),
                message = data.optionalString("message"),
                tool = data.optionalString("tool"),
            )
        "lease.acquired", "lease.released" ->
            BusEvent.LeaseChanged(
                sequence = event.sequence,
                name = event.name,
                id = data.optionalString("id"),
                pane = data.optionalWireString("pane"),
                task = data.optionalWireString("task"),
                paths = data.optionalStringList("paths"),
                acquired = data.optionalStrictLong("acquired"),
                leases = data.optionalStringList("leases"),
            )
        "workspace.created", "workspace.closed", "workspace.moved" ->
            BusEvent.WorkspaceChanged(
                sequence = event.sequence,
                name = event.name,
                workspace = data.optionalWireString("workspace"),
                to = data.optionalWireString("to"),
            )
        "terminal.created", "terminal.moved", "terminal.metadata_changed",
        "terminal.output_ready", "terminal.exited", "terminal.closed",
        ->
            BusEvent.TerminalChanged(
                sequence = event.sequence,
                name = event.name,
                serverGeneration = data.optionalString("server_generation"),
                terminalId = data.optionalString("terminal_id"),
                paneId = data.optionalWireString("pane_id"),
                contentRevision = data.optionalStrictLong("content_revision"),
                workspace = data.optionalStrictLong("workspace"),
                tab = data.optionalStrictLong("tab"),
                label = data.optionalObject("detail")?.optionalString("label")
                    ?: data.optionalString("label"),
            )
        else -> BusEvent.Ignored(sequence = event.sequence, name = event.name)
    }
}

internal data class HostBusProjection(
    val snapshot: SessionSnapshot?,
    val agents: List<AgentSummary>,
    val tasks: List<TaskSummary>,
    val pullSession: Boolean = false,
    val relistTasks: Boolean = false,
    val resync: Boolean = false,
    /**
     * True when the event only touched per-pane terminal bookkeeping
     * (content revision). Such updates change nothing the topology cache or
     * the UI reads, so the caller may skip persisting and republishing.
     * terminal.output_ready arrives ~20/s while any agent is printing.
     */
    val terminalOnly: Boolean = false,
)

internal fun projectBusEvent(
    event: BusEvent,
    snapshot: SessionSnapshot?,
    agents: List<AgentSummary>,
    tasks: List<TaskSummary>,
): HostBusProjection {
    val base =
        HostBusProjection(
            snapshot = snapshot,
            agents = agents,
            tasks = tasks,
        )
    return when (event) {
        is BusEvent.AgentStatusChanged -> projectAgentStatus(event, snapshot, agents, tasks)
        is BusEvent.TaskPayload -> projectTask(event, snapshot, agents, tasks)
        is BusEvent.PaneChanged ->
            if (event.name == "pane.focused") {
                projectFocus(event.pane, snapshot, agents, tasks)
            } else {
                base.copy(pullSession = true)
            }
        is BusEvent.PaneRenamed -> base.copy(pullSession = true)
        is BusEvent.AgentHook -> base
        is BusEvent.LeaseChanged -> base
        is BusEvent.WorkspaceChanged -> base.copy(pullSession = true)
        is BusEvent.TerminalChanged ->
            if (event.name == "terminal.output_ready" || event.name == "terminal.metadata_changed") {
                projectTerminalLocal(event, snapshot, agents, tasks)
            } else {
                base.copy(pullSession = true)
            }
        is BusEvent.ResyncRequired -> base.copy(resync = true)
        is BusEvent.Ignored -> base
    }
}

private fun projectAgentStatus(
    event: BusEvent.AgentStatusChanged,
    snapshot: SessionSnapshot?,
    agents: List<AgentSummary>,
    tasks: List<TaskSummary>,
): HostBusProjection {
    val pane = event.pane
    if (pane == null) {
        return HostBusProjection(snapshot, agents, tasks, pullSession = true)
    }
    // Luvus emits this event for plain shell panes too (dispatch.rs: "A plain
    // shell going quiet or blocking is not an agent"). A shell identity means
    // the pane is not, or is no longer, an Agent.
    val shell = event.agent.isShellIdentity()
    val index = agents.indexOfFirst { it.paneId == pane }
    if (index < 0) {
        return if (shell) {
            HostBusProjection(snapshot, agents, tasks)
        } else {
            HostBusProjection(snapshot, agents, tasks, pullSession = true)
        }
    }
    if (shell) {
        val remaining = agents.filterNot { it.paneId == pane }
        val nextSnapshot = snapshot?.copy(agents = snapshot.agents.filterNot { it.paneId == pane })
        return HostBusProjection(nextSnapshot, remaining, tasks)
    }
    val updated =
        agents[index].copy(
            status = event.status,
            agent = event.agent ?: agents[index].agent,
            cwd = event.cwd ?: agents[index].cwd,
            project = event.project ?: agents[index].project,
            branch = event.branch ?: agents[index].branch,
            authority = event.authority ?: agents[index].authority,
            stateSource = event.stateSource ?: agents[index].stateSource,
        )
    val nextAgents = agents.toMutableList().also { it[index] = updated }
    val nextSnapshot =
        snapshot?.copy(
            agents = snapshot.agents.map { agent ->
                if (agent.paneId == pane) updated else agent
            },
        )
    return HostBusProjection(nextSnapshot, nextAgents, tasks)
}

private fun projectTask(
    event: BusEvent.TaskPayload,
    snapshot: SessionSnapshot?,
    agents: List<AgentSummary>,
    tasks: List<TaskSummary>,
): HostBusProjection {
    val task = event.task
    if (task != null) {
        val summary = task.toSummary()
        val next = tasks.filterNot { it.id == summary.id } + summary
        return HostBusProjection(snapshot, agents, next)
    }
    val id = event.id
    if (event.name == "task.deleted" && id != null) {
        return HostBusProjection(snapshot, agents, tasks.filterNot { it.id == id })
    }
    return HostBusProjection(snapshot, agents, tasks, relistTasks = true)
}

private fun projectFocus(
    pane: String?,
    snapshot: SessionSnapshot?,
    agents: List<AgentSummary>,
    tasks: List<TaskSummary>,
): HostBusProjection {
    if (pane == null) {
        return HostBusProjection(snapshot, agents, tasks)
    }
    val nextAgents = agents.map { it.copy(focused = it.paneId == pane) }
    val nextSnapshot =
        snapshot?.copy(
            panes = snapshot.panes.map { it.copy(focused = it.paneId == pane) },
            agents = snapshot.agents.map { it.copy(focused = it.paneId == pane) },
        )
    return HostBusProjection(nextSnapshot, nextAgents, tasks)
}

private fun projectTerminalLocal(
    event: BusEvent.TerminalChanged,
    snapshot: SessionSnapshot?,
    agents: List<AgentSummary>,
    tasks: List<TaskSummary>,
): HostBusProjection {
    val paneId = event.paneId
    val revision = event.contentRevision
    if (paneId == null || revision == null || snapshot == null) {
        return HostBusProjection(snapshot, agents, tasks, terminalOnly = true)
    }
    val nextSnapshot =
        snapshot.copy(
            panes =
                snapshot.panes.map { pane ->
                    if (pane.paneId == paneId) pane.copy(contentRevision = revision) else pane
                },
        )
    return HostBusProjection(nextSnapshot, agents, tasks, terminalOnly = true)
}

internal class UnknownMajorException(val name: String, val major: Int) : Exception("unsupported protocol $name/$major")

private fun mapWorkspaceListEntry(obj: JsonObject): WorkspaceListEntry =
    WorkspaceListEntry(
        workspace = obj.optionalWireString("workspace") ?: "",
        workspaceId = obj.optionalString("workspace_id"),
        name = obj.optionalString("name") ?: "",
        cwd = obj.optionalString("cwd"),
        terminalCwd = obj.optionalString("terminal_cwd"),
        pinned = obj.booleanOrFalse("pinned"),
        displayPosition = obj.optionalWireString("display_position"),
        active = obj.booleanOrFalse("active"),
        tabs = obj.optionalStrictLong("tabs")?.toInt() ?: 0,
    )

private fun mapAgentSummary(obj: JsonObject): AgentSummary =
    AgentSummary(
        paneId = obj.optionalWireString("pane") ?: obj.optionalString("pane_id") ?: "",
        name = obj.optionalString("name"),
        status = parseAgentStatus(obj.optionalString("status")),
        agent = obj.optionalString("agent"),
        authority = obj.optionalString("authority"),
        stateSource = obj.optionalString("state_source"),
        session = obj.optionalString("session"),
        focused = obj.booleanOrFalse("focused"),
        workspace = obj.optionalWireString("workspace"),
        workspaceName = obj.optionalString("workspace_name"),
        tab = obj.optionalWireString("tab"),
        cwd = obj.optionalString("cwd"),
        branch = obj.optionalString("branch"),
        project = obj.optionalString("project"),
        repo = obj.optionalString("repo"),
        worktree = obj.optionalBoolean("worktree"),
    )

private fun mapTask(obj: JsonObject): Task? {
    val id = obj.optionalString("id") ?: return null
    val title = obj.optionalString("title") ?: return null
    val worker = obj.optionalObject("workspace_worker")
    return Task(
        id = id,
        title = title,
        status = parseTaskStatus(obj.optionalString("status")),
        assignee = obj.optionalStrictLong("assignee"),
        deps = obj.optionalStringList("deps"),
        paths = obj.optionalStringList("paths"),
        gate = obj.optionalString("gate"),
        outputs = obj.optionalStringList("outputs"),
        notes = obj.optionalStringList("notes"),
        worktree = obj.optionalString("worktree"),
        branch = obj.optionalString("branch"),
        context = obj.optionalDouble("context"),
        created = obj.optionalStrictLong("created") ?: 0L,
        updated = obj.optionalStrictLong("updated") ?: 0L,
        mode = obj.optionalString("mode"),
        workspaceWorker =
            worker?.let {
                WorkspaceWorker(
                    workspaceId = it.optionalString("workspace_id") ?: return@let null,
                    tabId = it.optionalString("tab_id") ?: return@let null,
                    root = it.optionalString("root") ?: return@let null,
                )
            },
    )
}

private fun mapDiffFileMeta(obj: JsonObject): DiffFile =
    DiffFile(
        path = obj.optionalString("path") ?: "",
        pathRawHex = obj.optionalString("path_raw_hex"),
        oldPath = obj.optionalString("old_path"),
        oldPathRawHex = obj.optionalString("old_path_raw_hex"),
        layer = parseDiffLayer(obj.optionalString("layer")),
        status = obj.optionalString("status"),
        additions = obj.optionalStrictLong("additions"),
        deletions = obj.optionalStrictLong("deletions"),
        binary = obj.optionalBoolean("binary"),
        notes = obj.optionalStrictLong("notes"),
        viewed = obj.optionalBoolean("viewed"),
        modifiedSinceReview = obj.optionalBoolean("modified_since_review"),
        fingerprint = obj.optionalString("fingerprint"),
    )

private fun mapDiffHunks(result: JsonObject): List<DiffHunk> =
    result.optionalObjectList("hunks").map { hunk ->
        DiffHunk(
            id = hunk.optionalString("id") ?: "",
            oldStart = hunk.optionalStrictLong("old_start") ?: 0L,
            newStart = hunk.optionalStrictLong("new_start") ?: 0L,
            header = hunk.optionalString("header") ?: "",
            lines = mapDiffLines(hunk),
        )
    }

private fun mapDiffLines(hunk: JsonObject): List<DiffLine> {
    val value = hunk["lines"] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? JsonArray ?: return emptyList()
    return array.mapNotNull { el ->
        val line = el as? JsonObject ?: return@mapNotNull null
        DiffLine(
            kind = line.optionalString("kind") ?: "",
            oldLine = line.optionalStrictLong("old_line"),
            newLine = line.optionalStrictLong("new_line"),
            text = line.optionalString("text") ?: "",
        )
    }
}

private fun mapReviewNote(obj: JsonObject): ReviewNote? {
    val id = obj.optionalString("id") ?: return null
    return ReviewNote(
        id = id,
        review = obj.optionalString("review"),
        author = obj.optionalString("author"),
        kind = parseReviewNoteKind(obj.optionalString("kind")),
        body = obj.optionalString("body") ?: "",
        state = parseReviewNoteState(obj.optionalString("state")),
        path = obj.optionalString("path"),
        layer = parseDiffLayer(obj.optionalString("layer")),
        side = obj.optionalString("side"),
        startLine = obj.optionalStrictLong("start_line"),
        endLine = obj.optionalStrictLong("end_line"),
        revision = obj.optionalStrictLong("revision"),
        deliveries =
            obj.optionalObjectList("deliveries").mapNotNull { delivery ->
                val target = delivery.optionalString("target") ?: return@mapNotNull null
                ReviewNoteDelivery(
                    target = target,
                    deliveredAtMs = delivery.optionalStrictLong("delivered_at_ms"),
                )
            },
        createdAtMs = obj.optionalStrictLong("created_at_ms"),
        updatedAtMs = obj.optionalStrictLong("updated_at_ms"),
    )
}

private fun mapGitFileChange(obj: JsonObject): GitFileChange? {
    val path = obj.optionalString("path") ?: return null
    val code = obj.optionalWireString("code") ?: return null
    return GitFileChange(code = code, path = path)
}

private fun parseDiffLayer(raw: String?): DiffLayer? =
    when (raw) {
        "staged" -> DiffLayer.STAGED
        "worktree", "unstaged" -> DiffLayer.WORKTREE
        "untracked" -> DiffLayer.UNTRACKED
        "conflict" -> DiffLayer.CONFLICT
        else -> null
    }

private fun parseReviewNoteKind(raw: String?): ReviewNoteKind? =
    when (raw) {
        "question" -> ReviewNoteKind.QUESTION
        "issue" -> ReviewNoteKind.ISSUE
        "suggestion" -> ReviewNoteKind.SUGGESTION
        "praise" -> ReviewNoteKind.PRAISE
        else -> null
    }

private fun parseReviewNoteState(raw: String?): ReviewNoteState? =
    when (raw) {
        "open" -> ReviewNoteState.OPEN
        "resolved" -> ReviewNoteState.RESOLVED
        "outdated" -> ReviewNoteState.OUTDATED
        "orphaned" -> ReviewNoteState.ORPHANED
        else -> null
    }

private fun mapTerminalEntry(obj: JsonObject): TerminalInventoryEntry? {
    val terminalId = obj.optionalString("terminal_id") ?: return null
    val paneId = obj.optionalWireString("pane_id") ?: return null
    val workspace = obj.optionalObject("workspace")
    val tab = obj.optionalObject("tab")
    val root = obj.optionalObject("root_process")
    return TerminalInventoryEntry(
        terminalId = terminalId,
        paneId = paneId,
        contentRevision = obj.optionalStrictLong("content_revision") ?: 0L,
        terminalTitle = obj.optionalString("terminal_title"),
        label = obj.optionalString("label"),
        cwd = obj.optionalString("cwd"),
        workspace =
            workspace?.let {
                TerminalWorkspaceRef(
                    index = it.optionalStrictLong("index")?.toInt(),
                    name = it.optionalString("name"),
                    root = it.optionalString("root"),
                )
            },
        tab =
            tab?.let {
                TerminalTabRef(
                    index = it.optionalStrictLong("index")?.toInt(),
                    name = it.optionalString("name"),
                )
            },
        rootProcess =
            root?.optionalStrictLong("pid")?.let { pid ->
                ProcessIdentity(pid = pid, startMarker = root.optionalString("start_marker"))
            },
    )
}

private val shellIdentities: Set<String> =
    setOf(
        "sh", "bash", "zsh", "fish", "dash", "ksh", "tcsh", "csh",
        "nu", "nushell", "xonsh", "elvish", "pwsh", "powershell", "cmd",
    )

private fun String?.isShellIdentity(): Boolean {
    val name = this?.trim()?.substringAfterLast('/')?.lowercase() ?: return false
    return name in shellIdentities
}
