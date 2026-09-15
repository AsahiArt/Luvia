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
