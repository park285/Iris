package party.qwer.iris

import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread

interface NotificationIdentityStore {
    fun upsert(identity: KakaoNotificationIdentity)
}

class KakaoDbNotificationIdentityStore(
    private val kakaoDb: KakaoDB,
) : NotificationIdentityStore {
    override fun upsert(identity: KakaoNotificationIdentity) {
        kakaoDb.upsertObservedProfile(identity)
    }
}

class KakaoProfileIndexer(
    private val notifications: ActiveNotificationFeed = HiddenNotificationFeed(),
    private val profileStore: NotificationIdentityStore,
    private val scanIntervalMillis: Long = 3_000L,
    private val parseNotification: (StatusBarNotification) -> KakaoNotificationIdentity? = KakaoNotificationProfileParser::parse,
) {
    private val lastDigestByNotificationKey = mutableMapOf<String, String>()
    private val lastDigestByIdentity =
        object : LinkedHashMap<String, String>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 8_192
        }

    @Volatile
    private var workerThread: Thread? = null

    @Synchronized
    fun launch() {
        if (workerThread?.isAlive == true) {
            return
        }

        workerThread =
            thread(start = true, isDaemon = true, name = "iris-kakao-profile-indexer") {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        refreshDirectory()
                    } catch (e: Exception) {
                        IrisLogger.error("[KakaoProfileIndexer] refresh failed: ${e.message}", e)
                    }

                    try {
                        Thread.sleep(scanIntervalMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
    }

    @Synchronized
    fun stop() {
        workerThread?.interrupt()
        workerThread = null
    }

    internal fun indexParsedIdentities(identities: Iterable<KakaoNotificationIdentity>) {
        synchronized(this) {
            val activeKeys = HashSet<String>()
            for (identity in identities) {
                activeKeys += identity.notificationKey
                val digest = digest(identity)
                if (lastDigestByNotificationKey[identity.notificationKey] != digest) {
                    lastDigestByNotificationKey[identity.notificationKey] = digest
                    if (lastDigestByIdentity[identity.stableId] != digest) {
                        profileStore.upsert(identity)
                        lastDigestByIdentity[identity.stableId] = digest
                    }
                }
            }

            lastDigestByNotificationKey.keys.retainAll(activeKeys)
        }
    }

    private fun refreshDirectory() {
        val identities =
            notifications.snapshot().mapNotNull { notification ->
                runCatching { parseNotification(notification) }.getOrNull()
            }
        indexParsedIdentities(identities)
    }

    private fun digest(identity: KakaoNotificationIdentity): String =
        listOf(identity.stableId, identity.displayName, identity.roomName)
            .joinToString("\u001F")
}
