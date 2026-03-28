package party.qwer.iris.imagebridge.runtime

import android.content.Intent

internal data class ReplyMarkdownPendingContext(
    val roomId: Long,
    val messageText: String,
    val threadId: Long,
    val threadScope: Int,
    val sessionId: String? = null,
    val createdAtEpochMs: Long,
)

internal class ReplyMarkdownPendingContextStore(
    private val ttlMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val context: ReplyMarkdownPendingContext,
        val rememberedAtEpochMs: Long,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun remember(context: ReplyMarkdownPendingContext) {
        pruneExpiredLocked()
        while (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(Entry(context, clock()))
    }

    @Synchronized
    fun match(
        roomId: Long,
        messageText: String,
        sessionId: String? = null,
    ): ReplyMarkdownPendingContext? {
        pruneExpiredLocked()
        if (sessionId != null) {
            findAndRemoveMatchingLocked(roomId, messageText, sessionId, allowSessionlessFallback = false)?.let { return it }
        }
        return findAndRemoveMatchingLocked(roomId, messageText, sessionId, allowSessionlessFallback = true)
    }

    private fun findAndRemoveMatchingLocked(
        roomId: Long,
        messageText: String,
        sessionId: String?,
        allowSessionlessFallback: Boolean,
    ): ReplyMarkdownPendingContext? {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (
                entry.context.roomId == roomId &&
                entry.context.messageText == messageText &&
                (
                    sessionId == null ||
                        entry.context.sessionId == sessionId ||
                        (
                            allowSessionlessFallback &&
                                entry.context.sessionId == null
                        )
                )
            ) {
                iterator.remove()
                return entry.context
            }
        }
        return null
    }

    @Synchronized
    fun size(): Int {
        pruneExpiredLocked()
        return entries.size
    }

    private fun pruneExpiredLocked() {
        val now = clock()
        while (entries.isNotEmpty()) {
            val oldest = entries.first()
            if (now - oldest.rememberedAtEpochMs <= ttlMs) {
                return
            }
            entries.removeFirst()
        }
    }
}

internal object ReplyMarkdownIngressCapture {
    fun capture(
        intent: Intent?,
        store: ReplyMarkdownPendingContextStore,
        onCaptured: (ReplyMarkdownPendingContext) -> Unit = {},
    ): ReplyMarkdownPendingContext? {
        val context = ReplyMarkdownBridgeExtras.extractPendingContext(intent) ?: return null
        store.remember(context)
        onCaptured(context)
        return context
    }
}

internal object ReplyMarkdownBridgeExtras {
    const val SESSION_ID = "party.qwer.iris.extra.SHARE_SESSION_ID"
    const val THREAD_ID = "party.qwer.iris.extra.THREAD_ID"
    const val THREAD_SCOPE = "party.qwer.iris.extra.THREAD_SCOPE"
    const val ROOM_ID = "party.qwer.iris.extra.ROOM_ID"
    const val CREATED_AT = "party.qwer.iris.extra.CREATED_AT"

    private const val EXTRA_SEND_INTENT = "ConnectManager.ACTION_SEND_INTENT"
    private const val EXTRA_CHAT_MESSAGE = "EXTRA_CHAT_MESSAGE"

    internal data class Snapshot(
        val sessionId: String? = null,
        val roomIdRaw: String? = null,
        val fallbackRoomId: Long? = null,
        val threadIdRaw: String? = null,
        val threadScope: Int? = null,
        val createdAtEpochMs: Long? = null,
        val messageText: String? = null,
        val nestedMessageText: String? = null,
    )

    fun extractPendingContext(
        intent: Intent?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ReplyMarkdownPendingContext? =
        intent?.let { source ->
            extractPendingContext(
                Snapshot(
                    sessionId = source.stringExtraOrNull(SESSION_ID),
                    roomIdRaw = source.stringExtraOrNull(ROOM_ID),
                    fallbackRoomId = source.longExtraOrNull("key_id"),
                    threadIdRaw = source.stringExtraOrNull(THREAD_ID),
                    threadScope = source.intExtraOrNull(THREAD_SCOPE),
                    createdAtEpochMs = source.longExtraOrNull(CREATED_AT),
                    messageText = source.textExtraOrNull(Intent.EXTRA_TEXT),
                    nestedMessageText = source.nestedIntentExtraOrNull(EXTRA_SEND_INTENT)?.textExtraOrNull(EXTRA_CHAT_MESSAGE),
                ),
                nowEpochMs = nowEpochMs,
            )
        }

    fun extractPendingContext(
        snapshot: Snapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ReplyMarkdownPendingContext? {
        val threadId = snapshot.threadIdRaw?.toLongOrNull() ?: return null
        val threadScope = snapshot.threadScope ?: 2
        if (threadScope <= 0) {
            return null
        }

        val roomId = snapshot.roomIdRaw?.toLongOrNull() ?: snapshot.fallbackRoomId ?: return null
        val messageText = snapshot.messageText ?: snapshot.nestedMessageText ?: return null
        if (messageText.isBlank()) {
            return null
        }

        return ReplyMarkdownPendingContext(
            roomId = roomId,
            messageText = messageText,
            threadId = threadId,
            threadScope = threadScope,
            sessionId = snapshot.sessionId,
            createdAtEpochMs = snapshot.createdAtEpochMs ?: nowEpochMs,
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.stringExtraOrNull(name: String): String? =
        when (val raw = extras?.get(name)) {
            is String -> raw.takeIf { it.isNotBlank() }
            is CharSequence -> raw.toString().takeIf { it.isNotBlank() }
            is Number -> raw.toString()
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun Intent.textExtraOrNull(name: String): String? =
        when (val raw = extras?.get(name)) {
            is CharSequence -> raw.toString()
            is Number -> raw.toString()
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun Intent.intExtraOrNull(name: String): Int? =
        when (val raw = extras?.get(name)) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun Intent.longExtraOrNull(name: String): Long? =
        when (val raw = extras?.get(name)) {
            is Long -> raw
            is Int -> raw.toLong()
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }

    private fun Intent.nestedIntentExtraOrNull(
        name: String,
    ): Intent? =
        runCatching {
            getParcelableExtra(name, Intent::class.java)
        }.getOrNull()
}
