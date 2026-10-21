package io.github.anetesa.sellerflow.core

/**
 * The result of an operation that can fail in an expected way. Failures carry a [SellerFlowError]
 * whose message tells the user what to do next; exceptions are reserved for programming errors.
 */
sealed interface Outcome<out T> {
    data class Ok<out T>(
        val value: T,
    ) : Outcome<T>

    data class Err(
        val error: SellerFlowError,
    ) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Ok -> Outcome.Ok(transform(value))
        is Outcome.Err -> this
    }

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> =
    when (this) {
        is Outcome.Ok -> transform(value)
        is Outcome.Err -> this
    }

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/**
 * Everything that can go wrong in a way the user can fix. [message] is shown to the user (and to the
 * AI agent) as is, so it says what happened and what to do — never a stack trace, never a secret.
 */
sealed class SellerFlowError(
    val message: String,
) {
    /** Configuration is missing or invalid. */
    class Config(
        message: String,
    ) : SellerFlowError(message)

    /** A service rejected or lost our credentials. */
    class Auth(
        val service: String,
        message: String,
    ) : SellerFlowError(message)

    /** The input does not make sense (bad NIP, unknown order and so on). */
    class Invalid(
        message: String,
    ) : SellerFlowError(message)

    /** A remote service failed or is unreachable; retrying later may help. */
    class Remote(
        val service: String,
        message: String,
    ) : SellerFlowError(message)

    /** A write was refused because it would duplicate one already done (idempotency guard). */
    class AlreadyDone(
        message: String,
    ) : SellerFlowError(message)

    override fun toString(): String = "${this::class.simpleName}: $message"
}
