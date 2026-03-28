package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

internal fun dispatchNotificationReply(
    startService: (Intent) -> Unit,
    referer: String,
    chatId: Long,
    preparedMessage: CharSequence,
    threadId: Long?,
    threadScope: Int?,
) {
    val isThreadNotification = threadId != null || threadScope != null
    val intent =
        Intent().apply {
            component =
                ComponentName(
                    "com.kakao.talk",
                    "com.kakao.talk.notification.NotificationActionService",
                )
            putExtra("noti_referer", referer)
            putExtra("chat_id", chatId)

            putExtra("is_chat_thread_notification", isThreadNotification)
            if (threadId != null) {
                putExtra("thread_id", threadId)
            }
            if (threadScope != null) {
                putExtra("scope", threadScope)
            }

            action = "com.kakao.talk.notification.REPLY_MESSAGE"

            val results =
                Bundle().apply {
                    putCharSequence("reply_message", preparedMessage)
                }

            val remoteInput = RemoteInput.Builder("reply_message").build()
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
        }

    IrisLogger.debug("[ReplyService] Calling AndroidHiddenApi.startService...")
    startService(intent)
    IrisLogger.debug("[ReplyService] AndroidHiddenApi.startService returned successfully")
}

internal fun dispatchSharedTextReply(
    startActivityAs: (String, Intent) -> Unit,
    room: Long,
    preparedMessage: CharSequence,
    threadId: Long?,
    threadScope: Int?,
) {
    val threadMetadata =
        if (threadId != null && threadScope != null) {
            ReplyMarkdownThreadMetadata(
                threadId = threadId,
                threadScope = threadScope,
            )
        } else {
            null
        }
    val spec = buildReplyMarkdownIntentSpec(room, preparedMessage, threadMetadata)
    val intent = buildReplyMarkdownIntent(room, preparedMessage, threadMetadata)
    startActivityAs(spec.callerPackageName, intent)
}
