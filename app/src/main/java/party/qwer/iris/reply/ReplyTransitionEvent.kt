package party.qwer.iris.reply

sealed interface ReplyTransitionEvent {
    data object PrepareStarted : ReplyTransitionEvent

    data object PrepareCompleted : ReplyTransitionEvent

    data object SendStarted : ReplyTransitionEvent

    data object SendCompleted : ReplyTransitionEvent

    data class Failed(
        val reason: String,
    ) : ReplyTransitionEvent
}
