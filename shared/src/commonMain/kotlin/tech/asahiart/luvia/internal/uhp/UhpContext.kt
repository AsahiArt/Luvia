package tech.asahiart.luvia.internal.uhp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.asahiart.luvia.HostRuntime
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.LuviaSession

internal const val NOT_CONNECTED: String = "Not connected to this host."

internal class UhpContext(
    val session: () -> LuviaSession?,
    val runtime: () -> HostRuntime?,
    private val state: MutableStateFlow<HostUhpState>,
    val scope: CoroutineScope,
) {
    fun value(): HostUhpState = state.value

    fun update(transform: (HostUhpState) -> HostUhpState) {
        state.update(transform)
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}

internal fun activeWorkspaceId(state: HostUhpState): String? {
    state.agents.firstOrNull { it.focused && it.workspaceId != null }?.workspaceId?.let { return it }
    val agentIds = state.agents.mapNotNull { it.workspaceId }.distinct()
    if (agentIds.size == 1) return agentIds.first()
    val missionIds = state.mission?.rows?.mapNotNull { it.workspaceId }?.distinct().orEmpty()
    return missionIds.singleOrNull()
}

