package party.qwer.iris

import party.qwer.iris.model.PeriodSpec
import party.qwer.iris.nativecore.NativeCoreHolder

internal class PeriodSpecParser(
    private val defaultDays: Long = 7L,
) {
    fun parse(period: String?): PeriodSpec =
        NativeCoreHolder.current().parsePeriodSpecOrFallback(period, defaultDays) {
            parseKotlin(period)
        }

    private fun parseKotlin(period: String?): PeriodSpec =
        when {
            period == "all" -> PeriodSpec.All
            period != null && period.endsWith("d") ->
                period
                    .dropLast(1)
                    .toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let(PeriodSpec::Days)
                    ?: PeriodSpec.Days(defaultDays)
            else -> PeriodSpec.Days(defaultDays)
        }

    fun toSeconds(period: PeriodSpec): Long? =
        when (period) {
            PeriodSpec.All -> null
            is PeriodSpec.Days -> period.value * 86_400L
        }
}
