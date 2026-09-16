package tech.asahiart.luvia.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.AcpAgentKind
import tech.asahiart.luvia.AcpEvent
import tech.asahiart.luvia.AcpPermissionKind
import tech.asahiart.luvia.AcpPermissionOption
import tech.asahiart.luvia.AcpPermissionRequest
import tech.asahiart.luvia.AcpPlanEntry
import tech.asahiart.luvia.AcpPlanStatus
import tech.asahiart.luvia.AcpSessionInfo
import tech.asahiart.luvia.AcpStopReason
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolCallUpdate
import tech.asahiart.luvia.AcpToolStatus

internal fun mapAcpAgents(result: JsonElement): List<AcpAgentKind> =
    result.asObject().optionalObjectList("agents").mapNotNull { row ->
        val id = row.optionalString("id") ?: return@mapNotNull null
        val name = row.optionalString("name") ?: id
        val command = row.optionalString("command") ?: ""
        AcpAgentKind(
            id = id,
            name = name,
            command = command,
            available = row.booleanOrFalse("available"),
        )
    }

internal fun mapAcpSessionAck(result: JsonElement, agentId: String): AcpSessionInfo {
    val obj = result.asObject()
    return AcpSessionInfo(
        sessionId = obj.optionalString("session_id") ?: "",
        agentId = obj.optionalString("agent") ?: agentId,
        agentName = obj.optionalString("agent_name") ?: "",
        protocolVersion = obj.optionalStrictLong("protocol_version")?.toInt() ?: 1,
        cwd = obj.optionalString("cwd") ?: "",
    )
}

internal fun decodeAcpEvent(event: UhpEvent): AcpEvent? =
    when (event.name) {
        "acp.update" -> decodeUpdate(event.data)
        "acp.permission" -> decodePermission(event.data)
        "acp.turn" ->
            AcpEvent.TurnEnded(parseStopReason(event.data.optionalString("stop_reason")))
        "acp.exit" ->
            AcpEvent.Exited(
                code = event.data.optionalStrictLong("code")?.toInt(),
                message = event.data.optionalString("message") ?: "",
            )
        else -> null
    }

internal fun encodeAcpAction(id: String, action: String, params: JsonObject): String {
    if (!REQUEST_ID.matches(id)) {
        throw CodecException(CodecException.Kind.InvalidId, "invalid action id")
    }
    val obj =
        buildJsonObject {
            put("id", id)
            put("action", action)
            put("params", params)
        }
    return compactJson.encodeToString(JsonObject.serializer(), obj)
}

private fun decodeUpdate(data: JsonObject): AcpEvent? {
    val update = data.optionalObject("update") ?: return null
    return when (update.optionalString("sessionUpdate")) {
        "agent_message_chunk" -> chunkText(update)?.let { AcpEvent.AgentMessage(it) }
        "agent_thought_chunk" -> chunkText(update)?.let { AcpEvent.AgentThought(it) }
        "user_message_chunk" -> chunkText(update)?.let { AcpEvent.UserMessage(it) }
        "tool_call" -> decodeToolCall(update)?.let { AcpEvent.ToolCall(it) }
        "tool_call_update" -> decodeToolCallUpdate(update)?.let { AcpEvent.ToolCallUpdate(it) }
        "plan" -> AcpEvent.Plan(decodePlanEntries(update))
        else -> null
    }
}

private fun chunkText(update: JsonObject): String? {
    val content = update.optionalObject("content") ?: return null
    if (content.optionalString("type") != "text") return null
    return content.optionalString("text")
}

private fun decodeToolCall(update: JsonObject): AcpToolCall? {
    val toolCallId = update.optionalString("toolCallId") ?: return null
    return AcpToolCall(
        toolCallId = toolCallId,
        title = update.optionalString("title") ?: "",
        kind = update.optionalString("kind"),
        status = parseToolStatus(update.optionalString("status")) ?: AcpToolStatus.Unknown,
        summary = toolSummary(update),
    )
}

private fun decodeToolCallUpdate(update: JsonObject): AcpToolCallUpdate? {
    val toolCallId = update.optionalString("toolCallId") ?: return null
    val hasStatus = "status" in update
    return AcpToolCallUpdate(
        toolCallId = toolCallId,
        title = update.optionalString("title"),
        kind = update.optionalString("kind"),
        status = if (hasStatus) parseToolStatus(update.optionalString("status")) ?: AcpToolStatus.Unknown else null,
        summary = toolSummary(update),
    )
}

private fun toolSummary(update: JsonObject): String? {
    val contents = update["content"] as? JsonArray
    if (contents != null) {
        for (el in contents) {
            val item = el as? JsonObject ?: continue
            val inner = item.optionalObject("content") ?: continue
            val text = inner.optionalString("text")
            if (!text.isNullOrEmpty()) return text
        }
    }
    val locations = update["locations"] as? JsonArray ?: return null
    for (el in locations) {
        val item = el as? JsonObject ?: continue
        val path = item.optionalString("path")
        if (!path.isNullOrEmpty()) return path
    }
    return null
}

private fun decodePlanEntries(update: JsonObject): List<AcpPlanEntry> =
    update.optionalObjectList("entries").mapNotNull { row ->
        val content = row.optionalString("content") ?: return@mapNotNull null
        AcpPlanEntry(
            content = content,
            priority = row.optionalString("priority"),
            status = parsePlanStatus(row.optionalString("status")),
        )
    }

private fun decodePermission(data: JsonObject): AcpEvent.Permission? {
    val requestId = data.optionalString("request_id") ?: return null
    val tool = data.optionalObject("tool_call")
    return AcpEvent.Permission(
        AcpPermissionRequest(
            requestId = requestId,
            title = data.optionalString("title") ?: "",
            description = data.optionalString("description"),
            toolTitle = tool?.optionalString("title"),
            toolKind = tool?.optionalString("kind"),
            options =
                data.optionalObjectList("options").mapNotNull { row ->
                    val optionId = row.optionalString("option_id") ?: return@mapNotNull null
                    AcpPermissionOption(
                        optionId = optionId,
                        name = row.optionalString("name") ?: optionId,
                        kind = parsePermissionKind(row.optionalString("kind")),
                    )
                },
        ),
    )
}

private fun parseToolStatus(raw: String?): AcpToolStatus? =
    when (raw) {
        null -> null
        "pending" -> AcpToolStatus.Pending
        "in_progress" -> AcpToolStatus.InProgress
        "completed" -> AcpToolStatus.Completed
        "failed" -> AcpToolStatus.Failed
        else -> AcpToolStatus.Unknown
    }

private fun parsePlanStatus(raw: String?): AcpPlanStatus =
    when (raw) {
        "in_progress" -> AcpPlanStatus.InProgress
        "completed" -> AcpPlanStatus.Completed
        else -> AcpPlanStatus.Pending
    }

private fun parsePermissionKind(raw: String?): AcpPermissionKind =
    when (raw) {
        "allow_once" -> AcpPermissionKind.AllowOnce
        "allow_always" -> AcpPermissionKind.AllowAlways
        "reject_once" -> AcpPermissionKind.RejectOnce
        "reject_always" -> AcpPermissionKind.RejectAlways
        else -> AcpPermissionKind.Other
    }

private fun parseStopReason(raw: String?): AcpStopReason =
    when (raw) {
        "end_turn" -> AcpStopReason.EndTurn
        "max_tokens" -> AcpStopReason.MaxTokens
        "max_turn_requests" -> AcpStopReason.MaxTurnRequests
        "refusal" -> AcpStopReason.Refusal
        "cancelled" -> AcpStopReason.Cancelled
        else -> AcpStopReason.Unknown
    }
