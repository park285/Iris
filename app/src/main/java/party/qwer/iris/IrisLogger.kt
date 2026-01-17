package party.qwer.iris

object IrisLogger {
    enum class Level(val priority: Int) {
        ERROR(3),
        INFO(2),
        DEBUG(1),
    }

    @PublishedApi
    internal val currentLevel: Level =
        run {
            val env = System.getenv("IRIS_LOG_LEVEL")?.uppercase() ?: "ERROR"
            try {
                Level.valueOf(env)
            } catch (e: IllegalArgumentException) {
                println("[IrisLogger] Invalid IRIS_LOG_LEVEL=$env, using ERROR")
                Level.ERROR
            }
        }

    fun error(message: String) {
        if (currentLevel.priority <= Level.ERROR.priority) {
            System.err.println(message)
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

    /**
     * Lazy info 로깅.
     */
    inline fun infoLazy(messageProvider: () -> String) {
        if (currentLevel.priority <= Level.INFO.priority) {
            println(messageProvider())
        }
    }
}
