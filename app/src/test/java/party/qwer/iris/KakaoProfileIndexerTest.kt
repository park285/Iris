package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoProfileIndexerTest {
    @Test
    fun `stores only changed identities across notification keys`() {
        val store = RecordingNotificationIdentityStore()
        val indexer = KakaoProfileIndexer(notifications = EmptyNotificationFeed, profileStore = store)

        val firstIdentity =
            KakaoNotificationIdentity(
                stableId = "user-1",
                displayName = "Alice",
                roomName = "Room A",
                notificationKey = "notif-1",
                postedAt = 100L,
            )

        indexer.indexParsedIdentities(listOf(firstIdentity))
        indexer.indexParsedIdentities(listOf(firstIdentity))
        indexer.indexParsedIdentities(
            listOf(
                firstIdentity.copy(
                    notificationKey = "notif-2",
                    postedAt = 200L,
                ),
            ),
        )
        indexer.indexParsedIdentities(
            listOf(
                firstIdentity.copy(
                    displayName = "Alice Kim",
                    notificationKey = "notif-2",
                    postedAt = 300L,
                ),
            ),
        )

        assertEquals(
            listOf(
                firstIdentity,
                firstIdentity.copy(
                    displayName = "Alice Kim",
                    notificationKey = "notif-2",
                    postedAt = 300L,
                ),
            ),
            store.saved,
        )
    }
}

private object EmptyNotificationFeed : ActiveNotificationFeed {
    override fun snapshot() = emptyList<android.service.notification.StatusBarNotification>()
}

private class RecordingNotificationIdentityStore : NotificationIdentityStore {
    val saved = mutableListOf<KakaoNotificationIdentity>()

    override fun upsert(identity: KakaoNotificationIdentity) {
        saved += identity
    }
}
