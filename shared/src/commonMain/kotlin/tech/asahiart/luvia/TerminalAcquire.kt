package tech.asahiart.luvia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The terminal view an acquire must still match when its stream arrives. */
public data class TerminalBinding(
    val hostId: String,
    val serverGeneration: String,
    val terminalId: String,
    val paneId: String,
    val viewEpoch: Long,
)

/**
 * One host's control acquire. A late stream is closed when the view, pane, or
 * generation changed. Cancel does not skip that close.
 */
public class TerminalAcquire<T>(
    private val close: (T) -> Unit,
) {
    private var binding: TerminalBinding? = null
    private var visible: Boolean = false
    private var held: T? = null
    private var epoch: Long = 0
    private var request: Long = 0
    private var job: Job? = null

    public fun held(): T? = held

    public fun binding(): TerminalBinding? = if (visible) binding else null

    /** Same binding keeps the open control. A different one drops it. */
    public fun show(next: TerminalBinding) {
        if (visible && binding == next) return
        drop()
        binding = next
        visible = true
    }

    public fun hide() {
        visible = false
        drop()
    }

    public fun acquire(
        scope: CoroutineScope,
        onInstalled: (T) -> Unit = {},
        open: suspend () -> T?,
    ) {
        val ticket = binding ?: return
        if (!visible) return
        val ticketEpoch = epoch
        val ticketRequest = ++request
        // The previous open is left running. Cancel would drop a stream that still
        // arrives, and that stream has to be closed.
        job = scope.launch {
            val opened = open() ?: return@launch
            if (!still(ticket, ticketEpoch, ticketRequest)) {
                close(opened)
            } else {
                held?.let(close)
                held = opened
                onInstalled(opened)
            }
        }
    }

    /** False when this view no longer owns the held stream. [write] runs only then. */
    public fun send(write: (T) -> Unit): Boolean {
        val current = held ?: return false
        if (!visible || binding == null) return false
        write(current)
        return true
    }

    private fun still(ticket: TerminalBinding, ticketEpoch: Long, ticketRequest: Long): Boolean =
        visible && epoch == ticketEpoch && request == ticketRequest && binding == ticket

    private fun drop() {
        request += 1
        epoch += 1
        held?.let(close)
        held = null
    }
}
