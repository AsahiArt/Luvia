package tech.asahiart.luvia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.mapTaskMutation
import tech.asahiart.luvia.internal.optionalBoolean
import tech.asahiart.luvia.internal.optionalObject
import tech.asahiart.luvia.internal.optionalObjectList
import tech.asahiart.luvia.internal.optionalStrictLong
import tech.asahiart.luvia.internal.optionalString
import tech.asahiart.luvia.internal.optionalStringList
import tech.asahiart.luvia.internal.optionalWireString
import tech.asahiart.luvia.internal.wireName
import tech.asahiart.luvia.internal.withIfRevision

internal object OrchMethods {
    const val TASK_CLAIM: String = "task.claim"
    const val TASK_UPDATE: String = "task.update"
    const val TASK_MERGE: String = "task.merge"
    const val TASK_RELEASE: String = "task.release"
    const val TASK_DELETE: String = "task.delete"
    const val WORKTREE_LIST: String = "worktree.list"
    const val WORKTREE_CREATE: String = "worktree.create"
    const val WORKTREE_OPEN: String = "worktree.open"
    const val WORKTREE_REMOVE: String = "worktree.remove"
    const val LEASE_ACQUIRE: String = "lease.acquire"
    const val LEASE_LIST: String = "lease.list"
    const val LEASE_RELEASE: String = "lease.release"
    const val AUTOMATION_CREATE: String = "automation.create"
    const val AUTOMATION_LIST: String = "automation.list"
    const val AUTOMATION_GET: String = "automation.get"
    const val AUTOMATION_UPDATE: String = "automation.update"
    const val AUTOMATION_ENABLE: String = "automation.enable"
    const val AUTOMATION_DISABLE: String = "automation.disable"
    const val AUTOMATION_REBIND: String = "automation.rebind"
    const val AUTOMATION_DELETE: String = "automation.delete"
    const val AUTOMATION_RUN: String = "automation.run"
    const val AUTOMATION_HISTORY: String = "automation.history"
    const val AUTOMATION_PREVIEW: String = "automation.preview"
    const val AUTOMATION_HEALTH: String = "automation.health"
}

public data class WorktreeEntry(
    public val path: String,
    public val branch: String?,
    public val head: String?,
    public val main: Boolean,
)

public data class PathLease(
    public val id: String,
    public val pane: String?,
    public val task: String?,
    public val paths: List<String>,
    public val acquired: Long?,
)

public data class TaskReleaseResult(
    public val task: Task,
    public val releasedLeases: List<String>,
    public val revision: Long?,
)

public data class TaskMergeResult(
    public val outcome: String,
    public val task: String?,
    public val branch: String?,
    public val into: String?,
    public val commit: String?,
    public val files: List<String>,
    public val revision: Long?,
)

public sealed class AutomationTrigger {
    public data class Once(
        public val atUtc: Long,
    ) : AutomationTrigger()

    public data class Interval(
        public val everySeconds: Long,
        public val anchorUtc: Long,
    ) : AutomationTrigger()

    public data class Daily(
        public val timezone: String,
        public val secondOfDay: Int,
    ) : AutomationTrigger()

    public data class Weekly(
        public val timezone: String,
        public val weekdays: List<Int>,
        public val secondOfDay: Int,
    ) : AutomationTrigger()
}

public sealed class AutomationTarget {
    public data object NewWorker : AutomationTarget()

    public data class ActiveAgent(
        public val paneId: String,
        public val terminalId: String,
        public val ifBusy: String? = null,
        public val binding: String? = null,
    ) : AutomationTarget()
}

public data class AutomationTaskSpec(
    public val title: String,
    public val prompt: String,
    public val agentId: String,
    public val workspaceId: String,
    public val mode: String? = null,
    public val access: String? = null,
    public val paths: List<String> = emptyList(),
    public val gate: String? = null,
)

public data class AutomationPolicySpec(
    public val misfire: String? = null,
    public val overlap: String? = null,
    public val misfireGraceSeconds: Long? = null,
)

public data class AutomationDraft(
    public val name: String,
    public val enabled: Boolean = true,
    public val trigger: AutomationTrigger,
    public val target: AutomationTarget = AutomationTarget.NewWorker,
    public val task: AutomationTaskSpec,
    public val policy: AutomationPolicySpec? = null,
)


public data class Automation(
    public val id: String,
    public val name: String,
    public val enabled: Boolean,
    public val trigger: AutomationTrigger?,
    public val target: AutomationTarget?,
    public val targetState: String?,
    public val task: AutomationTaskSpec?,
    public val policy: AutomationPolicySpec?,
    public val nextRunAt: Long?,
    public val createdAt: Long?,
    public val updatedAt: Long?,
)

public data class AutomationRun(
    public val id: String,
    public val automationId: String,
    public val scheduledAt: Long?,
    public val createdAt: Long?,
    public val startedAt: Long?,
    public val finishedAt: Long?,
    public val taskId: String?,
    public val status: String?,
    public val attempt: Long?,
    public val error: String?,
    public val trigger: AutomationTrigger?,
    public val policy: AutomationPolicySpec?,
    public val target: AutomationTarget?,
    public val task: AutomationTaskSpec?,
)

public data class AutomationPreviewResult(
    public val occurrencesUtc: List<Long>,
)

public data class AutomationHealthSummary(
    public val definitions: Long,
    public val enabled: Long,
    public val scheduled: Long,
    public val running: Long,
    public val review: Long,
    public val failed: Long,
    public val nextRunAt: Long?,
)

public data class AutomationView(
    public val id: String,
    public val name: String,
    public val state: String?,
    public val nextRunAt: Long?,
    public val currentRunId: String?,
    public val latestRunId: String?,
    public val latestStatus: String?,
    public val latestError: String?,
    public val agentId: String?,
    public val workspaceId: String?,
    public val targetState: String?,
)

public data class AutomationHealthResult(
    public val summary: AutomationHealthSummary,
    public val automations: List<AutomationView>,
)

public suspend fun LuviaSession.claimTask(
    id: String,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<TaskMutationResult> {
    val params =
        buildJsonObject {
            put("id", id)
            if (pane != null) put("pane", pane)
        }
    return engine.unary(OrchMethods.TASK_CLAIM, withIfRevision(params, ifRevision), mutation = true) {
        mapTaskMutation(it.asObjectOrEmpty())
    }
}

public suspend fun LuviaSession.updateTask(
    id: String,
    status: TaskStatus? = null,
    output: String? = null,
    note: String? = null,
    prompt: String? = null,
    clearPrompt: Boolean = false,
    ifRevision: Long? = null,
): Outcome<TaskMutationResult> {
    val params =
        buildJsonObject {
            put("id", id)
            if (status != null) put("status", status.wireName())
            if (output != null) put("output", output)
            if (note != null) put("note", note)
            when {
                clearPrompt -> put("prompt", JsonNull)
                prompt != null -> put("prompt", prompt)
            }
        }
    return engine.unary(OrchMethods.TASK_UPDATE, withIfRevision(params, ifRevision), mutation = true) {
        mapTaskMutation(it.asObjectOrEmpty())
    }
}

public suspend fun LuviaSession.mergeTask(
    id: String,
    ifRevision: Long? = null,
): Outcome<TaskMergeResult> =
    engine.unary(
        OrchMethods.TASK_MERGE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { mapTaskMerge(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.releaseTask(
    id: String,
    ifRevision: Long? = null,
): Outcome<TaskReleaseResult> =
    engine.unary(
        OrchMethods.TASK_RELEASE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { mapTaskRelease(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.deleteTask(
    id: String,
    ifRevision: Long? = null,
): Outcome<TaskMutationResult> =
    engine.unary(
        OrchMethods.TASK_DELETE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { mapTaskMutation(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.listWorktrees(workspace: Int? = null): Outcome<List<WorktreeEntry>> {
    val params =
        if (workspace == null) {
            JsonObject(emptyMap())
        } else {
            buildJsonObject { put("workspace", workspace) }
        }
    return engine.unary(OrchMethods.WORKTREE_LIST, params, mutation = false) {
        it.asObjectOrEmpty().optionalObjectList("worktrees").map { row -> mapWorktree(row) }
    }
}

public suspend fun LuviaSession.createWorktree(
    branch: String,
    ifRevision: Long? = null,
    workspace: Int? = null,
): Outcome<String> =
    engine.unary(
        OrchMethods.WORKTREE_CREATE,
        withIfRevision(
            buildJsonObject {
                put("branch", branch)
                if (workspace != null) put("workspace", workspace)
            },
            ifRevision,
        ),
        mutation = true,
    ) { it.asObjectOrEmpty().optionalString("path") ?: "" }

public suspend fun LuviaSession.openWorktree(
    path: String,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        OrchMethods.WORKTREE_OPEN,
        withIfRevision(buildJsonObject { put("path", path) }, ifRevision),
        mutation = true,
    ) { }

public suspend fun LuviaSession.removeWorktree(
    path: String,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        OrchMethods.WORKTREE_REMOVE,
        withIfRevision(buildJsonObject { put("path", path) }, ifRevision),
        mutation = true,
    ) { }

public suspend fun LuviaSession.acquireLease(
    task: String,
    paths: List<String>,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PathLease> {
    val params =
        buildJsonObject {
            put("task", task)
            put("paths", stringArray(paths))
            if (pane != null) put("pane", pane)
        }
    return engine.unary(OrchMethods.LEASE_ACQUIRE, withIfRevision(params, ifRevision), mutation = true) {
        mapPathLease(
            it.asObjectOrEmpty().optionalObject("lease") ?: it.asObjectOrEmpty(),
        )
    }
}

public suspend fun LuviaSession.listLeases(): Outcome<List<PathLease>> =
    engine.unary(OrchMethods.LEASE_LIST, JsonObject(emptyMap()), mutation = false) {
        it.asObjectOrEmpty().optionalObjectList("leases").map { row -> mapPathLease(row) }
    }

public suspend fun LuviaSession.releaseLease(
    id: String,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        OrchMethods.LEASE_RELEASE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { }

public suspend fun LuviaSession.createAutomation(
    name: String,
    trigger: AutomationTrigger,
    task: AutomationTaskSpec,
    enabled: Boolean? = null,
    target: AutomationTarget? = null,
    policy: AutomationPolicySpec? = null,
    idempotencyKey: String? = null,
    ifRevision: Long? = null,
): Outcome<Automation> {
    val params =
        automationDefinitionParams(
            id = null,
            name = name,
            trigger = trigger,
            task = task,
            enabled = enabled,
            target = target,
            policy = policy,
            idempotencyKey = idempotencyKey,
        )
    return engine.unary(OrchMethods.AUTOMATION_CREATE, withIfRevision(params, ifRevision), mutation = true) {
        mapAutomationResult(it.asObjectOrEmpty())
    }
}

public suspend fun LuviaSession.listAutomations(): Outcome<List<Automation>> =
    engine.unary(OrchMethods.AUTOMATION_LIST, JsonObject(emptyMap()), mutation = false) {
        it.asObjectOrEmpty().optionalObjectList("automations").map { row -> mapAutomation(row) }
    }

public suspend fun LuviaSession.getAutomation(id: String): Outcome<Automation> =
    engine.unary(
        OrchMethods.AUTOMATION_GET,
        buildJsonObject { put("id", id) },
        mutation = false,
    ) { mapAutomationResult(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.updateAutomation(
    id: String,
    name: String,
    trigger: AutomationTrigger,
    task: AutomationTaskSpec,
    enabled: Boolean? = null,
    target: AutomationTarget? = null,
    policy: AutomationPolicySpec? = null,
    ifRevision: Long? = null,
): Outcome<Automation> {
    val params =
        automationDefinitionParams(
            id = id,
            name = name,
            trigger = trigger,
            task = task,
            enabled = enabled,
            target = target,
            policy = policy,
            idempotencyKey = null,
        )
    return engine.unary(OrchMethods.AUTOMATION_UPDATE, withIfRevision(params, ifRevision), mutation = true) {
        mapAutomationResult(it.asObjectOrEmpty())
    }
}

public suspend fun LuviaSession.enableAutomation(
    id: String,
    ifRevision: Long? = null,
): Outcome<Automation> =
    engine.unary(
        OrchMethods.AUTOMATION_ENABLE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { mapAutomationResult(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.disableAutomation(
    id: String,
    ifRevision: Long? = null,
): Outcome<Automation> =
    engine.unary(
        OrchMethods.AUTOMATION_DISABLE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { mapAutomationResult(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.rebindAutomation(
    id: String,
    pane: String,
    terminalId: String? = null,
    ifRevision: Long? = null,
): Outcome<Automation> {
    val params =
        buildJsonObject {
            put("id", id)
            put("pane", pane)
            if (terminalId != null) put("terminal_id", terminalId)
        }
    return engine.unary(OrchMethods.AUTOMATION_REBIND, withIfRevision(params, ifRevision), mutation = true) {
        mapAutomationResult(it.asObjectOrEmpty())
    }
}

public suspend fun LuviaSession.deleteAutomation(
    id: String,
    ifRevision: Long? = null,
): Outcome<Automation> =
    engine.unary(
        OrchMethods.AUTOMATION_DELETE,
        withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
        mutation = true,
    ) { mapAutomationResult(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.runAutomation(
    id: String,
    idempotencyKey: String? = null,
    ifRevision: Long? = null,
): Outcome<AutomationRun> {
    val params =
        buildJsonObject {
            put("id", id)
            if (idempotencyKey != null) put("idempotency_key", idempotencyKey)
        }
    return engine.unary(OrchMethods.AUTOMATION_RUN, withIfRevision(params, ifRevision), mutation = true) {
        mapAutomationRunResult(it.asObjectOrEmpty())
    }
}

public suspend fun LuviaSession.listAutomationHistory(
    id: String? = null,
    limit: Long? = null,
): Outcome<List<AutomationRun>> {
    val params =
        buildJsonObject {
            if (id != null) put("id", id)
            if (limit != null) put("limit", limit)
        }
    return engine.unary(OrchMethods.AUTOMATION_HISTORY, params, mutation = false) {
        it.asObjectOrEmpty().optionalObjectList("runs").map { row -> mapAutomationRun(row) }
    }
}

public suspend fun LuviaSession.previewAutomation(
    trigger: AutomationTrigger,
    fromUtc: Long? = null,
): Outcome<AutomationPreviewResult> {
    val params =
        buildJsonObject {
            put("trigger", trigger.toJson())
            if (fromUtc != null) put("from_utc", fromUtc)
        }
    return engine.unary(OrchMethods.AUTOMATION_PREVIEW, params, mutation = false) {
        AutomationPreviewResult(occurrencesUtc = longList(it.asObjectOrEmpty(), "occurrences_utc"))
    }
}

public suspend fun LuviaSession.automationHealth(): Outcome<AutomationHealthResult> =
    engine.unary(OrchMethods.AUTOMATION_HEALTH, JsonObject(emptyMap()), mutation = false) {
        mapAutomationHealth(it.asObjectOrEmpty())
    }

private fun automationDefinitionParams(
    id: String?,
    name: String,
    trigger: AutomationTrigger,
    task: AutomationTaskSpec,
    enabled: Boolean?,
    target: AutomationTarget?,
    policy: AutomationPolicySpec?,
    idempotencyKey: String?,
): JsonObject =
    buildJsonObject {
        if (id != null) put("id", id)
        put("name", name)
        if (enabled != null) put("enabled", enabled)
        put("trigger", trigger.toJson())
        if (target != null) put("target", target.toParams())
        put("task", task.toJson())
        if (policy != null) put("policy", policy.toJson())
        if (idempotencyKey != null) put("idempotency_key", idempotencyKey)
    }

private fun AutomationTrigger.toJson(): JsonObject =
    when (this) {
        is AutomationTrigger.Once ->
            buildJsonObject {
                put("kind", "once")
                put("at_utc", atUtc)
            }
        is AutomationTrigger.Interval ->
            buildJsonObject {
                put("kind", "interval")
                put("every_seconds", everySeconds)
                put("anchor_utc", anchorUtc)
            }
        is AutomationTrigger.Daily ->
            buildJsonObject {
                put("kind", "daily")
                put("timezone", timezone)
                put("second_of_day", secondOfDay)
            }
        is AutomationTrigger.Weekly ->
            buildJsonObject {
                put("kind", "weekly")
                put("timezone", timezone)
                put("weekdays", buildJsonArray { weekdays.forEach { add(JsonPrimitive(it)) } })
                put("second_of_day", secondOfDay)
            }
    }

private fun AutomationTarget.toParams(): JsonObject =
    when (this) {
        is AutomationTarget.NewWorker -> buildJsonObject { put("kind", "new_worker") }
        is AutomationTarget.ActiveAgent ->
            buildJsonObject {
                put("kind", "active_agent")
                put("pane_id", paneId)
                put("terminal_id", terminalId)
                if (ifBusy != null) put("if_busy", ifBusy)
            }
    }

private fun AutomationTaskSpec.toJson(): JsonObject =
    buildJsonObject {
        put("title", title)
        put("prompt", prompt)
        put("agent_id", agentId)
        put("workspace_id", workspaceId)
        if (mode != null) put("mode", mode)
        if (access != null) put("access", access)
        if (paths.isNotEmpty()) put("paths", stringArray(paths))
        if (gate != null) put("gate", gate)
    }

private fun AutomationPolicySpec.toJson(): JsonObject =
    buildJsonObject {
        if (misfire != null) put("misfire", misfire)
        if (overlap != null) put("overlap", overlap)
        if (misfireGraceSeconds != null) put("misfire_grace_seconds", misfireGraceSeconds)
    }

private fun mapWorktree(obj: JsonObject): WorktreeEntry =
    WorktreeEntry(
        path = obj.optionalString("path") ?: "",
        branch = obj.optionalString("branch"),
        head = obj.optionalString("head"),
        main = obj.optionalBoolean("main") ?: false,
    )

private fun mapPathLease(obj: JsonObject): PathLease =
    PathLease(
        id = obj.optionalString("id") ?: "",
        pane = obj.optionalWireString("pane"),
        task = obj.optionalString("task"),
        paths = obj.optionalStringList("paths"),
        acquired = obj.optionalStrictLong("acquired"),
    )

private fun mapTaskRelease(result: JsonObject): TaskReleaseResult {
    val mapped = mapTaskMutation(result)
    return TaskReleaseResult(
        task = mapped.task,
        releasedLeases = result.optionalStringList("released_leases"),
        revision = mapped.revision,
    )
}

private fun mapTaskMerge(result: JsonObject): TaskMergeResult =
    TaskMergeResult(
        outcome = result.optionalString("outcome") ?: "",
        task = result.optionalWireString("task"),
        branch = result.optionalString("branch"),
        into = result.optionalString("into"),
        commit = result.optionalString("commit"),
        files = result.optionalStringList("files"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapAutomationResult(result: JsonObject): Automation =
    mapAutomation(result.optionalObject("automation") ?: result)

private fun mapAutomation(obj: JsonObject): Automation =
    Automation(
        id = obj.optionalString("id") ?: "",
        name = obj.optionalString("name") ?: "",
        enabled = obj.optionalBoolean("enabled") ?: false,
        trigger = obj.optionalObject("trigger")?.let { mapTrigger(it) },
        target = obj.optionalObject("target")?.let { mapTarget(it) },
        targetState = obj.optionalString("target_state"),
        task = obj.optionalObject("task")?.let { mapTaskSpec(it) },
        policy = obj.optionalObject("policy")?.let { mapPolicy(it) },
        nextRunAt = obj.optionalStrictLong("next_run_at"),
        createdAt = obj.optionalStrictLong("created_at"),
        updatedAt = obj.optionalStrictLong("updated_at"),
    )

private fun mapAutomationRunResult(result: JsonObject): AutomationRun =
    mapAutomationRun(result.optionalObject("run") ?: result)

private fun mapAutomationRun(obj: JsonObject): AutomationRun =
    AutomationRun(
        id = obj.optionalString("id") ?: "",
        automationId = obj.optionalString("automation_id") ?: "",
        scheduledAt = obj.optionalStrictLong("scheduled_at"),
        createdAt = obj.optionalStrictLong("created_at"),
        startedAt = obj.optionalStrictLong("started_at"),
        finishedAt = obj.optionalStrictLong("finished_at"),
        taskId = obj.optionalString("task_id"),
        status = obj.optionalString("status"),
        attempt = obj.optionalStrictLong("attempt"),
        error = obj.optionalString("error"),
        trigger = obj.optionalObject("trigger")?.let { mapTrigger(it) },
        policy = obj.optionalObject("policy")?.let { mapPolicy(it) },
        target = obj.optionalObject("target")?.let { mapTarget(it) },
        task = obj.optionalObject("task")?.let { mapTaskSpec(it) },
    )

private fun mapAutomationHealth(result: JsonObject): AutomationHealthResult {
    val summary = result.optionalObject("summary") ?: JsonObject(emptyMap())
    return AutomationHealthResult(
        summary =
            AutomationHealthSummary(
                definitions = summary.optionalStrictLong("definitions") ?: 0L,
                enabled = summary.optionalStrictLong("enabled") ?: 0L,
                scheduled = summary.optionalStrictLong("scheduled") ?: 0L,
                running = summary.optionalStrictLong("running") ?: 0L,
                review = summary.optionalStrictLong("review") ?: 0L,
                failed = summary.optionalStrictLong("failed") ?: 0L,
                nextRunAt = summary.optionalStrictLong("next_run_at"),
            ),
        automations = result.optionalObjectList("automations").map { mapAutomationView(it) },
    )
}

private fun mapAutomationView(obj: JsonObject): AutomationView =
    AutomationView(
        id = obj.optionalString("id") ?: "",
        name = obj.optionalString("name") ?: "",
        state = obj.optionalString("state"),
        nextRunAt = obj.optionalStrictLong("next_run_at"),
        currentRunId = obj.optionalString("current_run_id"),
        latestRunId = obj.optionalString("latest_run_id"),
        latestStatus = obj.optionalWireString("latest_status"),
        latestError = obj.optionalString("latest_error"),
        agentId = obj.optionalString("agent_id"),
        workspaceId = obj.optionalString("workspace_id"),
        targetState = obj.optionalString("target_state"),
    )

private fun mapTrigger(obj: JsonObject): AutomationTrigger? =
    when (obj.optionalString("kind")) {
        "once" ->
            obj.optionalStrictLong("at_utc")?.let { AutomationTrigger.Once(atUtc = it) }
        "interval" -> {
            val every = obj.optionalStrictLong("every_seconds")
            val anchor = obj.optionalStrictLong("anchor_utc")
            if (every == null || anchor == null) null
            else AutomationTrigger.Interval(everySeconds = every, anchorUtc = anchor)
        }
        "daily" -> {
            val timezone = obj.optionalString("timezone")
            val second = obj.optionalStrictLong("second_of_day")?.toInt()
            if (timezone == null || second == null) null
            else AutomationTrigger.Daily(timezone = timezone, secondOfDay = second)
        }
        "weekly" -> {
            val timezone = obj.optionalString("timezone")
            val second = obj.optionalStrictLong("second_of_day")?.toInt()
            if (timezone == null || second == null) null
            else
                AutomationTrigger.Weekly(
                    timezone = timezone,
                    weekdays = longList(obj, "weekdays").map { it.toInt() },
                    secondOfDay = second,
                )
        }
        else -> null
    }

private fun mapTarget(obj: JsonObject): AutomationTarget? =
    when (obj.optionalString("kind")) {
        "new_worker" -> AutomationTarget.NewWorker
        "active_agent" -> {
            val paneId = obj.optionalWireString("pane_id")
            val terminalId = obj.optionalString("terminal_id")
            if (paneId == null || terminalId == null) null
            else
                AutomationTarget.ActiveAgent(
                    paneId = paneId,
                    terminalId = terminalId,
                    ifBusy = obj.optionalString("if_busy"),
                    binding = obj.optionalString("binding"),
                )
        }
        else -> null
    }

private fun mapTaskSpec(obj: JsonObject): AutomationTaskSpec =
    AutomationTaskSpec(
        title = obj.optionalString("title") ?: "",
        prompt = obj.optionalString("prompt") ?: "",
        agentId = obj.optionalString("agent_id") ?: "",
        workspaceId = obj.optionalString("workspace_id") ?: "",
        mode = obj.optionalString("mode"),
        access = obj.optionalString("access"),
        paths = obj.optionalStringList("paths"),
        gate = obj.optionalString("gate"),
    )

private fun mapPolicy(obj: JsonObject): AutomationPolicySpec =
    AutomationPolicySpec(
        misfire = obj.optionalString("misfire"),
        overlap = obj.optionalString("overlap"),
        misfireGraceSeconds = obj.optionalStrictLong("misfire_grace_seconds"),
    )

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun longList(obj: JsonObject, key: String): List<Long> {
    val value = obj[key] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? JsonArray ?: return emptyList()
    return array.mapNotNull { el ->
        val primitive = el as? JsonPrimitive ?: return@mapNotNull null
        if (primitive.isString) return@mapNotNull null
        primitive.content.toLongOrNull()
    }
}

private fun JsonElement.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
