package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.LinkId
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
import kotlin.test.assertFalse
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

class MemberRepositoryQueryTest {
    @Test
    fun `observed profile query uses chat_id equality not LIKE`() {
        val executedQueries = mutableListOf<Pair<String, List<String?>>>()
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, args, _ ->
                        executedQueries.add(sql to (args?.toList() ?: emptyList()))
                        when {
                            sql.contains("FROM chat_rooms cr") ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "DirectChat",
                                        "active_members_count" to "1",
                                        "link_id" to null,
                                        "meta" to null,
                                        "members" to "[2]",
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        repo.listRooms()

        val profileQuery = executedQueries.find { it.first.contains("observed_profiles") }
        requireNotNull(profileQuery) { "expected observed_profiles query to execute" }
        assertFalse(
            profileQuery.first.contains("LIKE"),
            "observed_profiles query should use chat_id = ? not LIKE: ${profileQuery.first}",
        )
        assertEquals(listOf("42"), profileQuery.second)
    }

    @Test
    fun `batch nickname resolution reduces query count`() {
        val queryCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, args, _ ->
                        queryCount.incrementAndGet()
                        when {
                            sql.contains("open_chat_member") -> {
                                val userIds = args?.drop(1)?.mapNotNull { it?.toLongOrNull() } ?: emptyList()
                                userIds.map { uid ->
                                    mapOf("user_id" to uid.toString(), "nickname" to "open-$uid", "enc" to "0")
                                }
                            }
                            sql.contains("friends") -> {
                                val userIds = args?.mapNotNull { it?.toLongOrNull() } ?: emptyList()
                                userIds.map { uid ->
                                    mapOf("id" to uid.toString(), "name" to "friend-$uid", "enc" to "0")
                                }
                            }
                            else -> emptyList()
                        }
                    },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        val result =
            repo.resolveNicknamesBatch(
                userIds = listOf(1L, 2L, 3L, 4L, 5L).map(::UserId),
                linkId = LinkId(10L),
                chatId = ChatId(42L),
            )

        assertEquals(5, result.size)
        assertEquals("open-1", result[UserId(1L)])
        assertTrue(queryCount.get() <= 2, "Expected at most 2 queries, got ${queryCount.get()}")
    }

    @Test
    fun `batch nickname falls back through open, friends, observed`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, _, _ ->
                        when {
                            sql.contains("open_chat_member") ->
                                listOf(mapOf("user_id" to "1", "nickname" to "open-nick", "enc" to "0"))
                            sql.contains("friends") ->
                                listOf(mapOf("id" to "2", "name" to "friend-nick", "enc" to "0"))
                            sql.contains("observed_profile_user_links") ->
                                listOf(mapOf("user_id" to "3", "display_name" to "observed-nick"))
                            else -> emptyList()
                        }
                    },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        val result =
            repo.resolveNicknamesBatch(
                userIds = listOf(1L, 2L, 3L, 4L).map(::UserId),
                linkId = LinkId(10L),
                chatId = ChatId(42L),
            )

        assertEquals("open-nick", result[UserId(1L)])
        assertEquals("friend-nick", result[UserId(2L)])
        assertEquals("observed-nick", result[UserId(3L)])
        assertEquals("4", result[UserId(4L)], "Unresolved userId should fall back to string")
    }

    @Test
    fun `MemberRepository accepts QueryExecutionResult directly`() {
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped = { _, _, _ ->
                    QueryExecutionResult(
                        columns =
                            listOf(
                                QueryColumn(name = "id", sqliteType = "TEXT"),
                                QueryColumn(name = "type", sqliteType = "TEXT"),
                                QueryColumn(name = "members", sqliteType = "TEXT"),
                                QueryColumn(name = "link_id", sqliteType = "TEXT"),
                                QueryColumn(name = "active_members_count", sqliteType = "TEXT"),
                                QueryColumn(name = "meta", sqliteType = "TEXT"),
                                QueryColumn(name = "link_name", sqliteType = "TEXT"),
                                QueryColumn(name = "link_url", sqliteType = "TEXT"),
                                QueryColumn(name = "member_limit", sqliteType = "TEXT"),
                                QueryColumn(name = "searchable", sqliteType = "TEXT"),
                                QueryColumn(name = "bot_role", sqliteType = "TEXT"),
                            ),
                        rows =
                            listOf(
                                listOf(
                                    JsonPrimitive("42"),
                                    JsonPrimitive("0"),
                                    JsonPrimitive("[1,2]"),
                                    null,
                                    JsonPrimitive("2"),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                ),
                            ),
                    )
                },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        val rooms = repo.listRooms()
        assertEquals(1, rooms.rooms.size)
        assertEquals(42L, rooms.rooms[0].chatId)
    }

    @Test
    fun `listMembers query count is bounded`() {
        val queryCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, _, _ ->
                        queryCount.incrementAndGet()
                        when {
                            sql.contains("chat_rooms") ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "MultiChat",
                                        "active_members_count" to "3",
                                        "link_id" to null,
                                        "meta" to null,
                                        "members" to "[10,20,30]",
                                        "blinded_member_ids" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                    ),
                                )
                            sql.contains("friends") ->
                                listOf(
                                    mapOf("id" to "10", "name" to "Alice", "enc" to "0"),
                                    mapOf("id" to "20", "name" to "Bob", "enc" to "0"),
                                    mapOf("id" to "30", "name" to "Carol", "enc" to "0"),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        queryCount.set(0)
        val result = repo.listMembers(42L)
        assertTrue(result.members.isNotEmpty(), "listMembers should return members")
        assertTrue(queryCount.get() <= 6, "listMembers should use bounded queries, got ${queryCount.get()}")
    }

    @Test
    fun `roomSummary query count is bounded`() {
        val queryCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, _, _ ->
                        queryCount.incrementAndGet()
                        when {
                            sql.contains("chat_rooms") ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "MultiChat",
                                        "active_members_count" to "3",
                                        "link_id" to null,
                                        "meta" to null,
                                        "members" to "[10,20]",
                                        "blinded_member_ids" to null,
                                        "link_name" to null,
                                        "link_url" to null,
                                        "member_limit" to null,
                                        "searchable" to null,
                                        "bot_role" to null,
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        queryCount.set(0)
        val result = repo.roomSummary(42L)
        assertEquals(42L, result?.chatId)
        assertTrue(queryCount.get() <= 4, "roomSummary should use bounded queries, got ${queryCount.get()}")
    }

    @Test
    fun `roomStats query count is bounded`() {
        val queryCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val repo =
            buildRepoFromLegacy(
                executeQueryTyped =
                    legacyQuery { sql, _, _ ->
                        queryCount.incrementAndGet()
                        when {
                            sql.contains("chat_rooms") ->
                                listOf(
                                    mapOf(
                                        "id" to "42",
                                        "type" to "MultiChat",
                                        "active_members_count" to "3",
                                        "link_id" to null,
                                        "meta" to null,
                                        "members" to "[10,20]",
                                        "blinded_member_ids" to null,
                                    ),
                                )
                            else -> emptyList()
                        }
                    },
                decrypt = { _, s, _ -> s },
                botId = 1L,
            )

        queryCount.set(0)
        val result = repo.roomStats(42L, "7d", 10)
        assertTrue(queryCount.get() <= 4, "roomStats should use bounded queries, got ${queryCount.get()}")
    }
}
