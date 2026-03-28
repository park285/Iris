package party.qwer.iris.imagebridge.runtime

import org.json.JSONObject

internal object ReplyMarkdownSendingLogAccess {
    fun readRoomId(sendingLog: Any): Long? =
        runCatching {
            val value = sendingLog.javaClass.getMethod("getChatRoomId").invoke(sendingLog)
            when (value) {
                is Long -> value
                is Number -> value.toLong()
                else -> value?.toString()?.toLongOrNull()
            }
        }.getOrNull()

    fun readMessageText(sendingLog: Any): String? =
        runCatching {
            sendingLog
                .javaClass
                .getMethod("f0")
                .invoke(sendingLog)
                ?.toString()
        }.getOrNull()

    fun readAttachmentSessionId(
        sendingLog: Any,
    ): String? =
        readAttachmentText(
            sendingLog,
        )?.let(::extractSessionId)

    fun writeThreadMetadata(
        sendingLog: Any,
        threadId: Long,
        threadScope: Int,
    ) {
        val sendingLogClass = sendingLog.javaClass
        val threadIdValue = java.lang.Long.valueOf(threadId)

        runCatching {
            sendingLogClass.getMethod("H1", Int::class.javaPrimitiveType).invoke(sendingLog, threadScope)
        }.getOrElse {
            val scopeField = sendingLogClass.getDeclaredField("Z").apply { isAccessible = true }
            scopeField.setInt(sendingLog, threadScope)
        }

        runCatching {
            sendingLogClass.getMethod("J1", Long::class.javaObjectType).invoke(sendingLog, threadIdValue)
        }.getOrElse {
            val threadField = sendingLogClass.getDeclaredField("V0").apply { isAccessible = true }
            threadField.set(sendingLog, threadIdValue)
        }
    }

    private fun readAttachmentText(sendingLog: Any): String? =
        runCatching {
            val attachment =
                sendingLog
                    .javaClass
                    .getDeclaredField("G")
                    .apply { isAccessible = true }
                    .get(sendingLog)
            when (attachment) {
                null -> null
                is String -> attachment
                else ->
                    attachment
                        .javaClass
                        .declaredFields
                        .asSequence()
                        .onEach { field -> field.isAccessible = true }
                        .mapNotNull { field -> field.get(attachment) as? String }
                        .firstOrNull { candidate -> candidate.contains("irisSessionId") || candidate.contains("callingPkg") }
                        ?: attachment.toString()
            }
        }.getOrNull()

    private fun extractSessionId(attachmentText: String): String? =
        runCatching {
            JSONObject(attachmentText).optString("irisSessionId").ifBlank { null }
        }.getOrNull()
}
