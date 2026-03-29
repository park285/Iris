package party.qwer.iris

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AppRuntimeWiringTest {
    @Test
    fun `AppRuntime uses sqlite schema journal and shutdown cleanup`() {
        val source = loadAppRuntimeSource()

        assertTrue(
            source.contains("IrisDatabaseSchema.createAll(persistenceDriver)"),
            "AppRuntime should create all SQLite persistence tables",
        )
        assertTrue(
            source.contains("checkpointJournal = SqliteCheckpointJournal(persistenceDriver)"),
            "AppRuntime should wire SqliteCheckpointJournal",
        )
        assertTrue(
            source.contains("checkpointJournal.flushNow()"),
            "AppRuntime stop should flush checkpoint journal before shutdown",
        )
        assertTrue(
            source.contains("persistenceDriver.close()"),
            "AppRuntime stop should close persistence driver before database shutdown",
        )
    }

    private fun loadAppRuntimeSource(): String {
        val candidates =
            listOf(
                File("app/src/main/java/party/qwer/iris/AppRuntime.kt"),
                File("../app/src/main/java/party/qwer/iris/AppRuntime.kt"),
            )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("AppRuntime.kt not found from test working directory")
    }
}
