package party.qwer.iris

import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var workerJob: Job? = null

    @Synchronized
    fun launch() {
        if (workerJob?.isActive == true) {
            return
        }

        workerJob =
            coroutineScope.launch {
                while (isActive) {
                    try {
                        refreshDirectory()
                    } catch (e: Exception) {
                        IrisLogger.error("[KakaoProfileIndexer] refresh failed: ${e.message}", e)
                    }
                    delay(scanIntervalMillis)
                }
            }
    }

    fun stop() {
        val job = synchronized(this) {
            val captured = workerJob ?: return
            workerJob = null
            captured
        }

        runBlocking {
            job.cancelAndJoin()
        }
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
