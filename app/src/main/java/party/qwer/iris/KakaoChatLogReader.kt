package party.qwer.iris

internal class KakaoChatLogReader(
    private val runtime: KakaoDbRuntime,
    private val identityReader: KakaoIdentityReader,
) : ChatLogRepository {
    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<KakaoDB.ChatLogEntry> {
        val effectiveLimit = limit.coerceIn(1, KakaoDB.DEFAULT_POLL_BATCH_SIZE)
        return runtime.withPrimaryConnection { db ->
            db
                .rawQuery(
                    """
                    SELECT _id, id, chat_id, user_id, message, v, created_at, type, thread_id, scope, supplement, attachment
                    FROM chat_logs
                    WHERE _id > ?
                    ORDER BY _id ASC
                    LIMIT $effectiveLimit
                    """.trimIndent(),
                    arrayOf(afterLogId.toString()),
                ).use { cursor ->
                    val rows = ArrayList<KakaoDB.ChatLogEntry>(effectiveLimit)
                    val idIndex = cursor.getColumnIndexOrThrow("_id")
                    val chatLogIdIndex = cursor.getColumnIndex("id")
                    val chatIdIndex = cursor.getColumnIndexOrThrow("chat_id")
                    val userIdIndex = cursor.getColumnIndexOrThrow("user_id")
                    val messageIndex = cursor.getColumnIndexOrThrow("message")
                    val metadataIndex = cursor.getColumnIndexOrThrow("v")
                    val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
                    val messageTypeIndex = cursor.getColumnIndex("type")
                    val threadIdIndex = cursor.getColumnIndex("thread_id")
                    val threadScopeIndex = cursor.getColumnIndex("scope")
                    val supplementIndex = cursor.getColumnIndex("supplement")
                    val attachmentIndex = cursor.getColumnIndex("attachment")
                    while (cursor.moveToNext()) {
                        rows.add(
                            KakaoDB.ChatLogEntry(
                                id = cursor.getLong(idIndex),
                                chatLogId = cursor.getOptionalString(chatLogIdIndex),
                                chatId = cursor.getLong(chatIdIndex),
                                userId = cursor.getLong(userIdIndex),
                                message = cursor.getString(messageIndex).orEmpty(),
                                metadata = cursor.getString(metadataIndex).orEmpty(),
                                createdAt = cursor.getString(createdAtIndex),
                                messageType = cursor.getOptionalString(messageTypeIndex),
                                threadId = cursor.getOptionalString(threadIdIndex),
                                threadScope = cursor.getOptionalInt(threadScopeIndex),
                                supplement = cursor.getOptionalString(supplementIndex),
                                attachment = cursor.getOptionalString(attachmentIndex),
                            ),
                        )
                    }
                    rows
                }
        }
    }

    override fun resolveSenderName(userId: Long): String = identityReader.resolveSenderName(userId)

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata =
        runtime.withPrimaryConnection { db ->
            db.rawQuery("SELECT type, link_id FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use KakaoDB.RoomMetadata()
                }
                KakaoDB.RoomMetadata(
                    type = cursor.getString(0)?.trim().orEmpty(),
                    linkId = cursor.getString(1)?.trim().orEmpty(),
                )
            }
        }

    override fun latestLogId(): Long =
        runtime.withPrimaryConnection { db ->
            db.rawQuery("SELECT MAX(_id) FROM chat_logs", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    0L
                }
            }
        }
}
