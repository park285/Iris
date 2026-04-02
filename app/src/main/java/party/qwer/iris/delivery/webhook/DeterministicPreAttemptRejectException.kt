package party.qwer.iris.delivery.webhook

internal class DeterministicPreAttemptRejectException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
