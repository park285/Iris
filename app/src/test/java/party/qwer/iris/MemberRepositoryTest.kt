package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeCoreJniBridge
import party.qwer.iris.nativecore.NativeCoreRuntime
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.QuerySpec
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.SqlClient
import party.qwer.iris.storage.ThreadQueries
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun stubResult(
    columns: List<String>,
    rows: List<Map<String, String?>>,
): QueryExecutionResult {
    val cols = columns.map { QueryColumn(name = it, sqliteType = "TEXT") }
    val jsonRows =
        rows.map { row ->
            columns.map { col ->
                row[col]?.let { JsonPrimitive(it) }
            }
        }
    return QueryExecutionResult(cols, jsonRows)
}

private fun emptyResult(): QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList())

private fun legacyQuery(block: (String, Array<String?>?, Int) -> List<Map<String, String?>>): (String, Array<String?>?, Int) -> QueryExecutionResult =
    { sql, args, maxRows ->
        val rows = block(sql, args, maxRows)
        val columns =
            rows
                .firstOrNull()
                ?.keys
                ?.toList()
                .orEmpty()
        if (columns.isEmpty()) {
            emptyResult()
        } else {
            stubResult(columns, rows)
        }
    }

private fun openMemberRow(
    userId: String,
    nickname: String,
    linkMemberType: String = "2",
    enc: String = "0",
): Map<String, String?> =
    mapOf(
        "user_id" to userId,
        "nickname" to nickname,
        "link_member_type" to linkMemberType,
        "profile_image_url" to null,
        "full_profile_image_url" to null,
        "original_profile_image_url" to null,
        "open_profile_image_url" to null,
        "open_profile_full_profile_image_url" to null,
        "open_profile_original_profile_image_url" to null,
        "linked_profile_nickname" to null,
        "linked_profile_enc" to null,
        "enc" to enc,
    )

private fun openNicknameRow(
    userId: String,
    nickname: String,
    enc: String = "0",
): Map<String, String?> =
    mapOf(
        "user_id" to userId,
        "nickname" to nickname,
        "linked_profile_nickname" to null,
        "linked_profile_enc" to null,
        "enc" to enc,
    )

// 빈 결과를 반환하는 ThreadQueries 스텁
private val stubThreadQueries =
    ThreadQueries(
        object : SqlClient {
            override fun <T> query(spec: QuerySpec<T>): List<T> = emptyList()
        },
    )

private fun buildRepoFromLegacy(
    executeQueryTyped: (String, Array<String?>?, Int) -> QueryExecutionResult,
    decrypt: (Int, String, Long) -> String = { _, s, _ -> s },
    botId: Long = 1L,
    learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit = { _, _ -> },
): MemberRepository {
    val sqlClient = KakaoDbSqlClient(executeQueryTyped)
    return MemberRepository(
        roomDirectory = RoomDirectoryQueries(sqlClient),
        memberIdentity = MemberIdentityQueries(sqlClient, decrypt, botId),
        observedProfile = ObservedProfileQueries(sqlClient),
        roomStats = RoomStatsQueries(sqlClient),
        threadQueries = stubThreadQueries,
        decrypt = decrypt,
        botId = botId,
        learnObservedProfileUserMappings = learnObservedProfileUserMappings,
    )
}

private fun withNativeParserResponses(
    vararg parserResponses: String,
    block: (QueueNativeCoreJni, NativeCoreRuntime) -> Unit,
) {
    val jni = QueueNativeCoreJni(parserResponses.toList())
    val runtime =
        NativeCoreRuntime.create(
            env =
                mapOf(
                    "IRIS_NATIVE_CORE" to "on",
                    "IRIS_NATIVE_DECRYPT" to "off",
                    "IRIS_NATIVE_PARSERS" to "on",
                ),
            loader = {},
            jni = jni,
        )
    try {
        NativeCoreHolder.install(runtime)
        block(jni, runtime)
    } finally {
        NativeCoreHolder.resetForTest()
    }
}

private class QueueNativeCoreJni(
    parserResponses: List<String>,
) : NativeCoreJniBridge {
    private val parserResponses = ArrayDeque(parserResponses)

    var parserCalls = 0
        private set
    val parserRequests = mutableListOf<String>()

    override fun nativeSelfTest(): String = "iris-native-core:test"

    override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray = error("decrypt should not be called")

    override fun parserBatch(requestJsonBytes: ByteArray): ByteArray {
        parserCalls += 1
        parserRequests += requestJsonBytes.decodeToString()
        if (parserResponses.isEmpty()) {
            error("unexpected parser batch call")
        }
        return parserResponses.removeFirst().encodeToByteArray()
    }
}

class MemberRepositoryTest {
    @Test
    fun `roleCodeToName maps known codes correctly`() {
        assertEquals(
            "owner",
            party.qwer.iris.model
                .roleCodeToName(1),
        )
        assertEquals(
            "member",
            party.qwer.iris.model
                .roleCodeToName(2),
        )
        assertEquals(
            "admin",
            party.qwer.iris.model
                .roleCodeToName(4),
        )
        assertEquals(
            "bot",
            party.qwer.iris.model
                .roleCodeToName(8),
        )
        assertEquals(
            "member",
            party.qwer.iris.model
                .roleCodeToName(99),
        )
    }

    @Test
    fun `parseJsonLongArray parses member id arrays`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped = legacyQuery { _, _, _ -> emptyList() },
                decrypt = { _, _, _ -> "" },
                botId = 1L,
            )
        assertEquals(setOf(1L, 2L, 3L), repo.parseJsonLongArray("[1,2,3]"))
        assertEquals(emptySet(), repo.parseJsonLongArray("[]"))
        assertEquals(emptySet(), repo.parseJsonLongArray(null))
        assertEquals(emptySet(), repo.parseJsonLongArray(""))
    }

    @Test
    fun `parsePeriodSeconds converts period strings`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped = legacyQuery { _, _, _ -> emptyList() },
                decrypt = { _, _, _ -> "" },
                botId = 1L,
            )
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("7d"))
        assertEquals(30 * 86400L, repo.parsePeriodSeconds("30d"))
        assertEquals(null, repo.parsePeriodSeconds("all"))
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("invalid"))
    }

    @Test
    fun `roomSummary resolves non open room metadata`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, args, _ ->
                        when {
                            sql.contains("FROM chat_rooms WHERE id = ?") && args?.firstOrNull() == "42" ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "DirectChat",
                                        "link_id" to null,
                                        "active_members_count" to "1",
                                        "meta" to """[{"type":"3","content":"Room Title"}]""",
                                        "members" to "[2]",
                                        "blinded_member_ids" to null,
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
            )

        val summary = repo.roomSummary(42L)

        requireNotNull(summary)
        assertEquals(42L, summary.chatId)
        assertEquals("DirectChat", summary.type)
        assertEquals("Room Title", summary.linkName)
    }

    @Test
    fun `roomInfo parses notices and blinded ids from room metadata`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery == "SELECT id, type, link_id, meta, blinded_member_ids FROM chat_rooms WHERE id = ?" ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "OM",
                                        "link_id" to null,
                                        "meta" to
                                            """
                                            {
                                              "noticeActivityContents": [
                                                {"message":"공지", "authorId":123, "createdAt":456}
                                              ]
                                            }
                                            """.trimIndent(),
                                        "blinded_member_ids" to "[7,8]",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                botId = 1L,
            )

        val response = repo.roomInfo(42L)

        assertEquals(42L, response.chatId)
        assertEquals("OM", response.type)
        assertEquals(listOf("공지"), response.notices.map { it.content })
        assertEquals(listOf(123L), response.notices.map { it.authorId })
        assertEquals(listOf(456L), response.notices.map { it.updatedAt })
        assertEquals(listOf(7L, 8L), response.blindedMemberIds)
    }

    @Test
    fun `roomInfo coalesces notices and blinded ids through one native parser batch`() {
        withNativeParserResponses(
            """
            {
              "items": [
                {
                  "kind":"notices",
                  "ok":true,
                  "fallback":false,
                  "notices":[{"content":"점검","authorId":7,"updatedAt":100}]
                },
                {"kind":"idArray","ok":true,"fallback":false,"ids":[8,9]}
              ]
            }
            """.trimIndent(),
        ) { jni, runtime ->
            val repo =
                buildRepoFromLegacy(
                    executeQueryTyped =
                        legacyQuery { sqlQuery, _, _ ->
                            when {
                                sqlQuery == "SELECT id, type, link_id, meta, blinded_member_ids FROM chat_rooms WHERE id = ?" ->
                                    listOf(
                                        mapOf(
                                            "id" to "42",
                                            "type" to "OM",
                                            "link_id" to null,
                                            "meta" to "native-meta",
                                            "blinded_member_ids" to "[1]",
                                        ),
                                    )
                                else -> emptyList()
                            }
                        },
                    botId = 1L,
                )

            val response = repo.roomInfo(42L)
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals(listOf("점검"), response.notices.map { it.content })
            assertEquals(listOf(8L, 9L), response.blindedMemberIds)
            assertEquals(1, jni.parserCalls)
            assertEquals(1L, parserStats.jniCalls)
            assertEquals(2L, parserStats.items)
        }
    }

    @Test
    fun `roomInfo falls back to empty metadata for malformed notices and blinded ids`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery == "SELECT id, type, link_id, meta, blinded_member_ids FROM chat_rooms WHERE id = ?" ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "OM",
                                        "link_id" to null,
                                        "meta" to "{not-json",
                                        "blinded_member_ids" to "not-json",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                botId = 1L,
            )

        val response = repo.roomInfo(42L)

        assertEquals(emptyList(), response.notices)
        assertEquals(emptyList(), response.blindedMemberIds)
    }

    @Test
    fun `roomStats keeps totals before top member limit`() {
        val chatId = 100L
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" -> listOf(mapOf("link_id" to "777"))
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "3"))
                            sqlQuery.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                                listOf(
                                    mapOf("user_id" to "1", "type" to "0", "cnt" to "10", "last_active" to "1000"),
                                    mapOf("user_id" to "2", "type" to "0", "cnt" to "7", "last_active" to "900"),
                                    mapOf("user_id" to "3", "type" to "0", "cnt" to "3", "last_active" to "800"),
                                )
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("WHERE om.link_id = ? AND om.user_id IN (?, ?, ?)") &&
                                bindArgs?.toList() == listOf("777", "1", "2", "3") ->
                                listOf(
                                    openNicknameRow("1", "user-1-link-777"),
                                    openNicknameRow("2", "user-2-link-777"),
                                    openNicknameRow("3", "user-3-link-777"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val stats = repo.roomStats(chatId = chatId, period = "all", limit = 2)

        assertEquals(20, stats.totalMessages)
        assertEquals(3, stats.activeMembers)
        assertEquals(2, stats.topMembers.size)
        assertEquals(listOf(1L, 2L), stats.topMembers.map { it.userId })
        assertTrue(stats.topMembers.all { it.userId != 3L || it.messageCount == 3 })
        assertEquals("user-1-link-777", stats.topMembers.first().nickname)
    }

    @Test
    fun `roomStats applies minMessages filter before totals`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" -> listOf(mapOf("link_id" to "777"))
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                            sqlQuery.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                                listOf(
                                    mapOf("user_id" to "1", "type" to "0", "cnt" to "10", "last_active" to "1000"),
                                    mapOf("user_id" to "2", "type" to "0", "cnt" to "7", "last_active" to "900"),
                                    mapOf("user_id" to "3", "type" to "0", "cnt" to "3", "last_active" to "800"),
                                )
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("WHERE om.user_id = ? AND om.link_id = ?") ->
                                listOf(openNicknameRow("0", "user"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val stats = repo.roomStats(chatId = 100L, period = "all", limit = 10, minMessages = 5)

        assertEquals(17, stats.totalMessages)
        assertEquals(2, stats.activeMembers)
        assertEquals(listOf(1L, 2L), stats.topMembers.map { it.userId })
    }

    @Test
    fun `roomStats resolves open member nicknames in batch`() {
        var batchNicknameQueryCount = 0
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" -> listOf(mapOf("link_id" to "777"))
                            sqlQuery.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                                listOf(
                                    mapOf("user_id" to "1", "type" to "0", "cnt" to "10", "last_active" to "1000"),
                                    mapOf("user_id" to "2", "type" to "0", "cnt" to "7", "last_active" to "900"),
                                )
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("WHERE om.link_id = ? AND om.user_id IN (?, ?)") &&
                                bindArgs?.toList() == listOf("777", "1", "2") -> {
                                batchNicknameQueryCount++
                                listOf(
                                    openNicknameRow("1", "alice"),
                                    openNicknameRow("2", "bob"),
                                )
                            }
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("WHERE om.user_id = ? AND om.link_id = ?") ->
                                error("roomStats should not resolve nicknames with per-user queries")
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val stats = repo.roomStats(chatId = 100L, period = "all", limit = 2)

        assertEquals(1, batchNicknameQueryCount)
        assertEquals(listOf("alice", "bob"), stats.topMembers.map { it.nickname })
    }

    @Test
    fun `roomStats resolves non open member nicknames in batch ownership`() {
        var friendBatchQueryCount = 0
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" ->
                                listOf(mapOf("link_id" to null))
                            sqlQuery.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                                listOf(
                                    mapOf("user_id" to "203887151", "type" to "0", "cnt" to "10", "last_active" to "1002"),
                                    mapOf("user_id" to "243338321", "type" to "0", "cnt" to "7", "last_active" to "1001"),
                                    mapOf("user_id" to "438562408", "type" to "0", "cnt" to "3", "last_active" to "1000"),
                                )
                            sqlQuery == "SELECT id, name, enc FROM db2.friends WHERE id IN (?,?,?)" &&
                                bindArgs?.toList() == listOf("203887151", "243338321", "438562408") -> {
                                friendBatchQueryCount++
                                listOf(
                                    mapOf("id" to "203887151", "name" to "재균", "enc" to "0"),
                                    mapOf("id" to "243338321", "name" to "서윤", "enc" to "0"),
                                    mapOf("id" to "438562408", "name" to "봇", "enc" to "0"),
                                )
                            }
                            sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" ->
                                error("roomStats should not resolve non open nicknames with per-user queries")
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val stats = repo.roomStats(chatId = 366795577484293L, period = "all", limit = 3)

        assertEquals(1, friendBatchQueryCount)
        assertEquals(listOf("재균", "서윤", "봇"), stats.topMembers.map { it.nickname })
    }

    @Test
    fun `listMembers includes current member activity metadata without former members`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" -> listOf(mapOf("link_id" to "777"))
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("LEFT JOIN db2.open_profile op") &&
                                sqlQuery.contains("WHERE om.link_id = ?") ->
                                listOf(
                                    openMemberRow("1", "alice", linkMemberType = "1"),
                                    openMemberRow("2", "bob"),
                                )
                            sqlQuery.contains("SELECT user_id, COUNT(*) as message_count, MAX(created_at) as last_active") ->
                                listOf(
                                    mapOf("user_id" to "1", "message_count" to "10", "last_active" to "1000"),
                                    mapOf("user_id" to "3", "message_count" to "99", "last_active" to "5000"),
                                )
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("WHERE om.user_id = ? AND om.link_id = ?") ->
                                listOf(openNicknameRow(bindArgs?.get(0).orEmpty(), "user-${bindArgs?.get(0)}"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val response = repo.listMembers(chatId = 100L)
        val members =
            response.members.associateBy { it.userId }

        assertEquals(setOf(1L, 2L), members.keys)
        assertEquals(10, members.getValue(1L).messageCount)
        assertEquals(1000L, members.getValue(1L).lastActiveAt)
        assertEquals(0, members.getValue(2L).messageCount)
        assertEquals(null, members.getValue(2L).lastActiveAt)
    }

    @Test
    fun `listMembers scopes activity aggregation to current member ids`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("LEFT JOIN db2.open_profile op") &&
                                sqlQuery.contains("WHERE om.link_id = ?") ->
                                listOf(
                                    openMemberRow("1", "alice", linkMemberType = "1"),
                                    openMemberRow("2", "bob"),
                                )
                            sqlQuery.contains("FROM chat_logs") &&
                                sqlQuery.contains("user_id IN (?, ?)") &&
                                bindArgs?.take(3) == listOf("100", "1", "2") ->
                                listOf(
                                    mapOf("user_id" to "1", "message_count" to "10", "last_active" to "1000"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val response = repo.listMembers(chatId = 100L)
        val members = response.members.associateBy { it.userId }

        assertEquals(10, members.getValue(1L).messageCount)
        assertEquals(0, members.getValue(2L).messageCount)
    }

    @Test
    fun `listMembers reuses activity aggregation for immediate repeated reads`() {
        var activityQueryCount = 0
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("LEFT JOIN db2.open_profile op") &&
                                sqlQuery.contains("WHERE om.link_id = ?") ->
                                listOf(
                                    openMemberRow("1", "alice", linkMemberType = "1"),
                                    openMemberRow("2", "bob"),
                                )
                            sqlQuery.contains("FROM chat_logs") &&
                                sqlQuery.contains("user_id IN (?, ?)") &&
                                bindArgs?.toList() == listOf("100", "1", "2") -> {
                                activityQueryCount++
                                listOf(
                                    mapOf("user_id" to "1", "message_count" to "10", "last_active" to "1000"),
                                )
                            }
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        repo.listMembers(chatId = 100L)
        repo.listMembers(chatId = 100L)

        assertEquals(1, activityQueryCount)
    }

    @Test
    fun `listRooms deduplicates same open link and prefers positive chat id`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { _, _, _ ->
                        listOf(
                            mapOf(
                                "id" to "-1774565104303",
                                "type" to "OD",
                                "active_members_count" to "2",
                                "link_id" to "455150406",
                                "link_name" to "카푸",
                                "link_url" to "https://open.kakao.com/o/s5Ee5Tmi",
                                "member_limit" to null,
                                "searchable" to "1",
                                "bot_role" to "1",
                            ),
                            mapOf(
                                "id" to "18478615493603057",
                                "type" to "OD",
                                "active_members_count" to "2",
                                "link_id" to "455150406",
                                "link_name" to "카푸",
                                "link_url" to "https://open.kakao.com/o/s5Ee5Tmi",
                                "member_limit" to null,
                                "searchable" to "1",
                                "bot_role" to "1",
                            ),
                        )
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val rooms = repo.listRooms().rooms

        assertEquals(1, rooms.size)
        assertEquals(18478615493603057L, rooms.first().chatId)
        assertEquals(455150406L, rooms.first().linkId)
    }

    @Test
    fun `listRooms includes non open rooms with resolved display names`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("FROM chat_rooms cr") ->
                                listOf(
                                    mapOf(
                                        "id" to "366795577484293",
                                        "type" to "MultiChat",
                                        "active_members_count" to "6",
                                        "link_id" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                        "meta" to """[{"type":3,"content":"홀붕이"}]""",
                                        "members" to "[27126526,203887151,222348861]",
                                    ),
                                    mapOf(
                                        "id" to "464252100463241",
                                        "type" to "DirectChat",
                                        "active_members_count" to "2",
                                        "link_id" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                        "meta" to "[]",
                                        "members" to "[267947734]",
                                    ),
                                )
                            sqlQuery.contains("SELECT id, name, enc FROM db2.friends WHERE id IN (?)") && bindArgs?.toList() == listOf("267947734") ->
                                listOf(mapOf("id" to "267947734", "name" to "카푸", "enc" to "0"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val rooms = repo.listRooms().rooms

        assertEquals(listOf("홀붕이", "카푸"), rooms.map { it.linkName })
        assertEquals(listOf("MultiChat", "DirectChat"), rooms.map { it.type })
    }

    @Test
    fun `listMembers returns non open members from members and chat logs`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" ->
                                listOf(mapOf("link_id" to null))
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "DirectChat",
                                        "members" to "[267947734]",
                                        "active_members_count" to "2",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") && sqlQuery.contains("GROUP BY user_id") ->
                                listOf(
                                    mapOf("user_id" to "267947734", "message_count" to "1", "last_active" to "1000"),
                                    mapOf("user_id" to "438562408", "message_count" to "1", "last_active" to "1001"),
                                )
                            sqlQuery == "SELECT id, name, enc FROM db2.friends WHERE id IN (?,?)" &&
                                bindArgs?.toList() == listOf("438562408", "267947734") ->
                                listOf(
                                    mapOf("id" to "267947734", "name" to "카푸", "enc" to "0"),
                                    mapOf("id" to "438562408", "name" to "봇", "enc" to "0"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val response = repo.listMembers(chatId = 464252100463241L)
        val members = response.members.associateBy { it.userId }

        assertEquals(2, response.totalCount)
        assertEquals(setOf(267947734L, 438562408L), members.keys)
        assertEquals("카푸", members.getValue(267947734L).nickname)
        assertEquals("member", members.getValue(267947734L).role)
        assertEquals("bot", members.getValue(438562408L).role)
    }

    @Test
    fun `listMembers resolves non open nicknames in batch ownership`() {
        var friendBatchQueryCount = 0
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "MultiChat",
                                        "members" to "[203887151,243338321]",
                                        "active_members_count" to "3",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") &&
                                sqlQuery.contains("user_id IN (?, ?, ?)") &&
                                bindArgs?.toList() == listOf("366795577484293", "203887151", "243338321", "438562408") ->
                                listOf(
                                    mapOf("user_id" to "203887151", "message_count" to "10", "last_active" to "1002"),
                                    mapOf("user_id" to "243338321", "message_count" to "7", "last_active" to "1001"),
                                    mapOf("user_id" to "438562408", "message_count" to "3", "last_active" to "1000"),
                                )
                            sqlQuery == "SELECT id, name, enc FROM db2.friends WHERE id IN (?,?,?)" &&
                                bindArgs?.toList() == listOf("203887151", "243338321", "438562408") -> {
                                friendBatchQueryCount++
                                listOf(
                                    mapOf("id" to "203887151", "name" to "재균", "enc" to "0"),
                                    mapOf("id" to "243338321", "name" to "서윤", "enc" to "0"),
                                    mapOf("id" to "438562408", "name" to "봇", "enc" to "0"),
                                )
                            }
                            sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" ->
                                error("listMembers should not resolve non open nicknames with per-user queries")
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { _, _ -> },
            )

        val response = repo.listMembers(chatId = 366795577484293L)
        val members = response.members.associateBy { it.userId }

        assertEquals(1, friendBatchQueryCount)
        assertEquals("재균", members.getValue(203887151L).nickname)
        assertEquals("서윤", members.getValue(243338321L).nickname)
        assertEquals("봇", members.getValue(438562408L).nickname)
    }

    @Test
    fun `listMembers falls back to user id string when non open nickname sources are missing`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "DirectChat",
                                        "members" to "[267947734]",
                                        "active_members_count" to "2",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") && sqlQuery.contains("GROUP BY user_id") ->
                                listOf(
                                    mapOf("user_id" to "267947734", "message_count" to "1", "last_active" to "1000"),
                                    mapOf("user_id" to "438562408", "message_count" to "1", "last_active" to "1001"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val response = repo.listMembers(chatId = 464252100463241L)
        val members = response.members.associateBy { it.userId }

        assertEquals("267947734", members.getValue(267947734L).nickname)
        assertEquals("438562408", members.getValue(438562408L).nickname)
    }

    @Test
    fun `listRooms falls back to member id when direct chat name sources are missing`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery.contains("FROM chat_rooms cr") ->
                                listOf(
                                    mapOf(
                                        "id" to "464252100463241",
                                        "type" to "DirectChat",
                                        "active_members_count" to "2",
                                        "link_id" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                        "meta" to "[]",
                                        "members" to "[267947734]",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val rooms = repo.listRooms().rooms

        assertEquals(listOf("267947734"), rooms.map { it.linkName })
    }

    @Test
    fun `listRooms uses observed profile room name for direct chat fallback`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("FROM chat_rooms cr") ->
                                listOf(
                                    mapOf(
                                        "id" to "464252100463241",
                                        "type" to "DirectChat",
                                        "active_members_count" to "2",
                                        "link_id" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                        "meta" to "[]",
                                        "members" to "[267947734]",
                                    ),
                                )
                            sqlQuery.contains("FROM db3.observed_profiles") &&
                                bindArgs?.toList() == listOf("464252100463241") ->
                                listOf(
                                    mapOf(
                                        "chat_id" to "464252100463241",
                                        "display_name" to "박준우",
                                        "room_name" to "박준우",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val rooms = repo.listRooms().rooms

        assertEquals(listOf("박준우"), rooms.map { it.linkName })
    }

    @Test
    fun `listRooms skips member id batch when observed profile resolves room name`() {
        withNativeParserResponses(
            """
            {
              "items": [
                {"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":null}
              ]
            }
            """.trimIndent(),
        ) { jni, runtime ->
            val repo =
                buildRepoFromLegacy(
                    executeQueryTyped =
                        legacyQuery { sqlQuery, bindArgs, _ ->
                            when {
                                sqlQuery.contains("FROM chat_rooms cr") ->
                                    listOf(
                                        mapOf(
                                            "id" to "464252100463241",
                                            "type" to "DirectChat",
                                            "active_members_count" to "2",
                                            "link_id" to null,
                                            "link_name" to null,
                                            "link_url" to null,
                                            "member_limit" to null,
                                            "searchable" to null,
                                            "bot_role" to null,
                                            "meta" to "[]",
                                            "members" to "not-json",
                                        ),
                                    )
                                sqlQuery.contains("FROM db3.observed_profiles") &&
                                    bindArgs?.toList() == listOf("464252100463241") ->
                                    listOf(
                                        mapOf(
                                            "chat_id" to "464252100463241",
                                            "display_name" to "박준우",
                                            "room_name" to "박준우",
                                        ),
                                    )
                                else -> emptyList()
                            }
                        },
                    decrypt = { _, raw, _ -> raw },
                    botId = 438562408L,
                )

            val rooms = repo.listRooms().rooms
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals(listOf("박준우"), rooms.map { it.linkName })
            assertEquals(1, jni.parserCalls)
            assertEquals(1L, parserStats.jniCalls)
            assertEquals(1L, parserStats.items)
        }
    }

    @Test
    fun `listRooms batches non open member id parsing through native parser`() {
        withNativeParserResponses(
            """
            {
              "items": [
                {"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":null},
                {"kind":"roomTitle","ok":true,"fallback":false,"roomTitle":null}
              ]
            }
            """.trimIndent(),
            """
            {
              "items": [
                {"kind":"idArray","ok":true,"fallback":false,"ids":[100]},
                {"kind":"idArray","ok":true,"fallback":false,"ids":[200]}
              ]
            }
            """.trimIndent(),
        ) { jni, runtime ->
            val repo =
                buildRepoFromLegacy(
                    executeQueryTyped =
                        legacyQuery { sqlQuery, bindArgs, _ ->
                            when {
                                sqlQuery.contains("FROM chat_rooms cr") ->
                                    listOf(
                                        mapOf(
                                            "id" to "1",
                                            "type" to "DirectChat",
                                            "active_members_count" to "2",
                                            "link_id" to null,
                                            "link_name" to null,
                                            "link_url" to null,
                                            "member_limit" to null,
                                            "searchable" to null,
                                            "bot_role" to null,
                                            "meta" to "[]",
                                            "members" to "not-json-a",
                                        ),
                                        mapOf(
                                            "id" to "2",
                                            "type" to "DirectChat",
                                            "active_members_count" to "2",
                                            "link_id" to null,
                                            "link_name" to null,
                                            "link_url" to null,
                                            "member_limit" to null,
                                            "searchable" to null,
                                            "bot_role" to null,
                                            "meta" to "[]",
                                            "members" to "not-json-b",
                                        ),
                                    )
                                sqlQuery == "SELECT id, name, enc FROM db2.friends WHERE id IN (?)" &&
                                    bindArgs?.toList() == listOf("100") ->
                                    listOf(mapOf("id" to "100", "name" to "Alice", "enc" to "0"))
                                sqlQuery == "SELECT id, name, enc FROM db2.friends WHERE id IN (?)" &&
                                    bindArgs?.toList() == listOf("200") ->
                                    listOf(mapOf("id" to "200", "name" to "Bob", "enc" to "0"))
                                else -> emptyList()
                            }
                        },
                    decrypt = { _, raw, _ -> raw },
                    botId = 438562408L,
                )

            val rooms = repo.listRooms().rooms
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals(listOf("Alice", "Bob"), rooms.map { it.linkName })
            assertEquals(2, jni.parserCalls, jni.parserRequests.joinToString(separator = "\n"))
            assertEquals(2L, parserStats.jniCalls)
            assertEquals(4L, parserStats.items)
        }
    }

    @Test
    fun `listRooms falls back to room type when members array is malformed`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery.contains("FROM chat_rooms cr") ->
                                listOf(
                                    mapOf(
                                        "id" to "464252100463241",
                                        "type" to "DirectChat",
                                        "active_members_count" to "2",
                                        "link_id" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                        "meta" to "[]",
                                        "members" to "not-json",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val rooms = repo.listRooms().rooms

        assertEquals(listOf("DirectChat"), rooms.map { it.linkName })
    }

    @Test
    fun `listMembers uses observed profile display name for direct chat participant`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "DirectChat",
                                        "members" to "[267947734]",
                                        "active_members_count" to "2",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") && sqlQuery.contains("GROUP BY user_id") ->
                                listOf(
                                    mapOf("user_id" to "267947734", "message_count" to "1", "last_active" to "1000"),
                                    mapOf("user_id" to "438562408", "message_count" to "1", "last_active" to "1001"),
                                )
                            sqlQuery.contains("FROM db3.observed_profiles") &&
                                bindArgs?.toList() == listOf("464252100463241") ->
                                listOf(
                                    mapOf(
                                        "display_name" to "박준우",
                                        "room_name" to "박준우",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { _, _ -> },
            )

        val response = repo.listMembers(chatId = 464252100463241L)
        val members = response.members.associateBy { it.userId }

        assertEquals("박준우", members.getValue(267947734L).nickname)
        assertEquals("438562408", members.getValue(438562408L).nickname)
    }

    @Test
    fun `listMembers uses learned observed profile display name fallback`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "MultiChat",
                                        "members" to "[203887151]",
                                        "active_members_count" to "1",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") && sqlQuery.contains("GROUP BY user_id") ->
                                listOf(
                                    mapOf("user_id" to "203887151", "message_count" to "1", "last_active" to "1000"),
                                )
                            sqlQuery == "SELECT id, name, enc FROM db2.friends WHERE id IN (?,?)" &&
                                bindArgs?.toList() == listOf("203887151", "438562408") ->
                                emptyList()
                            sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                                bindArgs?.toList() == listOf("366795577484293", "203887151", "438562408") ->
                                listOf(mapOf("user_id" to "203887151", "display_name" to "재균"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { _, _ -> },
            )

        val response = repo.listMembers(chatId = 366795577484293L)
        val members = response.members.associateBy { it.userId }

        assertEquals("재균", members.getValue(203887151L).nickname)
    }

    @Test
    fun `resolveDisplayName uses learned observed profile fallback for non open room`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" -> emptyList()
                            sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                                bindArgs?.toList() == listOf("366795577484293", "203887151") ->
                                listOf(mapOf("user_id" to "203887151", "display_name" to "재균"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        assertEquals(
            "재균",
            repo.resolveDisplayName(
                userId = 203887151L,
                chatId = 366795577484293L,
            ),
        )
    }

    @Test
    fun `listMembers scopes non open activity aggregation to current members and bot`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "MultiChat",
                                        "members" to "[203887151,243338321]",
                                        "active_members_count" to "3",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") &&
                                sqlQuery.contains("user_id IN (?, ?, ?)") &&
                                bindArgs?.toList() == listOf("366795577484293", "203887151", "243338321", "438562408") ->
                                listOf(
                                    mapOf("user_id" to "203887151", "message_count" to "10", "last_active" to "1000"),
                                    mapOf("user_id" to "438562408", "message_count" to "2", "last_active" to "1001"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { _, _ -> },
            )

        val response = repo.listMembers(chatId = 366795577484293L)
        val members = response.members.associateBy { it.userId }

        assertEquals(setOf(203887151L, 243338321L, 438562408L), members.keys)
        assertEquals(10, members.getValue(203887151L).messageCount)
        assertEquals(0, members.getValue(243338321L).messageCount)
    }

    @Test
    fun `listMembers reuses non open activity cache for repeated reads`() {
        var activityQueryCount = 0
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "MultiChat",
                                        "members" to "[203887151,243338321]",
                                        "active_members_count" to "3",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") &&
                                sqlQuery.contains("user_id IN (?, ?, ?)") &&
                                bindArgs?.toList() == listOf("366795577484293", "203887151", "243338321", "438562408") -> {
                                activityQueryCount++
                                listOf(
                                    mapOf("user_id" to "203887151", "message_count" to "10", "last_active" to "1000"),
                                )
                            }
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { _, _ -> },
            )

        repo.listMembers(chatId = 366795577484293L)
        repo.listMembers(chatId = 366795577484293L)

        assertEquals(1, activityQueryCount)
    }

    @Test
    fun `listMembers learns observed profile mappings from visible open member names`() {
        var learnedChatId: Long? = null
        var learnedNames: Map<Long, String>? = null
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(mapOf("link_id" to "777", "type" to "OD", "members" to "[]", "active_members_count" to "1"))
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("LEFT JOIN db2.open_profile op") &&
                                sqlQuery.contains("WHERE om.link_id = ?") ->
                                listOf(
                                    openMemberRow("6729110572752592365", "박준우"),
                                )
                            sqlQuery.contains("FROM chat_logs") && sqlQuery.contains("GROUP BY user_id") ->
                                listOf(
                                    mapOf(
                                        "user_id" to "6729110572752592365",
                                        "message_count" to "1",
                                        "last_active" to "1000",
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { chatId, names ->
                    learnedChatId = chatId
                    learnedNames = names
                },
            )

        repo.listMembers(chatId = 18478615493603057L)

        assertEquals(18478615493603057L, learnedChatId)
        assertEquals(mapOf(6729110572752592365L to "박준우"), learnedNames)
    }

    @Test
    fun `listMembers excludes friend resolved users from observed profile learning`() {
        var learnedNames: Map<Long, String>? = null
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                                listOf(
                                    mapOf(
                                        "link_id" to null,
                                        "type" to "MultiChat",
                                        "members" to "[203887151,243338321]",
                                        "active_members_count" to "2",
                                    ),
                                )
                            sqlQuery.contains("FROM chat_logs") && sqlQuery.contains("GROUP BY user_id") ->
                                listOf(
                                    mapOf("user_id" to "203887151", "message_count" to "5", "last_active" to "1001"),
                                    mapOf("user_id" to "243338321", "message_count" to "3", "last_active" to "1000"),
                                )
                            sqlQuery.contains("SELECT id, name, enc FROM db2.friends WHERE id IN") ->
                                if (bindArgs?.contains("203887151") == true) {
                                    listOf(mapOf("id" to "203887151", "name" to "재균", "enc" to "0"))
                                } else {
                                    emptyList()
                                }
                            sqlQuery.contains("FROM db3.observed_profile_user_links") ->
                                if (bindArgs?.contains("243338321") == true) {
                                    listOf(mapOf("user_id" to "243338321", "display_name" to "서윤"))
                                } else {
                                    emptyList()
                                }
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { _, names ->
                    learnedNames = names
                },
            )

        repo.listMembers(chatId = 366795577484293L)

        // 친구 DB로 해석된 203887151("재균")은 제외, observed profile로 해석된 243338321만 학습
        assertEquals(mapOf(243338321L to "서윤"), learnedNames)
    }

    @Test
    fun `snapshot learns observed profile mappings from open member names`() {
        var learnedChatId: Long? = null
        var learnedNames: Map<Long, String>? = null
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, _, _ ->
                        when {
                            sqlQuery == "SELECT id, type, members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" ->
                                listOf(
                                    mapOf(
                                        "id" to "18478615493603057",
                                        "type" to "OM",
                                        "members" to "[6729110572752592365]",
                                        "blinded_member_ids" to "[]",
                                        "link_id" to "455150406",
                                    ),
                                )
                            sqlQuery.contains("FROM db2.open_chat_member om") &&
                                sqlQuery.contains("LEFT JOIN db2.open_profile op") &&
                                sqlQuery.contains("WHERE om.link_id = ?") ->
                                listOf(
                                    openMemberRow("6729110572752592365", "박준우"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
                learnObservedProfileUserMappings = { chatId, names ->
                    learnedChatId = chatId
                    learnedNames = names
                },
            )

        repo.snapshot(chatId = 18478615493603057L)

        assertEquals(18478615493603057L, learnedChatId)
        assertEquals(mapOf(6729110572752592365L to "박준우"), learnedNames)
    }

    @Test
    fun `snapshot uses learned observed profile fallback for non open members`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sqlQuery, bindArgs, _ ->
                        when {
                            sqlQuery == "SELECT id, type, members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" ->
                                listOf(
                                    mapOf(
                                        "id" to "366795577484293",
                                        "type" to "MultiChat",
                                        "members" to "[203887151,243338321]",
                                        "blinded_member_ids" to "[]",
                                        "link_id" to null,
                                    ),
                                )
                            sqlQuery.contains("SELECT id, name, enc FROM db2.friends WHERE id IN (?,?)") &&
                                bindArgs?.toList() == listOf("203887151", "243338321") ->
                                emptyList()
                            sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                                bindArgs?.toList() == listOf("366795577484293", "203887151", "243338321") ->
                                listOf(mapOf("user_id" to "203887151", "display_name" to "재균"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val snapshot = repo.snapshot(chatId = 366795577484293L)

        val present = snapshot as RoomSnapshotReadResult.Present
        assertEquals("재균", present.snapshot.nicknames[UserId(203887151L)])
        assertEquals(null, present.snapshot.nicknames[UserId(243338321L)])
    }

    @Test
    fun `snapshot coalesces member and blinded id parsing through one native parser batch`() {
        withNativeParserResponses(
            """
            {
              "items": [
                {"kind":"idArray","ok":true,"fallback":false,"ids":[10,20]},
                {"kind":"idArray","ok":true,"fallback":false,"ids":[99]}
              ]
            }
            """.trimIndent(),
        ) { jni, runtime ->
            val repo =
                buildRepoFromLegacy(
                    executeQueryTyped =
                        legacyQuery { sqlQuery, _, _ ->
                            when {
                                sqlQuery == "SELECT id, type, members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" ->
                                    listOf(
                                        mapOf(
                                            "id" to "42",
                                            "type" to "MultiChat",
                                            "members" to "native-members",
                                            "blinded_member_ids" to "native-blinded",
                                            "link_id" to null,
                                        ),
                                    )
                                else -> emptyList()
                            }
                        },
                    decrypt = { _, raw, _ -> raw },
                    botId = 1L,
                )

            val snapshot = repo.snapshot(chatId = 42L)
            val present = snapshot as RoomSnapshotReadResult.Present
            val parserStats = runtime.diagnostics().componentStats.getValue("parsers")

            assertEquals(setOf(UserId(10L), UserId(20L)), present.snapshot.memberIds)
            assertEquals(setOf(UserId(99L)), present.snapshot.blindedIds)
            assertEquals(1, jni.parserCalls)
            assertEquals(1L, parserStats.jniCalls)
            assertEquals(2L, parserStats.items)
        }
    }

    @Test
    fun `snapshot returns missing when room row is absent`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped = legacyQuery { _, _, _ -> emptyList() },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val snapshot = repo.snapshot(chatId = 999L)

        assertEquals(RoomSnapshotReadResult.Missing, snapshot)
    }
}
