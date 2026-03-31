package party.qwer.iris.model

internal sealed interface PeriodSpec {
    data object All : PeriodSpec

    data class Days(
        val value: Long,
    ) : PeriodSpec {
        init {
            require(value > 0L) { "days must be positive" }
        }
    }
}
