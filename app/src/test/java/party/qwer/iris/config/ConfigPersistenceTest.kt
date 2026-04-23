package party.qwer.iris.config

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigPersistenceTest {
    private fun createPersistence(configPath: String): ConfigPersistence {
        val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        return ConfigPersistence(configPath, json)
    }

    @Test
    fun `load returns missing when file does not exist`() {
        val tmpDir = Files.createTempDirectory("iris-persist-load").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val persistence = createPersistence(configPath)

        val result = persistence.load()
        assertIs<ConfigLoadResult.Missing>(result)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load returns state from valid config file`() {
        val tmpDir = Files.createTempDirectory("iris-persist-load-valid").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("""{"botName":"TestBot","botHttpPort":4000}""")
        val persistence = createPersistence(configPath)

        val result = persistence.load()
        assertIs<ConfigLoadResult.Loaded>(result)
        assertEquals("TestBot", result.config.userState.botName)
        assertEquals(4000, result.config.userState.botHttpPort)
        assertFalse(result.config.migratedLegacyEndpoint)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load backs up broken config and returns invalid`() {
        val tmpDir = Files.createTempDirectory("iris-persist-broken").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("not valid json {{{")
        val persistence = createPersistence(configPath)

        val result = persistence.load()
        assertIs<ConfigLoadResult.Invalid>(result)
        assertTrue(tmpDir.resolve("config.json.bak").exists())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load backs up config when policy validation fails`() {
        val tmpDir = Files.createTempDirectory("iris-persist-invalid").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("""{"botHttpPort":70000}""")
        val persistence = createPersistence(configPath)

        val result = persistence.load()

        assertIs<ConfigLoadResult.Invalid>(result)
        assertTrue(tmpDir.resolve("config.json.bak").exists())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load rejects invalid webhook endpoint scheme from file`() {
        val tmpDir = Files.createTempDirectory("iris-persist-invalid-endpoint").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("""{"endpoint":"ftp://example.com/webhook"}""")
        val persistence = createPersistence(configPath)

        val result = persistence.load()

        assertIs<ConfigLoadResult.Invalid>(result)
        assertTrue(tmpDir.resolve("config.json.bak").exists())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `load rejects secrets with surrounding whitespace from file`() {
        val tmpDir = Files.createTempDirectory("iris-persist-invalid-secret").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("""{"inboundSigningSecret":" inbound-secret "}""")
        val persistence = createPersistence(configPath)

        val result = persistence.load()

        assertIs<ConfigLoadResult.Invalid>(result)
        assertTrue(tmpDir.resolve("config.json.bak").exists())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `save writes config and removes temp file`() {
        val tmpDir = Files.createTempDirectory("iris-persist-save").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val persistence = createPersistence(configPath)

        val state = party.qwer.iris.UserConfigState(botName = "SaveTest")
        val success = persistence.save(state)

        assertTrue(success)
        val configFile = tmpDir.resolve("config.json")
        assertTrue(configFile.exists())
        assertTrue(configFile.readText().contains("SaveTest"))
        assertTrue(tmpDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        tmpDir.deleteRecursively()
    }

    @Test
    fun `save creates parent directories`() {
        val tmpDir = Files.createTempDirectory("iris-persist-mkdir").toFile()
        val nested = tmpDir.resolve("deep/nested")
        val configPath = nested.resolve("config.json").absolutePath
        val persistence = createPersistence(configPath)

        val success = persistence.save(party.qwer.iris.UserConfigState())
        assertTrue(success)
        assertTrue(nested.resolve("config.json").exists())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `save then load round-trips state`() {
        val tmpDir = Files.createTempDirectory("iris-persist-roundtrip").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val persistence = createPersistence(configPath)

        val original =
            party.qwer.iris.UserConfigState(
                botName = "RoundTrip",
                botHttpPort = 5000,
                messageSendRate = 75,
                dbPollingRate = 200,
            )
        persistence.save(original)
        val loaded = assertIs<ConfigLoadResult.Loaded>(persistence.load())
        assertEquals("RoundTrip", loaded.config.userState.botName)
        assertEquals(5000, loaded.config.userState.botHttpPort)
        assertEquals(75L, loaded.config.userState.messageSendRate)
        assertEquals(200L, loaded.config.userState.dbPollingRate)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `save preserves routing maps`() {
        val tmpDir = Files.createTempDirectory("iris-persist-routing").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val persistence = createPersistence(configPath)

        val original =
            party.qwer.iris.UserConfigState(
                commandRoutePrefixes = mapOf("chatbot" to listOf("!", "/")),
                imageMessageTypeRoutes = mapOf("dalle" to listOf("26")),
            )
        persistence.save(original)
        val loaded = assertIs<ConfigLoadResult.Loaded>(persistence.load())
        assertEquals(mapOf("chatbot" to listOf("!", "/")), loaded.config.userState.commandRoutePrefixes)
        assertEquals(mapOf("dalle" to listOf("26")), loaded.config.userState.imageMessageTypeRoutes)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `backupBrokenConfig moves file to bak`() {
        val tmpDir = Files.createTempDirectory("iris-persist-backup").toFile()
        val configFile = tmpDir.resolve("config.json")
        configFile.writeText("broken content")
        val persistence = createPersistence(configFile.absolutePath)

        persistence.backupBrokenConfig()

        assertFalse(configFile.exists())
        assertTrue(tmpDir.resolve("config.json.bak").exists())
        assertEquals("broken content", tmpDir.resolve("config.json.bak").readText())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `backupBrokenConfig does nothing when file does not exist`() {
        val tmpDir = Files.createTempDirectory("iris-persist-backup-nofile").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val persistence = createPersistence(configPath)

        persistence.backupBrokenConfig()
        assertFalse(tmpDir.resolve("config.json.bak").exists())
        tmpDir.deleteRecursively()
    }
}
