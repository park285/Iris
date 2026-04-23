package party.qwer.iris

import party.qwer.iris.model.ConfigRequest
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigUpdateOutcomeTest {
    @Test
    fun `sendrate update remains hot applied without restart`() {
        val configPath = temporaryConfigPath("iris-config-update-outcome-test")
        val configManager = ConfigManager(configPath = configPath.toString())

        val outcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "sendrate",
                request = ConfigRequest(rate = 25),
            )

        assertEquals(25L, configManager.messageSendRate)
        assertEquals("sendrate", outcome.name)
        assertTrue(outcome.persisted)
        assertEquals(true, outcome.applied)
        assertFalse(outcome.requiresRestart)
        assertEquals(25L, (outcome.response ?: error("expected response")).runtimeApplied.messageSendRate)
        configPath.toFile().deleteRecursively()
    }

    @Test
    fun `unknown config name throws ApiRequestException`() {
        val configPath = temporaryConfigPath("iris-config-update-outcome-unknown-test")
        val configManager = ConfigManager(configPath = configPath.toString())

        val exception =
            assertFailsWith<ApiRequestException> {
                applyConfigUpdate(
                    configManager = configManager,
                    name = "nonexistent",
                    request = ConfigRequest(),
                )
            }

        assertEquals("unknown config 'nonexistent'", exception.message)
        configPath.toFile().deleteRecursively()
    }

    @Test
    fun `update outcome keeps response snapshot from its own mutation after later update`() {
        val configPath = temporaryConfigPath("iris-config-update-response-atomic-test")
        val configManager = ConfigManager(configPath = configPath.toString())

        val firstOutcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "sendrate",
                request = ConfigRequest(rate = 25),
            )

        val secondOutcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "dbrate",
                request = ConfigRequest(rate = 2500),
            )

        assertTrue(firstOutcome.persisted)
        val firstResponse = firstOutcome.response ?: error("expected response")
        assertEquals("sendrate", firstResponse.name)
        assertEquals(25L, firstResponse.user.messageSendRate)
        assertEquals(25L, firstResponse.runtimeApplied.messageSendRate)
        assertEquals(100L, firstResponse.user.dbPollingRate)
        assertEquals(100L, firstResponse.runtimeApplied.dbPollingRate)
        assertTrue(secondOutcome.persisted)
        val secondResponse = secondOutcome.response ?: error("expected response")
        assertEquals(25L, secondResponse.user.messageSendRate)
        assertEquals(2500L, secondResponse.user.dbPollingRate)
        configPath.toFile().deleteRecursively()
    }

    @Test
    fun `overlapping updates are serialized and keep per request response snapshot`() {
        val configPath = temporaryConfigPath("iris-config-update-concurrency-test")
        val configManager = ConfigManager(configPath = configPath.toString())
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)
        val secondObservedRate = AtomicLong(-1L)

        try {
            val firstFuture =
                executor.submit<ConfigUpdateOutcome> {
                    configManager.applyConfigMutation { snapshot ->
                        firstEntered.countDown()
                        check(allowFirstToFinish.await(5, TimeUnit.SECONDS))
                        PlannedConfigUpdate(
                            name = "sendrate",
                            applied = true,
                            requiresRestart = false,
                            plan = ConfigMutationPlan(snapshot.copy(messageSendRate = 25), applyImmediately = true),
                        )
                    }
                }

            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val secondFuture =
                executor.submit<ConfigUpdateOutcome> {
                    configManager.applyConfigMutation { snapshot ->
                        secondObservedRate.set(snapshot.messageSendRate)
                        PlannedConfigUpdate(
                            name = "dbrate",
                            applied = true,
                            requiresRestart = false,
                            plan = ConfigMutationPlan(snapshot.copy(dbPollingRate = 2500), applyImmediately = true),
                        )
                    }
                }

            allowFirstToFinish.countDown()

            val firstOutcome = firstFuture.get(5, TimeUnit.SECONDS)
            val secondOutcome = secondFuture.get(5, TimeUnit.SECONDS)

            assertEquals(25L, secondObservedRate.get())
            val firstResponse = firstOutcome.response ?: error("expected first response")
            assertEquals(25L, firstResponse.user.messageSendRate)
            assertEquals(100L, firstResponse.user.dbPollingRate)
            val secondResponse = secondOutcome.response ?: error("expected second response")
            assertEquals(25L, secondResponse.user.messageSendRate)
            assertEquals(2500L, secondResponse.user.dbPollingRate)
        } finally {
            executor.shutdownNow()
            configPath.toFile().deleteRecursively()
        }
    }
}

private fun temporaryConfigPath(prefix: String) =
    Files
        .createTempDirectory(prefix)
        .resolve("config.json")
