package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `lastDigestByNotificationKey evicts entries beyond LRU limit`() {
        val store = RecordingNotificationIdentityStore()
        val indexer = KakaoProfileIndexer(notifications = EmptyNotificationFeed, profileStore = store)

        // 1025개 유니크 notification key로 호출하여 LRU 상한(1024)을 초과시킨다
        val identities =
            (0 until 1025).map { i ->
                KakaoNotificationIdentity(
                    stableId = "user-$i",
                    displayName = "User $i",
                    roomName = "Room $i",
                    notificationKey = "notif-$i",
                    postedAt = i.toLong(),
                )
            }
        indexer.indexParsedIdentities(identities)

        // 내부 맵 크기 확인 (reflection)
        val digestMap =
            KakaoProfileIndexer::class.java.getDeclaredField("lastDigestByNotificationKey").let { field ->
                field.isAccessible = true
                field.get(indexer) as Map<*, *>
            }

        // retainAll이 호출되어 activeKeys만 남으므로,
        // 1025개를 한 번에 넘기면 모두 activeKeys에 포함된다.
        // LRU LinkedHashMap의 removeEldestEntry가 insert 시점에 동작하므로
        // 실제 맵 크기는 1024 이하여야 한다.
        // 단, indexParsedIdentities 내부에서 retainAll(activeKeys)가
        // removeEldestEntry 이후에 실행되므로 최종 크기는 1024 이하
        assertTrue(
            digestMap.size <= 1024,
            "lastDigestByNotificationKey size ${digestMap.size} exceeds LRU limit 1024",
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
