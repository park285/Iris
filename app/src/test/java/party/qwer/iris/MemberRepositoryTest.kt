package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            MemberRepository(
                executeQuery = { _, _, _ -> emptyList() },
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
            MemberRepository(
                executeQuery = { _, _, _ -> emptyList() },
                decrypt = { _, _, _ -> "" },
                botId = 1L,
            )
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("7d"))
        assertEquals(30 * 86400L, repo.parsePeriodSeconds("30d"))
        assertEquals(null, repo.parsePeriodSeconds("all"))
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("invalid"))
    }

    @Test
    fun `roomStats keeps totals before top member limit`() {
        val chatId = 100L
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
                        sqlQuery == "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1" ->
                            listOf(
                                mapOf(
                                    "nickname" to "user-${bindArgs?.get(0)}-link-${bindArgs?.get(1)}",
                                    "enc" to "0",
                                ),
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
            MemberRepository(
                executeQuery = { sqlQuery, _, _ ->
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
                        sqlQuery == "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1" ->
                            listOf(mapOf("nickname" to "user", "enc" to "0"))
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" -> listOf(mapOf("link_id" to "777"))
                        sqlQuery.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                            listOf(
                                mapOf("user_id" to "1", "type" to "0", "cnt" to "10", "last_active" to "1000"),
                                mapOf("user_id" to "2", "type" to "0", "cnt" to "7", "last_active" to "900"),
                            )
                        sqlQuery.contains("FROM db2.open_chat_member") &&
                            sqlQuery.contains("WHERE link_id = ? AND user_id IN (?, ?)") &&
                            bindArgs?.toList() == listOf("777", "1", "2") -> {
                            batchNicknameQueryCount++
                            listOf(
                                mapOf("user_id" to "1", "nickname" to "alice", "enc" to "0"),
                                mapOf("user_id" to "2", "nickname" to "bob", "enc" to "0"),
                            )
                        }
                        sqlQuery == "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1" ->
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
    fun `listMembers includes current member activity metadata without former members`() {
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery == "SELECT link_id FROM chat_rooms WHERE id = ?" -> listOf(mapOf("link_id" to "777"))
                        sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                            listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                        sqlQuery.contains("FROM db2.open_chat_member WHERE link_id = ?") ->
                            listOf(
                                mapOf(
                                    "user_id" to "1",
                                    "nickname" to "alice",
                                    "link_member_type" to "1",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
                                mapOf(
                                    "user_id" to "2",
                                    "nickname" to "bob",
                                    "link_member_type" to "2",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
                            )
                        sqlQuery.contains("SELECT user_id, COUNT(*) as message_count, MAX(created_at) as last_active") ->
                            listOf(
                                mapOf("user_id" to "1", "message_count" to "10", "last_active" to "1000"),
                                mapOf("user_id" to "3", "message_count" to "99", "last_active" to "5000"),
                            )
                        sqlQuery == "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1" ->
                            listOf(mapOf("nickname" to "user-${bindArgs?.get(0)}", "enc" to "0"))
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                            listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                        sqlQuery.contains("FROM db2.open_chat_member WHERE link_id = ?") ->
                            listOf(
                                mapOf(
                                    "user_id" to "1",
                                    "nickname" to "alice",
                                    "link_member_type" to "1",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
                                mapOf(
                                    "user_id" to "2",
                                    "nickname" to "bob",
                                    "link_member_type" to "2",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                            listOf(mapOf("link_id" to "777", "type" to "OM", "members" to "[]", "active_members_count" to "2"))
                        sqlQuery.contains("FROM db2.open_chat_member WHERE link_id = ?") ->
                            listOf(
                                mapOf(
                                    "user_id" to "1",
                                    "nickname" to "alice",
                                    "link_member_type" to "1",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
                                mapOf(
                                    "user_id" to "2",
                                    "nickname" to "bob",
                                    "link_member_type" to "2",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
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
            MemberRepository(
                executeQuery = { _, _, _ ->
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
                        sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" && bindArgs?.toList() == listOf("267947734") ->
                            listOf(mapOf("name" to "카푸", "enc" to "0"))
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
                        sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" && bindArgs?.toList() == listOf("267947734") ->
                            listOf(mapOf("name" to "카푸", "enc" to "0"))
                        sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" && bindArgs?.toList() == listOf("438562408") ->
                            listOf(mapOf("name" to "봇", "enc" to "0"))
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
    fun `listMembers falls back to user id string when non open nickname sources are missing`() {
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, _, _ ->
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
            MemberRepository(
                executeQuery = { sqlQuery, _, _ ->
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
                            bindArgs?.toList() == listOf("%|464252100463241|%") ->
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
            )

        val rooms = repo.listRooms().rooms

        assertEquals(listOf("박준우"), rooms.map { it.linkName })
    }

    @Test
    fun `listMembers uses observed profile display name for direct chat participant`() {
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
                            bindArgs?.toList() == listOf("%|464252100463241|%") ->
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("203887151", "366795577484293") ->
                            listOf(mapOf("display_name" to "재균"))
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" -> emptyList()
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("203887151", "366795577484293") ->
                            listOf(mapOf("display_name" to "재균"))
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
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
            MemberRepository(
                executeQuery = { sqlQuery, _, _ ->
                    when {
                        sqlQuery.contains("SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?") ->
                            listOf(mapOf("link_id" to "777", "type" to "OD", "members" to "[]", "active_members_count" to "1"))
                        sqlQuery.contains("FROM db2.open_chat_member WHERE link_id = ?") ->
                            listOf(
                                mapOf(
                                    "user_id" to "6729110572752592365",
                                    "nickname" to "박준우",
                                    "link_member_type" to "2",
                                    "profile_image_url" to null,
                                    "enc" to "0",
                                ),
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
    fun `snapshot learns observed profile mappings from open member names`() {
        var learnedChatId: Long? = null
        var learnedNames: Map<Long, String>? = null
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, _, _ ->
                    when {
                        sqlQuery == "SELECT members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" ->
                            listOf(
                                mapOf(
                                    "members" to "[6729110572752592365]",
                                    "blinded_member_ids" to "[]",
                                    "link_id" to "455150406",
                                ),
                            )
                        sqlQuery == "SELECT user_id, nickname, link_member_type, profile_image_url, enc FROM db2.open_chat_member WHERE link_id = ?" ->
                            listOf(
                                mapOf(
                                    "user_id" to "6729110572752592365",
                                    "nickname" to "박준우",
                                    "link_member_type" to "2",
                                    "profile_image_url" to null,
                                    "enc" to "0",
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

        repo.snapshot(chatId = 18478615493603057L)

        assertEquals(18478615493603057L, learnedChatId)
        assertEquals(mapOf(6729110572752592365L to "박준우"), learnedNames)
    }

    @Test
    fun `snapshot uses learned observed profile fallback for non open members`() {
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery == "SELECT members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?" ->
                            listOf(
                                mapOf(
                                    "members" to "[203887151,243338321]",
                                    "blinded_member_ids" to "[]",
                                    "link_id" to null,
                                ),
                            )
                        sqlQuery == "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1" -> emptyList()
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("203887151", "366795577484293") ->
                            listOf(mapOf("display_name" to "재균"))
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("243338321", "366795577484293") ->
                            emptyList()
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("203887151") ->
                            listOf(mapOf("display_name" to "재균"))
                        sqlQuery.contains("FROM db3.observed_profile_user_links") &&
                            bindArgs?.toList() == listOf("243338321") ->
                            emptyList()
                        else -> emptyList()
                    }
                },
                decrypt = { _, raw, _ -> raw },
                botId = 438562408L,
            )

        val snapshot = repo.snapshot(chatId = 366795577484293L)

        assertEquals("재균", snapshot.nicknames[203887151L])
        assertEquals(null, snapshot.nicknames[243338321L])
    }
}
