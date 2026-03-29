package party.qwer.iris

import android.content.Intent
import org.json.JSONObject
import java.util.UUID

internal object ReplyMarkdownHookExtras {
    const val sessionId = "party.qwer.iris.extra.SHARE_SESSION_ID"
    const val roomId = "party.qwer.iris.extra.ROOM_ID"
    const val threadId = "party.qwer.iris.extra.THREAD_ID"
    const val threadScope = "party.qwer.iris.extra.THREAD_SCOPE"
    const val createdAt = "party.qwer.iris.extra.CREATED_AT"
}

internal data class ReplyMarkdownThreadMetadata(
    val threadId: Long,
    val threadScope: Int,
    val sessionId: String = UUID.randomUUID().toString(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

internal data class ReplyMarkdownIntentSpec(
    val action: String,
    val packageName: String,
    val className: String,
    val callerPackageName: String,
    val mimeType: String,
    val markdown: Boolean,
    val markdownParam: Boolean,
    val forceFlag: Boolean,
    val extraPackageName: String,
    val extraChatAttachment: String,
    val innerAction: String,
    val innerMessageTypeValue: Int,
    val room: Long,
    val text: CharSequence,
    val keyType: Int,
    val fromDirectShare: Boolean,
    val flags: Int,
    val threadMetadata: ReplyMarkdownThreadMetadata? = null,
)

internal fun buildReplyMarkdownIntentSpec(
    room: Long,
    text: CharSequence,
    threadMetadata: ReplyMarkdownThreadMetadata? = null,
): ReplyMarkdownIntentSpec =
    ReplyMarkdownIntentSpec(
        action = Intent.ACTION_SEND,
        packageName = "com.kakao.talk",
        className = "com.kakao.talk.activity.RecentExcludeIntentFilterActivity",
        callerPackageName = "com.kakao.talk",
        mimeType = "text/plain",
        markdown = true,
        markdownParam = true,
        forceFlag = true,
        extraPackageName = "com.kakao.talk",
        extraChatAttachment = buildReplyMarkdownAttachment(threadMetadata?.sessionId),
        innerAction = "com.kakao.talk.action.ACTION_SEND_CHAT_MESSAGE",
        innerMessageTypeValue = 1,
        room = room,
        text = text,
        keyType = 1,
        fromDirectShare = true,
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        threadMetadata = threadMetadata,
    )

internal fun buildReplyMarkdownIntent(
    room: Long,
    text: CharSequence,
    threadMetadata: ReplyMarkdownThreadMetadata? = null,
): Intent {
    val spec = buildReplyMarkdownIntentSpec(room, text, threadMetadata)
    return Intent(spec.action).apply {
        setClassName(spec.packageName, spec.className)
        type = spec.mimeType
        putExtra(Intent.EXTRA_TEXT, spec.text)
        putExtra("markdown", spec.markdown)
        putExtra("markdownParam", spec.markdownParam)
        putExtra("f", spec.forceFlag)
        putExtra("EXTRA_PACKAGE", spec.extraPackageName)
        putExtra("EXTRA_CHAT_ATTACHMENT", spec.extraChatAttachment)
        putExtra("key_id", spec.room)
        putExtra("key_type", spec.keyType)
        putExtra("key_from_direct_share", spec.fromDirectShare)
        putExtra(
            "ConnectManager.ACTION_SEND_INTENT",
            Intent(spec.innerAction).apply {
                putExtra("EXTRA_CHAT_MESSAGE", spec.text.toString())
                putExtra("EXTRA_CHAT_ATTACHMENT", spec.extraChatAttachment)
                putExtra("EXTRA_CHAT_MESSAGE_TYPE_VALUE", spec.innerMessageTypeValue)
                putReplyMarkdownThreadMetadata(spec.room, spec.threadMetadata)
            },
        )
        putReplyMarkdownThreadMetadata(spec.room, spec.threadMetadata)
        addFlags(spec.flags)
    }
}

internal fun validateReplyMarkdownThreadMetadata(
    threadId: Long?,
    threadScope: Int?,
): Int? {
    if (threadId == null && threadScope == null) return null
    require(threadId != null) { "reply-markdown threadScope requires threadId" }
    val normalizedScope = threadScope ?: 2
    require(normalizedScope > 0) { "threadScope must be a positive integer" }
    return normalizedScope
}

private fun Intent.putReplyMarkdownThreadMetadata(
    room: Long,
    metadata: ReplyMarkdownThreadMetadata?,
) {
    if (metadata == null) return
    putExtra(ReplyMarkdownHookExtras.sessionId, metadata.sessionId)
    putExtra(ReplyMarkdownHookExtras.roomId, room.toString())
    putExtra(ReplyMarkdownHookExtras.threadId, metadata.threadId.toString())
    putExtra(ReplyMarkdownHookExtras.threadScope, metadata.threadScope)
    putExtra(ReplyMarkdownHookExtras.createdAt, metadata.createdAtEpochMs)
}

private fun buildReplyMarkdownAttachment(
    sessionId: String?,
): String =
    JSONObject()
        .apply {
            put("callingPkg", "com.kakao.talk")
            put("markdown", true)
            put("f", true)
            if (!sessionId.isNullOrBlank()) {
                put("irisSessionId", sessionId)
            }
        }.toString()
