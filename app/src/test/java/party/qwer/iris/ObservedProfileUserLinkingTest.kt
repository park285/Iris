package party.qwer.iris

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ObservedProfileUserLinkingTest {
    private val stores = mutableListOf<Pair<IrisMetadataStore, String>>()

    @AfterTest
    fun tearDown() {
        stores.forEach { (store, path) ->
            store.close()
            File(path).delete()
        }
        stores.clear()
    }

    @Test
    fun `extracts chat id from notification key`() {
        assertEquals(18478615493603057L, extractChatIdFromNotificationKey("0|com.kakao.talk|2|18478615493603057|10078"))
        assertEquals(null, extractChatIdFromNotificationKey("bad-key"))
    }

    @Test
    fun `matches observed profiles to unique known room member names`() {
        val links =
            matchObservedProfileUserLinks(
                chatId = 123L,
                observedProfiles =
                    listOf(
                        ObservedProfileRecord(
                            stableId = "stable-1",
                            displayName = "Alice",
                            roomName = "Room A",
                        ),
                    ),
                userDisplayNames = mapOf(42L to "Alice"),
            )

        assertEquals(
            listOf(
                ObservedProfileUserLink(
                    stableId = "stable-1",
                    userId = 42L,
                    chatId = 123L,
                    displayName = "Alice",
                    roomName = "Room A",
                ),
            ),
            links,
        )
    }

    @Test
    fun `ignores ambiguous display names when learning mappings`() {
        val links =
            matchObservedProfileUserLinks(
                chatId = 123L,
                observedProfiles =
                    listOf(
                        ObservedProfileRecord(
                            stableId = "stable-1",
                            displayName = "Alice",
                            roomName = "Room A",
                        ),
                    ),
                userDisplayNames = mapOf(42L to "Alice", 43L to "Alice"),
            )

        assertEquals(emptyList(), links)
    }

    @Test
    fun `learns timestamp correlation when exactly one unlinked profile matches`() {
        val store = createStore()
        val chatId = 123L
        val userId = 42L
        val messageCreatedAtMs = 1_000_000L
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-1",
                displayName = "Alice",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs + 1_000L,
            ),
        )

        store.learnFromTimestampCorrelation(chatId, userId, messageCreatedAtMs)

        assertEquals("Alice", store.resolveObservedDisplayName(userId, chatId))
    }

    @Test
    fun `skips timestamp correlation when profile is outside correlation window`() {
        val store = createStore()
        val chatId = 123L
        val userId = 42L
        val messageCreatedAtMs = 1_000_000L
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-1",
                displayName = "Alice",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs + 10_000L,
            ),
        )

        store.learnFromTimestampCorrelation(chatId, userId, messageCreatedAtMs)

        assertEquals(null, store.resolveObservedDisplayName(userId, chatId))
    }

    @Test
    fun `skips timestamp correlation when multiple unlinked profiles match`() {
        val store = createStore()
        val chatId = 123L
        val userId = 42L
        val messageCreatedAtMs = 1_000_000L
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-1",
                displayName = "Alice",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs - 1_000L,
            ),
        )
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-2",
                displayName = "Bob",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs + 1_000L,
            ),
        )

        store.learnFromTimestampCorrelation(chatId, userId, messageCreatedAtMs)

        assertEquals(null, store.resolveObservedDisplayName(userId, chatId))
    }

    @Test
    fun `skips timestamp correlation when user already has a link in chat`() {
        val store = createStore()
        val chatId = 123L
        val userId = 42L
        val messageCreatedAtMs = 1_000_000L
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-existing",
                displayName = "Alice",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs - 20_000L,
            ),
        )
        store.learnObservedProfileUserMappings(chatId, mapOf(userId to "Alice"))
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-new",
                displayName = "Bob",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs,
            ),
        )

        store.learnFromTimestampCorrelation(chatId, userId, messageCreatedAtMs)

        assertEquals("Alice", store.resolveObservedDisplayName(userId, chatId))
    }

    @Test
    fun `skips timestamp correlation when matching profile is already linked to another user`() {
        val store = createStore()
        val chatId = 123L
        val messageCreatedAtMs = 1_000_000L
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-1",
                displayName = "Alice",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs,
            ),
        )
        store.learnObservedProfileUserMappings(chatId, mapOf(7L to "Alice"))

        store.learnFromTimestampCorrelation(chatId, 42L, messageCreatedAtMs)

        assertEquals(null, store.resolveObservedDisplayName(42L, chatId))
        assertEquals("Alice", store.resolveObservedDisplayName(7L, chatId))
    }

    @Test
    fun `skips timestamp correlation when other profiles exist nearby in window`() {
        val store = createStore()
        val chatId = 123L
        val messageCreatedAtMs = 1_000_000L
        // 유저 B의 알림 프로필 (윈도우 내, 아직 링크 안 됨)
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-b",
                displayName = "Bob",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs - 2_000L,
            ),
        )
        // 유저 D의 알림 프로필 (윈도우 내, 이미 링크됨)
        store.upsertObservedProfile(
            notificationIdentity(
                stableId = "stable-d",
                displayName = "Dave",
                roomName = "Room A",
                chatId = chatId,
                postedAt = messageCreatedAtMs + 1_000L,
            ),
        )
        store.learnObservedProfileUserMappings(chatId, mapOf(99L to "Dave"))

        // 유저 C의 메시지에 대해 상관 시도 — 윈도우 내 다른 프로필(stable-d)이 있어 스킵
        store.learnFromTimestampCorrelation(chatId, 42L, messageCreatedAtMs)

        assertEquals(null, store.resolveObservedDisplayName(42L, chatId))
    }

    private fun createStore(): IrisMetadataStore {
        val path = "/tmp/iris-timestamp-correlation-test-${System.nanoTime()}.db"
        return IrisMetadataStore(databasePath = path).also { stores += it to path }
    }

    private fun notificationIdentity(
        stableId: String,
        displayName: String,
        roomName: String,
        chatId: Long,
        postedAt: Long,
    ): KakaoNotificationIdentity =
        KakaoNotificationIdentity(
            stableId = stableId,
            displayName = displayName,
            roomName = roomName,
            notificationKey = "0|com.kakao.talk|N|$chatId|X",
            postedAt = postedAt,
        )
}
