package party.qwer.iris.delivery.webhook

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class WebhookDeliveryPolicyTest {
    @Test
    fun `deliveryTimeoutMs must be positive`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                WebhookDeliveryPolicy(
                    deliveryTimeoutMs = 0L,
                )
            }

        assertContains(
            error.message.orEmpty(),
            "deliveryTimeoutMs must be positive",
        )
    }

    @Test
    fun `claimHeartbeatIntervalMs must be positive`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                WebhookDeliveryPolicy(
                    claimHeartbeatIntervalMs = 0L,
                )
            }

        assertContains(
            error.message.orEmpty(),
            "claimHeartbeatIntervalMs must be positive",
        )
    }

    @Test
    fun `claimExpirationMs must exceed deliveryTimeoutMs with safety margin`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                WebhookDeliveryPolicy(
                    claimRecoveryIntervalMs = 500L,
                    claimExpirationMs = 1_000L,
                    deliveryTimeoutMs = 500L,
                    claimHeartbeatIntervalMs = 250L,
                )
            }

        assertContains(
            error.message.orEmpty(),
            "claimExpirationMs must exceed deliveryTimeoutMs with safety margin",
        )
    }

    @Test
    fun `claimRecoveryIntervalMs must not exceed claimExpirationMs`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                WebhookDeliveryPolicy(
                    claimRecoveryIntervalMs = 3_000L,
                    claimExpirationMs = 2_000L,
                    deliveryTimeoutMs = 500L,
                    claimHeartbeatIntervalMs = 250L,
                )
            }

        assertContains(
            error.message.orEmpty(),
            "claimRecoveryIntervalMs must not exceed claimExpirationMs",
        )
    }

    @Test
    fun `claimHeartbeatIntervalMs must be less than claimExpirationMs`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                WebhookDeliveryPolicy(
                    claimRecoveryIntervalMs = 500L,
                    claimExpirationMs = 2_000L,
                    deliveryTimeoutMs = 500L,
                    claimHeartbeatIntervalMs = 2_000L,
                )
            }

        assertContains(
            error.message.orEmpty(),
            "claimHeartbeatIntervalMs must be less than claimExpirationMs",
        )
    }
}
