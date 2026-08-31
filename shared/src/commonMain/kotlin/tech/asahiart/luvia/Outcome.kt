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

    public class IndeterminateMutation(public val method: String) : Failure()

    public class Bridge(public val reason: String) : Failure()
    public class Transport(public val reason: String) : Failure()

    public class Closed : Failure()
}

internal fun <T> ok(value: T): Outcome<T> = Outcome.Ok(value)

internal fun <T> fail(failure: Failure): Outcome<T> = Outcome.Err(failure)
