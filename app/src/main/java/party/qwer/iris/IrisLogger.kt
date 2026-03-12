package party.qwer.iris

object IrisLogger {
    enum class Level(
        val priority: Int,
    ) {
        NONE(4),
        ERROR(3),
        INFO(2),
        DEBUG(1),
    }

    @PublishedApi
    internal val currentLevel: Level = parseIrisLogLevel(System.getenv("IRIS_LOG_LEVEL"))

    fun error(message: String) {
        if (currentLevel.priority <= Level.ERROR.priority) {
            System.err.println(message)
        }
    }

    fun error(
        message: String,
        throwable: Throwable,
    ) {
        if (currentLevel.priority <= Level.ERROR.priority) {
            System.err.println(message)
            System.err.println(throwable.stackTraceToString())
        }
    }

    fun info(message: String) {
        if (currentLevel.priority <= Level.INFO.priority) {
            println(message)
        }
    }

    fun debug(message: String) {
        if (currentLevel.priority <= Level.DEBUG.priority) {
            println(message)
        }
    }

    /**
     * Lazy debug 로깅 - 로그 레벨이 DEBUG일 때만 messageProvider 평가.
     * 문자열 연결 오버헤드 방지.
     */
    inline fun debugLazy(messageProvider: () -> String) {
        if (currentLevel.priority <= Level.DEBUG.priority) {
            println(messageProvider())
        }
    }
}

internal fun parseIrisLogLevel(rawValue: String?): IrisLogger.Level {
    val normalized = rawValue?.trim()?.uppercase().orEmpty()
    if (normalized.isEmpty()) {
        return IrisLogger.Level.ERROR
    }

    return IrisLogger.Level.entries.firstOrNull { it.name == normalized } ?: IrisLogger.Level.ERROR
}
