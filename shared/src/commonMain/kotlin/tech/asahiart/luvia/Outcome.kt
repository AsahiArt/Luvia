package tech.asahiart.luvia

public sealed class Outcome<out T> {
    public class Ok<T>(public val value: T) : Outcome<T>()

    public class Err<T>(public val failure: Failure) : Outcome<T>()
}

public sealed class Failure {
    public class Frame(public val reason: String) : Failure()

    public class ProtocolError(public val reason: String) : Failure()

    public class UnknownMajor(public val name: String, public val major: Int) : Failure()

    public class CapabilityMissing(public val method: String) : Failure()

    public class Remote(public val code: String, public val message: String) : Failure()

    /**
     * The mutation was written but the response was lost. Do not auto-retry;
     * the server may already have applied it.
     */
    public class IndeterminateMutation(public val method: String) : Failure()

    public class Bridge(public val reason: String) : Failure()

    public class Transport(public val reason: String) : Failure()

    public class Closed : Failure()

    /**
     * `if_revision` did not match the current event sequence. The mutation did
     * not run. Resnapshot (or apply events), then retry only if the user intent
     * still holds.
     */
    public class RevisionConflict(
        public val expected: Long,
        public val actual: Long,
        public val message: String,
    ) : Failure()

    /**
     * Token or scope denied. Do not retry the same credentials; reconnecting
     * will not help until the grant or bridge allow-list changes.
     */
    public class Forbidden(public val message: String) : Failure()

    /** Missing pane, workspace, task, or agent. Refresh snapshot or inventory. */
    public class NotFound(public val message: String) : Failure()

    /**
     * Request shape is wrong (unknown fields, bad key, cursor newer than the
     * fence). Do not retry as-is. When [sequence] is set on `events.subscribe`,
     * drop the cursor and take a fresh snapshot.
     */
    public class InvalidParams(
        public val message: String,
        public val sequence: Long? = null,
    ) : Failure()

    /**
     * Bad envelope, unknown method, missing required field, or `if_revision`
     * on a read. Fix the request.
     */
    public class InvalidRequest(public val message: String) : Failure()

    /**
     * `server_generation` mismatch. Discard the locator triple and
     * re-inventory; never retry the old identity.
     */
    public class StaleServer(public val message: String) : Failure()

    /**
     * Terminal is alive but `pane_id` has moved. Update the route from
     * inventory (or error metadata) before retrying.
     */
    public class StaleRoute(public val message: String) : Failure()

    /** PTY is gone. Drop this terminal from the local inventory. */
    public class TerminalGone(public val message: String) : Failure()

    /**
     * Subscribe cursor is behind the replay floor, or the stream overflowed.
     * Drop the cursor, take a fresh snapshot, and resubscribe. Do not drop
     * the host.
     */
    public class ResyncRequired(
        public val message: String,
        public val sequence: Long? = null,
    ) : Failure()

    /**
     * Another client holds the exclusive terminal control lease. Wait or
     * fall back to observe-only.
     */
    public class ControlConflict(public val message: String) : Failure()

    /**
     * Target agent already has a prompt waiting for completion
     * (`dispatch.rs:4799-4803`). Do not submit another wait on the same pane
     * until that turn finishes.
     */
    public class AgentPromptBusy(public val message: String) : Failure()

    /** Request exceeded the 1 MiB frame cap. Shrink the payload. */
    public class FrameTooLarge(public val message: String) : Failure()

    /**
     * Server connection capacity is full. Back off and retry when
     * [retryable] is true.
     */
    public class ServerBusy(
        public val message: String,
        public val retryable: Boolean,
    ) : Failure()
}

internal fun <T> ok(value: T): Outcome<T> = Outcome.Ok(value)

internal fun <T> fail(failure: Failure): Outcome<T> = Outcome.Err(failure)
