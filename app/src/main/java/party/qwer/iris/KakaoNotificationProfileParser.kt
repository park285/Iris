package party.qwer.iris

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

internal const val KAKAO_TALK_PACKAGE: String = "com.kakao.talk"

data class KakaoNotificationIdentity(
    val stableId: String,
    val displayName: String,
    val roomName: String,
    val notificationKey: String,
    val postedAt: Long,
)

internal data class MessagingSenderCandidate(
    val stableKey: String?,
    val displayName: String?,
    val timestamp: Long,
)

internal fun selectLatestSenderCandidate(candidates: List<MessagingSenderCandidate>): MessagingSenderCandidate? =
    candidates
        .asSequence()
        .filter { !it.stableKey.isNullOrBlank() }
        .maxByOrNull { it.timestamp }

internal fun resolveRoomNameValue(
    conversationTitle: String?,
    subText: String?,
    summaryText: String?,
    fallbackTitle: String?,
    displayName: String,
): String =
    sequenceOf(
        conversationTitle,
        subText,
        summaryText,
        fallbackTitle,
        displayName,
    ).mapNotNull { it.clean() }
        .firstOrNull()
        ?: displayName

object KakaoNotificationProfileParser {
    fun parse(sbn: StatusBarNotification): KakaoNotificationIdentity? {
        if (sbn.packageName != KAKAO_TALK_PACKAGE) return null

        val extras = sbn.notification.extras ?: return null
        val candidate = selectLatestSenderCandidate(readMessagingSenders(extras)) ?: return null
        val stableId = candidate.stableKey.clean() ?: return null
        val title = extras.readString(Notification.EXTRA_TITLE)
        val displayName = candidate.displayName.clean() ?: title.clean() ?: return null
        val roomName =
            resolveRoomNameValue(
                conversationTitle = extras.readString(Notification.EXTRA_CONVERSATION_TITLE),
                subText = extras.readString(Notification.EXTRA_SUB_TEXT),
                summaryText = extras.readString(Notification.EXTRA_SUMMARY_TEXT),
                fallbackTitle = title,
                displayName = displayName,
            )

        return KakaoNotificationIdentity(
            stableId = stableId,
            displayName = displayName,
            roomName = roomName,
            notificationKey = sbn.key,
            postedAt = sbn.postTime,
        )
    }

    private fun readMessagingSenders(extras: Bundle): List<MessagingSenderCandidate> {
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java) ?: return emptyList()

        return Notification.MessagingStyle.Message.getMessagesFromBundleArray(rawMessages).map { message ->
            val sender = message.senderPerson
            MessagingSenderCandidate(
                stableKey = sender?.key.clean(),
                displayName = sender?.name?.toString().clean(),
                timestamp = message.timestamp,
            )
        }
    }
}

private fun Bundle.readString(key: String): String? = getCharSequence(key)?.toString().clean()

private fun String?.clean(): String? {
    val trimmed = this?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}
