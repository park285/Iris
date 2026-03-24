package party.qwer.iris

import android.content.Intent
import party.qwer.iris.model.ReplyType

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
)

internal fun buildReplyMarkdownIntentSpec(
    room: Long,
    text: CharSequence,
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
        extraChatAttachment = "{\"callingPkg\":\"com.kakao.talk\",\"markdown\":true,\"f\":true}",
        innerAction = "com.kakao.talk.action.ACTION_SEND_CHAT_MESSAGE",
        innerMessageTypeValue = 1,
        room = room,
        text = text,
        keyType = 1,
        fromDirectShare = true,
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
    )

internal fun buildReplyMarkdownIntent(
    room: Long,
    text: CharSequence,
): Intent {
    val spec = buildReplyMarkdownIntentSpec(room, text)
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
            },
        )
        addFlags(spec.flags)
    }
}

internal fun validateReplyMarkdownThreadMetadata(
    threadId: Long?,
    threadScope: Int?,
) {
    require(threadId == null && threadScope == null) {
        "reply-markdown does not support thread metadata"
    }
}

internal fun validateReplyMarkdownType(replyType: ReplyType) {
    require(replyType == ReplyType.TEXT) {
        "reply-markdown replies require type=text"
    }
}
