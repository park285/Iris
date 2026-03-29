# Member Management Implementation Plan (Phase 1-5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Iris에 오픈채팅방 멤버 조회, 이벤트 감지, 활동 분석 API를 추가한다.

**Architecture:** IrisServer(Ktor)에 REST 엔드포인트를 추가하고, MemberRepository가 KakaoTalk SQLite DB에서 멤버 데이터를 복호화하여 반환한다. RoomSnapshotManager가 기존 폴링 사이클에 편승하여 멤버 변동을 감지하고, SseEventBus를 통해 SSE 스트림으로 실시간 전달한다. Bridge 모듈에 ChatRoomIntrospector를 추가하여 런타임 ChatRoom 객체를 reflection으로 스캔한다.

**Tech Stack:** Kotlin, Ktor (Netty), kotlinx.serialization, KakaoDecrypt (AES-256-CBC), Xposed (bridge), kotlin.test

**Phase 3 Status:** Deferred — requires bridge IPC channel. `ChatRoomIntrospector` is implemented, but runtime data cannot be merged into API responses until the bridge exposes introspection results over the existing UDS communication channel.

**Spec:** `docs/superpowers/specs/2026-03-27-member-management-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|---|---|
| `app/src/main/java/party/qwer/iris/model/MemberModels.kt` | 멤버/방 API 응답 타입 |
| `app/src/main/java/party/qwer/iris/model/EventModels.kt` | 이벤트 페이로드 타입 |
| `app/src/main/java/party/qwer/iris/MemberRepository.kt` | DB 쿼리 + 복호화 (멤버/방/통계) |
| `app/src/main/java/party/qwer/iris/RoomSnapshotManager.kt` | 스냅샷 diff 엔진 |
| `app/src/main/java/party/qwer/iris/SseEventBus.kt` | SSE 이벤트 버스 (링버퍼 + fan-out) |
| `bridge/src/main/java/party/qwer/iris/bridge/ChatRoomIntrospector.kt` | ChatRoom reflection 스캐너 |
| `app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt` | MemberRepository 단위 테스트 |
| `app/src/test/java/party/qwer/iris/RoomSnapshotManagerTest.kt` | 스냅샷 diff 단위 테스트 |
| `app/src/test/java/party/qwer/iris/SseEventBusTest.kt` | SSE 버스 단위 테스트 |
| `bridge/src/test/java/party/qwer/iris/bridge/ChatRoomIntrospectorTest.kt` | Introspector 단위 테스트 |

### Modified Files

| File | Change |
|---|---|
| `app/src/main/java/party/qwer/iris/IrisServer.kt` | 새 라우트 함수 6개 추가 + MemberRepository/SseEventBus 생성자 주입 |
| `app/src/main/java/party/qwer/iris/bridge/RoutingModels.kt` | `RoutingCommand`에 `senderRole` 필드 추가 |
| `app/src/main/java/party/qwer/iris/ObserverHelper.kt` | `routeCommand()`에서 senderRole 조회 + `checkChange()`에서 스냅샷 diff 호출 |
| `app/src/main/java/party/qwer/iris/ChatLogRepository.kt` | 인터페이스 변경 없음 — `executeQuery()` 재사용 |

---

## Task 1: Response Model Types

**Files:**
- Create: `app/src/main/java/party/qwer/iris/model/MemberModels.kt`

- [ ] **Step 1: Create model file with all response types**

```kotlin
package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class RoomListResponse(
    val rooms: List<RoomSummary>,
)

@Serializable
data class RoomSummary(
    val chatId: Long,
    val type: String?,
    val linkId: Long?,
    val activeMembersCount: Int?,
    val linkName: String? = null,
    val linkUrl: String? = null,
    val memberLimit: Int? = null,
    val searchable: Int? = null,
    val botRole: Int? = null,
)

@Serializable
data class MemberListResponse(
    val chatId: Long,
    val linkId: Long?,
    val members: List<MemberInfo>,
    val totalCount: Int,
)

@Serializable
data class MemberInfo(
    val userId: Long,
    val nickname: String?,
    val role: String,
    val roleCode: Int,
    val profileImageUrl: String? = null,
)

@Serializable
data class RoomInfoResponse(
    val chatId: Long,
    val type: String?,
    val linkId: Long?,
    val notices: List<NoticeInfo>,
    val blindedMemberIds: List<Long>,
    val botCommands: List<BotCommandInfo>,
    val openLink: OpenLinkInfo? = null,
)

@Serializable
data class NoticeInfo(
    val content: String,
    val authorId: Long,
    val updatedAt: Long,
)

@Serializable
data class BotCommandInfo(
    val name: String,
    val botId: Long,
)

@Serializable
data class OpenLinkInfo(
    val name: String?,
    val url: String?,
    val memberLimit: Int? = null,
    val description: String? = null,
    val searchable: Int? = null,
)

@Serializable
data class StatsResponse(
    val chatId: Long,
    val period: PeriodRange,
    val totalMessages: Int,
    val activeMembers: Int,
    val topMembers: List<MemberStats>,
)

@Serializable
data class PeriodRange(
    val from: Long,
    val to: Long,
)

@Serializable
data class MemberStats(
    val userId: Long,
    val nickname: String?,
    val messageCount: Int,
    val lastActiveAt: Long?,
    val messageTypes: Map<String, Int>,
)

@Serializable
data class MemberActivityResponse(
    val userId: Long,
    val nickname: String?,
    val messageCount: Int,
    val firstMessageAt: Long?,
    val lastMessageAt: Long?,
    val activeHours: List<Int>,
    val messageTypes: Map<String, Int>,
)

fun roleCodeToName(code: Int): String = when (code) {
    1 -> "owner"
    4 -> "admin"
    8 -> "bot"
    else -> "member"
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/model/MemberModels.kt
git commit -m "feat(model): add member management response types"
```

---

## Task 2: Event Model Types

**Files:**
- Create: `app/src/main/java/party/qwer/iris/model/EventModels.kt`

- [ ] **Step 1: Create event model file**

```kotlin
package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class MemberEvent(
    val type: String = "member_event",
    val event: String,
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val nickname: String?,
    val estimated: Boolean = false,
    val timestamp: Long,
)

@Serializable
data class NicknameChangeEvent(
    val type: String = "nickname_change",
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val oldNickname: String?,
    val newNickname: String?,
    val timestamp: Long,
)

@Serializable
data class RoleChangeEvent(
    val type: String = "role_change",
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val oldRole: String,
    val newRole: String,
    val timestamp: Long,
)

@Serializable
data class ProfileChangeEvent(
    val type: String = "profile_change",
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val timestamp: Long,
)
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/model/EventModels.kt
git commit -m "feat(model): add event payload types for member detection"
```

---

## Task 3: MemberRepository

**Files:**
- Create: `app/src/main/java/party/qwer/iris/MemberRepository.kt`
- Create: `app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class MemberRepositoryTest {

    @Test
    fun `roleCodeToName maps known codes correctly`() {
        assertEquals("owner", party.qwer.iris.model.roleCodeToName(1))
        assertEquals("member", party.qwer.iris.model.roleCodeToName(2))
        assertEquals("admin", party.qwer.iris.model.roleCodeToName(4))
        assertEquals("bot", party.qwer.iris.model.roleCodeToName(8))
        assertEquals("member", party.qwer.iris.model.roleCodeToName(99))
    }

    @Test
    fun `parseJsonLongArray parses member id arrays`() {
        val repo = MemberRepository(
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
        val repo = MemberRepository(
            executeQuery = { _, _, _ -> emptyList() },
            decrypt = { _, _, _ -> "" },
            botId = 1L,
        )
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("7d"))
        assertEquals(30 * 86400L, repo.parsePeriodSeconds("30d"))
        assertEquals(null, repo.parsePeriodSeconds("all"))
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("invalid"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.MemberRepositoryTest" 2>&1 | tail -10`
Expected: FAIL — `MemberRepository` not found

- [ ] **Step 3: Implement MemberRepository**

```kotlin
package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import party.qwer.iris.model.BotCommandInfo
import party.qwer.iris.model.MemberInfo
import party.qwer.iris.model.MemberListResponse
import party.qwer.iris.model.MemberStats
import party.qwer.iris.model.NoticeInfo
import party.qwer.iris.model.OpenLinkInfo
import party.qwer.iris.model.RoomInfoResponse
import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.model.RoomSummary
import party.qwer.iris.model.StatsResponse
import party.qwer.iris.model.MemberActivityResponse
import party.qwer.iris.model.PeriodRange
import party.qwer.iris.model.roleCodeToName

class MemberRepository(
    private val executeQuery: (String, Array<String?>?, Int) -> List<Map<String, String?>>,
    private val decrypt: (Int, String, Long) -> String,
    private val botId: Long,
) {
    companion object {
        private const val MAX_ROWS = 500
        private const val STATS_ROW_LIMIT = 50_000
        private val json = Json { ignoreUnknownKeys = true }

        // KakaoTalk message type → human name
        private val MESSAGE_TYPE_NAMES = mapOf(
            "0" to "text", "1" to "photo", "2" to "video", "3" to "voice",
            "12" to "file", "20" to "emoticon", "26" to "reply", "27" to "multi_photo",
        )
    }

    fun listRooms(): RoomListResponse {
        val rows = executeQuery(
            """
            SELECT cr.id, cr.type, cr.active_members_count, cr.link_id,
                   ol.name AS link_name, ol.url AS link_url, ol.member_limit, ol.searchable,
                   op.link_member_type AS bot_role
            FROM chat_rooms cr
            LEFT JOIN db2.open_link ol ON cr.link_id = ol.id
            LEFT JOIN db2.open_profile op ON cr.link_id = op.link_id
            WHERE cr.type LIKE 'O%' AND cr.link_id > 0
            """.trimIndent(),
            null, MAX_ROWS,
        )
        return RoomListResponse(
            rooms = rows.map { row ->
                RoomSummary(
                    chatId = row["id"]?.toLongOrNull() ?: 0L,
                    type = row["type"],
                    linkId = row["link_id"]?.toLongOrNull(),
                    activeMembersCount = row["active_members_count"]?.toIntOrNull(),
                    linkName = row["link_name"],
                    linkUrl = row["link_url"],
                    memberLimit = row["member_limit"]?.toIntOrNull(),
                    searchable = row["searchable"]?.toIntOrNull(),
                    botRole = row["bot_role"]?.toIntOrNull(),
                )
            },
        )
    }

    fun listMembers(chatId: Long): MemberListResponse {
        val linkIdRow = executeQuery(
            "SELECT link_id FROM chat_rooms WHERE id = ?",
            arrayOf(chatId.toString()), 1,
        ).firstOrNull()
        val linkId = linkIdRow?.get("link_id")?.toLongOrNull()
            ?: return MemberListResponse(chatId, null, emptyList(), 0)

        val rows = executeQuery(
            """
            SELECT user_id, nickname, link_member_type, profile_image_url, enc
            FROM db2.open_chat_member WHERE link_id = ?
            """.trimIndent(),
            arrayOf(linkId.toString()), MAX_ROWS,
        )
        val members = rows.map { row ->
            val enc = row["enc"]?.toIntOrNull() ?: 0
            val rawNick = row["nickname"]
            val roleCode = row["link_member_type"]?.toIntOrNull() ?: 2
            MemberInfo(
                userId = row["user_id"]?.toLongOrNull() ?: 0L,
                nickname = if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick,
                role = roleCodeToName(roleCode),
                roleCode = roleCode,
                profileImageUrl = row["profile_image_url"],
            )
        }
        return MemberListResponse(chatId, linkId, members, members.size)
    }

    fun roomInfo(chatId: Long): RoomInfoResponse {
        val roomRow = executeQuery(
            "SELECT id, type, link_id, meta, blinded_member_ids FROM chat_rooms WHERE id = ?",
            arrayOf(chatId.toString()), 1,
        ).firstOrNull() ?: return RoomInfoResponse(chatId, null, null, emptyList(), emptyList(), emptyList())

        val linkId = roomRow["link_id"]?.toLongOrNull()
        val notices = parseNotices(roomRow["meta"])
        val blindedIds = parseJsonLongArray(roomRow["blinded_member_ids"]).toList()

        val botCommands = if (linkId != null) {
            executeQuery(
                "SELECT name, bot_id FROM db2.openchat_bot_command WHERE link_id = ?",
                arrayOf(linkId.toString()), MAX_ROWS,
            ).map { BotCommandInfo(it["name"] ?: "", it["bot_id"]?.toLongOrNull() ?: 0L) }
        } else {
            emptyList()
        }

        val openLink = if (linkId != null) {
            executeQuery(
                "SELECT name, url, member_limit, description, searchable FROM db2.open_link WHERE id = ?",
                arrayOf(linkId.toString()), 1,
            ).firstOrNull()?.let { row ->
                OpenLinkInfo(
                    name = row["name"], url = row["url"],
                    memberLimit = row["member_limit"]?.toIntOrNull(),
                    description = row["description"],
                    searchable = row["searchable"]?.toIntOrNull(),
                )
            }
        } else {
            null
        }

        return RoomInfoResponse(
            chatId = chatId,
            type = roomRow["type"],
            linkId = linkId,
            notices = notices,
            blindedMemberIds = blindedIds,
            botCommands = botCommands,
            openLink = openLink,
        )
    }

    fun roomStats(chatId: Long, period: String?, limit: Int): StatsResponse {
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L

        val rows = executeQuery(
            """
            SELECT user_id, type, COUNT(*) as cnt, MAX(created_at) as last_active
            FROM chat_logs WHERE chat_id = ? AND created_at >= ?
            GROUP BY user_id, type ORDER BY cnt DESC LIMIT ?
            """.trimIndent(),
            arrayOf(chatId.toString(), from.toString(), STATS_ROW_LIMIT.toString()),
            STATS_ROW_LIMIT,
        )

        val byUser = rows.groupBy { it["user_id"]?.toLongOrNull() ?: 0L }
        val memberStats = byUser.map { (userId, typeRows) ->
            val types = mutableMapOf<String, Int>()
            var total = 0
            var lastActive: Long? = null
            for (row in typeRows) {
                val typeName = MESSAGE_TYPE_NAMES[row["type"]] ?: "other"
                val cnt = row["cnt"]?.toIntOrNull() ?: 0
                types[typeName] = (types[typeName] ?: 0) + cnt
                total += cnt
                val la = row["last_active"]?.toLongOrNull()
                if (la != null && (lastActive == null || la > lastActive)) lastActive = la
            }
            MemberStats(userId, resolveNickname(userId), total, lastActive, types)
        }.sortedByDescending { it.messageCount }.take(limit)

        return StatsResponse(
            chatId = chatId,
            period = PeriodRange(from, now),
            totalMessages = memberStats.sumOf { it.messageCount },
            activeMembers = memberStats.size,
            topMembers = memberStats,
        )
    }

    fun memberActivity(chatId: Long, userId: Long, period: String?): MemberActivityResponse {
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L

        val rows = executeQuery(
            """
            SELECT type, created_at FROM chat_logs
            WHERE chat_id = ? AND user_id = ? AND created_at >= ?
            ORDER BY created_at ASC LIMIT ?
            """.trimIndent(),
            arrayOf(chatId.toString(), userId.toString(), from.toString(), STATS_ROW_LIMIT.toString()),
            STATS_ROW_LIMIT,
        )

        val types = mutableMapOf<String, Int>()
        val hours = IntArray(24)
        var firstAt: Long? = null
        var lastAt: Long? = null

        for (row in rows) {
            val ts = row["created_at"]?.toLongOrNull() ?: continue
            if (firstAt == null) firstAt = ts
            lastAt = ts
            val hour = ((ts % 86400) / 3600).toInt()
            hours[hour]++
            val typeName = MESSAGE_TYPE_NAMES[row["type"]] ?: "other"
            types[typeName] = (types[typeName] ?: 0) + 1
        }

        return MemberActivityResponse(
            userId = userId,
            nickname = resolveNickname(userId),
            messageCount = rows.size,
            firstMessageAt = firstAt,
            lastMessageAt = lastAt,
            activeHours = hours.toList(),
            messageTypes = types,
        )
    }

    /** Resolve a single member's decrypted nickname from open_chat_member. */
    private fun resolveNickname(userId: Long): String? {
        val row = executeQuery(
            "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? LIMIT 1",
            arrayOf(userId.toString()), 1,
        ).firstOrNull() ?: return null
        val enc = row["enc"]?.toIntOrNull() ?: 0
        val rawNick = row["nickname"] ?: return null
        return if (enc > 0) decrypt(enc, rawNick, botId) else rawNick
    }

    /** Fetch a snapshot of member data for a given chatId (used by RoomSnapshotManager). */
    fun snapshot(chatId: Long): RoomSnapshotData {
        val roomRow = executeQuery(
            "SELECT members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?",
            arrayOf(chatId.toString()), 1,
        ).firstOrNull()

        val linkId = roomRow?.get("link_id")?.toLongOrNull()
        val memberIds = parseJsonLongArray(roomRow?.get("members"))
        val blindedIds = parseJsonLongArray(roomRow?.get("blinded_member_ids"))

        val memberDetails = if (linkId != null) {
            executeQuery(
                "SELECT user_id, nickname, link_member_type, profile_image_url, enc FROM db2.open_chat_member WHERE link_id = ?",
                arrayOf(linkId.toString()), MAX_ROWS,
            )
        } else {
            emptyList()
        }

        val nicknames = mutableMapOf<Long, String>()
        val roles = mutableMapOf<Long, Int>()
        val profileImages = mutableMapOf<Long, String>()

        for (row in memberDetails) {
            val uid = row["user_id"]?.toLongOrNull() ?: continue
            val enc = row["enc"]?.toIntOrNull() ?: 0
            val rawNick = row["nickname"]
            nicknames[uid] = if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick ?: ""
            roles[uid] = row["link_member_type"]?.toIntOrNull() ?: 2
            row["profile_image_url"]?.let { profileImages[uid] = it }
        }

        return RoomSnapshotData(
            chatId = chatId,
            linkId = linkId,
            memberIds = memberIds,
            blindedIds = blindedIds,
            nicknames = nicknames,
            roles = roles,
            profileImages = profileImages,
        )
    }

    fun parseJsonLongArray(raw: String?): Set<Long> {
        if (raw.isNullOrBlank() || raw == "[]") return emptySet()
        return try {
            json.parseToJsonElement(raw).jsonArray.mapNotNull {
                it.jsonPrimitive.long
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parsePeriodSeconds(period: String?): Long? = when {
        period == "all" -> null
        period != null && period.endsWith("d") ->
            period.dropLast(1).toLongOrNull()?.times(86400) ?: (7 * 86400L)
        else -> 7 * 86400L
    }

    fun resolveSenderRole(userId: Long, linkId: Long?): Int? {
        if (linkId == null) return null
        val row = executeQuery(
            "SELECT link_member_type FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1",
            arrayOf(userId.toString(), linkId.toString()), 1,
        ).firstOrNull() ?: return null
        return row["link_member_type"]?.toIntOrNull()
    }
}

data class RoomSnapshotData(
    val chatId: Long,
    val linkId: Long?,
    val memberIds: Set<Long>,
    val blindedIds: Set<Long>,
    val nicknames: Map<Long, String>,
    val roles: Map<Long, Int>,
    val profileImages: Map<Long, String>,
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.MemberRepositoryTest" 2>&1 | tail -10`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/party/qwer/iris/MemberRepository.kt app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt
git commit -m "feat: add MemberRepository with DB queries and decryption"
```

---

## Task 4: RoomSnapshotManager

**Files:**
- Create: `app/src/main/java/party/qwer/iris/RoomSnapshotManager.kt`
- Create: `app/src/test/java/party/qwer/iris/RoomSnapshotManagerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package party.qwer.iris

import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.model.RoleChangeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSnapshotManagerTest {

    private fun snap(
        memberIds: Set<Long> = emptySet(),
        blindedIds: Set<Long> = emptySet(),
        nicknames: Map<Long, String> = emptyMap(),
        roles: Map<Long, Int> = emptyMap(),
        profileImages: Map<Long, String> = emptyMap(),
    ) = RoomSnapshotData(
        chatId = 100L, linkId = 1L,
        memberIds = memberIds, blindedIds = blindedIds,
        nicknames = nicknames, roles = roles, profileImages = profileImages,
    )

    @Test
    fun `detects join event`() {
        val manager = RoomSnapshotManager()
        val prev = snap(memberIds = setOf(1L, 2L))
        val curr = snap(memberIds = setOf(1L, 2L, 3L), nicknames = mapOf(3L to "새멤버"))
        val events = manager.diff(prev, curr)
        assertEquals(1, events.size)
        val event = events[0] as MemberEvent
        assertEquals("join", event.event)
        assertEquals(3L, event.userId)
    }

    @Test
    fun `detects leave event when not blinded`() {
        val manager = RoomSnapshotManager()
        val prev = snap(memberIds = setOf(1L, 2L, 3L), nicknames = mapOf(3L to "퇴장자"))
        val curr = snap(memberIds = setOf(1L, 2L))
        val events = manager.diff(prev, curr)
        assertEquals(1, events.size)
        val event = events[0] as MemberEvent
        assertEquals("leave", event.event)
        assertTrue(event.estimated)
    }

    @Test
    fun `detects kick event when blinded`() {
        val manager = RoomSnapshotManager()
        val prev = snap(memberIds = setOf(1L, 2L, 3L), nicknames = mapOf(3L to "강퇴자"))
        val curr = snap(memberIds = setOf(1L, 2L), blindedIds = setOf(3L))
        val events = manager.diff(prev, curr)
        assertEquals(1, events.size)
        val event = events[0] as MemberEvent
        assertEquals("kick", event.event)
        assertEquals(false, event.estimated)
    }

    @Test
    fun `detects nickname change`() {
        val manager = RoomSnapshotManager()
        val prev = snap(memberIds = setOf(1L), nicknames = mapOf(1L to "이전닉"))
        val curr = snap(memberIds = setOf(1L), nicknames = mapOf(1L to "변경닉"))
        val events = manager.diff(prev, curr)
        assertEquals(1, events.size)
        val event = events[0] as NicknameChangeEvent
        assertEquals("이전닉", event.oldNickname)
        assertEquals("변경닉", event.newNickname)
    }

    @Test
    fun `detects role change`() {
        val manager = RoomSnapshotManager()
        val prev = snap(memberIds = setOf(1L), roles = mapOf(1L to 2))
        val curr = snap(memberIds = setOf(1L), roles = mapOf(1L to 4))
        val events = manager.diff(prev, curr)
        assertEquals(1, events.size)
        val event = events[0] as RoleChangeEvent
        assertEquals("member", event.oldRole)
        assertEquals("admin", event.newRole)
    }

    @Test
    fun `no events when nothing changed`() {
        val manager = RoomSnapshotManager()
        val snap = snap(memberIds = setOf(1L), nicknames = mapOf(1L to "닉"), roles = mapOf(1L to 2))
        assertEquals(0, manager.diff(snap, snap).size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.RoomSnapshotManagerTest" 2>&1 | tail -10`
Expected: FAIL — `RoomSnapshotManager` not found

- [ ] **Step 3: Implement RoomSnapshotManager**

```kotlin
package party.qwer.iris

import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.model.ProfileChangeEvent
import party.qwer.iris.model.RoleChangeEvent
import party.qwer.iris.model.roleCodeToName

class RoomSnapshotManager {

    fun diff(prev: RoomSnapshotData, curr: RoomSnapshotData): List<Any> {
        val events = mutableListOf<Any>()
        val now = System.currentTimeMillis() / 1000

        // Join events
        val joined = curr.memberIds - prev.memberIds
        for (uid in joined) {
            events.add(
                MemberEvent(
                    event = "join", chatId = curr.chatId, linkId = curr.linkId,
                    userId = uid, nickname = curr.nicknames[uid],
                    estimated = false, timestamp = now,
                ),
            )
        }

        // Leave / kick events
        val left = prev.memberIds - curr.memberIds
        for (uid in left) {
            val isKicked = uid in curr.blindedIds && uid !in prev.blindedIds
            events.add(
                MemberEvent(
                    event = if (isKicked) "kick" else "leave",
                    chatId = curr.chatId, linkId = curr.linkId,
                    userId = uid, nickname = prev.nicknames[uid],
                    estimated = !isKicked, timestamp = now,
                ),
            )
        }

        // Nickname changes (only for members still present)
        val commonMembers = prev.memberIds.intersect(curr.memberIds)
        for (uid in commonMembers) {
            val oldNick = prev.nicknames[uid]
            val newNick = curr.nicknames[uid]
            if (oldNick != null && newNick != null && oldNick != newNick) {
                events.add(
                    NicknameChangeEvent(
                        chatId = curr.chatId, linkId = curr.linkId,
                        userId = uid, oldNickname = oldNick, newNickname = newNick,
                        timestamp = now,
                    ),
                )
            }

            // Role changes
            val oldRole = prev.roles[uid]
            val newRole = curr.roles[uid]
            if (oldRole != null && newRole != null && oldRole != newRole) {
                events.add(
                    RoleChangeEvent(
                        chatId = curr.chatId, linkId = curr.linkId,
                        userId = uid, oldRole = roleCodeToName(oldRole), newRole = roleCodeToName(newRole),
                        timestamp = now,
                    ),
                )
            }

            // Profile image changes
            val oldImg = prev.profileImages[uid]
            val newImg = curr.profileImages[uid]
            if (oldImg != null && newImg != null && oldImg != newImg) {
                events.add(
                    ProfileChangeEvent(
                        chatId = curr.chatId, linkId = curr.linkId,
                        userId = uid, timestamp = now,
                    ),
                )
            }
        }

        return events
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.RoomSnapshotManagerTest" 2>&1 | tail -10`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/party/qwer/iris/RoomSnapshotManager.kt app/src/test/java/party/qwer/iris/RoomSnapshotManagerTest.kt
git commit -m "feat: add RoomSnapshotManager with diff-based event detection"
```

---

## Task 5: SseEventBus

**Files:**
- Create: `app/src/main/java/party/qwer/iris/SseEventBus.kt`
- Create: `app/src/test/java/party/qwer/iris/SseEventBusTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseEventBusTest {

    @Test
    fun `stores events in ring buffer`() {
        val bus = SseEventBus(bufferSize = 3)
        bus.emit("event-1")
        bus.emit("event-2")
        bus.emit("event-3")
        bus.emit("event-4")
        assertEquals(3, bus.replayFrom(0).size)
        assertEquals("event-2", bus.replayFrom(0)[0].second)
    }

    @Test
    fun `replayFrom returns events after given id`() {
        val bus = SseEventBus(bufferSize = 10)
        bus.emit("a")
        bus.emit("b")
        bus.emit("c")
        val replay = bus.replayFrom(1)
        assertEquals(2, replay.size)
        assertEquals("b", replay[0].second)
        assertEquals("c", replay[1].second)
    }

    @Test
    fun `replayFrom returns empty when no events after id`() {
        val bus = SseEventBus(bufferSize = 10)
        bus.emit("a")
        val replay = bus.replayFrom(1)
        assertTrue(replay.isEmpty())
    }

    @Test
    fun `monotonic ids increment`() {
        val bus = SseEventBus(bufferSize = 10)
        bus.emit("a")
        bus.emit("b")
        val all = bus.replayFrom(0)
        assertEquals(1L, all[0].first)
        assertEquals(2L, all[1].first)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.SseEventBusTest" 2>&1 | tail -10`
Expected: FAIL — `SseEventBus` not found

- [ ] **Step 3: Implement SseEventBus**

```kotlin
package party.qwer.iris

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SseEventBus(private val bufferSize: Int = 100) {
    private val idCounter = AtomicLong(0)
    private val buffer = ArrayDeque<Pair<Long, String>>(bufferSize)
    private val lock = Any()

    /** Listeners that receive (id, data) pairs in real-time. */
    val listeners = CopyOnWriteArrayList<(Long, String) -> Unit>()

    fun emit(data: String) {
        val id = idCounter.incrementAndGet()
        synchronized(lock) {
            if (buffer.size >= bufferSize) buffer.removeFirst()
            buffer.addLast(id to data)
        }
        for (listener in listeners) {
            try {
                listener(id, data)
            } catch (_: Exception) {
                // SSE listener failure must not affect operational path
            }
        }
    }

    /** Return events with id > afterId from the ring buffer. */
    fun replayFrom(afterId: Long): List<Pair<Long, String>> {
        synchronized(lock) {
            return buffer.filter { it.first > afterId }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.SseEventBusTest" 2>&1 | tail -10`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/party/qwer/iris/SseEventBus.kt app/src/test/java/party/qwer/iris/SseEventBusTest.kt
git commit -m "feat: add SseEventBus with ring buffer and replay support"
```

---

## Task 6: IrisServer — Member API Routes

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`

This task adds the route functions for `/rooms`, `/rooms/{chatId}/members`, `/rooms/{chatId}/info`, `/rooms/{chatId}/stats`, `/rooms/{chatId}/members/{userId}/activity`, and `/events/stream`.

- [ ] **Step 1: Add MemberRepository and SseEventBus to IrisServer constructor**

In `IrisServer.kt`, add constructor parameters:
```kotlin
// Add to the class constructor alongside existing parameters:
val memberRepo: MemberRepository,
val sseEventBus: SseEventBus,
```

- [ ] **Step 2: Add route registration in `configureRouting()`**

Add after existing route registrations inside `routing { }`:
```kotlin
configureMemberRoutes()
```

- [ ] **Step 3: Implement route functions**

Add as `private fun Route.configureMemberRoutes()`:

```kotlin
private fun Route.configureMemberRoutes() {
    get("/rooms") {
        if (!requireBotToken(call)) return@get
        call.respond(memberRepo.listRooms())
    }

    get("/rooms/{chatId}/members") {
        if (!requireBotToken(call)) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull()
            ?: invalidRequest("chatId must be a number")
        call.respond(memberRepo.listMembers(chatId))
    }

    get("/rooms/{chatId}/info") {
        if (!requireBotToken(call)) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull()
            ?: invalidRequest("chatId must be a number")
        call.respond(memberRepo.roomInfo(chatId))
    }

    get("/rooms/{chatId}/stats") {
        if (!requireBotToken(call)) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull()
            ?: invalidRequest("chatId must be a number")
        val period = call.request.queryParameters["period"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        call.respond(memberRepo.roomStats(chatId, period, limit))
    }

    get("/rooms/{chatId}/members/{userId}/activity") {
        if (!requireBotToken(call)) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull()
            ?: invalidRequest("chatId must be a number")
        val userId = call.parameters["userId"]?.toLongOrNull()
            ?: invalidRequest("userId must be a number")
        val period = call.request.queryParameters["period"]
        call.respond(memberRepo.memberActivity(chatId, userId, period))
    }

    get("/events/stream") {
        if (!requireBotToken(call)) return@get
        val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L
        call.respondBytesWriter(contentType = io.ktor.http.ContentType.Text.EventStream) {
            // Replay buffered events
            for ((id, data) in sseEventBus.replayFrom(lastEventId)) {
                writeStringUtf8("id: $id\ndata: $data\n\n")
                flush()
            }
            // Subscribe to live events
            val channel = kotlinx.coroutines.channels.Channel<Pair<Long, String>>(64)
            val listener: (Long, String) -> Unit = { id, data -> channel.trySend(id to data) }
            sseEventBus.listeners.add(listener)
            try {
                for ((id, data) in channel) {
                    writeStringUtf8("id: $id\ndata: $data\n\n")
                    flush()
                }
            } finally {
                sseEventBus.listeners.remove(listener)
                channel.close()
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (may require fixing constructor call sites — update where IrisServer is instantiated)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/party/qwer/iris/IrisServer.kt
git commit -m "feat(server): add member management and SSE event stream routes"
```

---

## Task 7: RoutingCommand senderRole Extension

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/bridge/RoutingModels.kt`
- Modify: `app/src/main/java/party/qwer/iris/ObserverHelper.kt`

- [ ] **Step 1: Add senderRole to RoutingCommand**

In `RoutingModels.kt`, add after `attachment` field (L15):
```kotlin
val senderRole: Int? = null,
```

- [ ] **Step 2: Add senderRole lookup in ObserverHelper.routeCommand()**

In `ObserverHelper.kt`, in the `routeCommand()` function (L179–L200), add senderRole resolution before the `RoutingCommand` constructor call:

```kotlin
val senderRole = memberRepo?.resolveSenderRole(
    logEntry.userId,
    roomMetadata.linkId.toLongOrNull(),
)
```

Then add `senderRole = senderRole` to the `RoutingCommand(...)` constructor call.

Note: `ObserverHelper` needs a `memberRepo: MemberRepository?` constructor parameter (nullable, for backwards compatibility).

- [ ] **Step 3: Verify compilation and existing tests pass**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: All existing tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/party/qwer/iris/bridge/RoutingModels.kt app/src/main/java/party/qwer/iris/ObserverHelper.kt
git commit -m "feat(routing): add senderRole to RoutingCommand webhook payload"
```

---

## Task 8: ObserverHelper Snapshot Diff Integration

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ObserverHelper.kt`

- [ ] **Step 1: Add snapshot manager fields to ObserverHelper**

Add constructor parameters:
```kotlin
private val snapshotManager: RoomSnapshotManager? = null,
private val memberRepo: MemberRepository? = null, // already added in Task 7
private val sseEventBus: SseEventBus? = null,
```

Add instance fields:
```kotlin
private val previousSnapshots = mutableMapOf<Long, RoomSnapshotData>()
```

- [ ] **Step 2: Add snapshot diff call at end of checkChange()**

After the `for (logEntry in newLogs)` loop in `checkChange()`, add:

```kotlin
if (snapshotManager != null && memberRepo != null && sseEventBus != null) {
    runSnapshotDiff()
}
```

- [ ] **Step 3: Implement runSnapshotDiff()**

```kotlin
private fun runSnapshotDiff() {
    val repo = memberRepo ?: return
    val snapMgr = snapshotManager ?: return
    val bus = sseEventBus ?: return

    // Get all open chat room IDs
    val roomRows = repo.executeQuery(
        "SELECT id FROM chat_rooms WHERE type LIKE 'O%' AND link_id > 0",
        null, 100,
    )

    for (roomRow in roomRows) {
        val chatId = roomRow["id"]?.toLongOrNull() ?: continue
        val currentSnapshot = repo.snapshot(chatId)
        val previousSnapshot = previousSnapshots[chatId]
        previousSnapshots[chatId] = currentSnapshot

        if (previousSnapshot == null) continue // first run — skip diff, just cache

        val events = snapMgr.diff(previousSnapshot, currentSnapshot)
        for (event in events) {
            val jsonStr = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer(event::class.java), event
            )
            bus.emit(jsonStr)
            // webhook fan-out is handled by the dispatcher if configured
        }
    }
}
```

Note: The serialization of polymorphic events requires using the concrete type serializer. Use `Json.encodeToString(MemberEvent.serializer(), event as MemberEvent)` etc., or use a sealed class hierarchy. Simpler approach: use `when (event)` to dispatch to the right serializer.

Replace the generic serialization with:
```kotlin
val jsonStr = when (event) {
    is party.qwer.iris.model.MemberEvent -> serverJson.encodeToString(party.qwer.iris.model.MemberEvent.serializer(), event)
    is party.qwer.iris.model.NicknameChangeEvent -> serverJson.encodeToString(party.qwer.iris.model.NicknameChangeEvent.serializer(), event)
    is party.qwer.iris.model.RoleChangeEvent -> serverJson.encodeToString(party.qwer.iris.model.RoleChangeEvent.serializer(), event)
    is party.qwer.iris.model.ProfileChangeEvent -> serverJson.encodeToString(party.qwer.iris.model.ProfileChangeEvent.serializer(), event)
    else -> continue
}
```

- [ ] **Step 4: Verify compilation and all tests pass**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/party/qwer/iris/ObserverHelper.kt
git commit -m "feat(observer): piggyback snapshot diff on polling cycle for event detection"
```

---

## Task 9: ChatRoomIntrospector (Bridge Module)

**Files:**
- Create: `bridge/src/main/java/party/qwer/iris/bridge/ChatRoomIntrospector.kt`
- Create: `bridge/src/test/java/party/qwer/iris/bridge/ChatRoomIntrospectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package party.qwer.iris.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoomIntrospectorTest {

    data class FakeRoom(
        val id: Long = 42L,
        val name: String = "TestRoom",
        val members: List<String> = listOf("a", "b"),
        val count: Int = 5,
    )

    @Test
    fun `scans all fields of a simple object`() {
        val result = ChatRoomIntrospector.scan(FakeRoom())
        assertEquals("FakeRoom", result.className.substringAfterLast('.'))
        assertTrue(result.fields.any { it.name == "id" && it.type == "long" })
        assertTrue(result.fields.any { it.name == "name" && it.type.contains("String") })
        assertTrue(result.fields.any { it.name == "members" && it.size == 2 })
        assertTrue(result.fields.any { it.name == "count" && it.type == "int" })
    }

    @Test
    fun `respects max depth`() {
        data class Nested(val inner: FakeRoom = FakeRoom())
        val result = ChatRoomIntrospector.scan(Nested(), maxDepth = 0)
        val innerField = result.fields.first { it.name == "inner" }
        assertTrue(innerField.nested.isEmpty())
    }

    @Test
    fun `scans nested fields at depth 1`() {
        data class Nested(val inner: FakeRoom = FakeRoom())
        val result = ChatRoomIntrospector.scan(Nested(), maxDepth = 1)
        val innerField = result.fields.first { it.name == "inner" }
        assertTrue(innerField.nested.isNotEmpty())
        assertTrue(innerField.nested.any { it.name == "id" })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :bridge:testDebugUnitTest --tests "party.qwer.iris.bridge.ChatRoomIntrospectorTest" 2>&1 | tail -10`
Expected: FAIL — `ChatRoomIntrospector` not found

- [ ] **Step 3: Implement ChatRoomIntrospector**

```kotlin
package party.qwer.iris.bridge

import java.lang.reflect.Modifier

object ChatRoomIntrospector {

    data class ScanResult(
        val className: String,
        val scannedAt: Long,
        val fields: List<FieldInfo>,
    )

    data class FieldInfo(
        val name: String,
        val type: String,
        val value: String? = null,
        val size: Int? = null,
        val elementType: String? = null,
        val nested: List<FieldInfo> = emptyList(),
    )

    fun scan(obj: Any, maxDepth: Int = 1): ScanResult {
        return ScanResult(
            className = obj.javaClass.name,
            scannedAt = System.currentTimeMillis() / 1000,
            fields = scanFields(obj, maxDepth, currentDepth = 0),
        )
    }

    private fun scanFields(obj: Any, maxDepth: Int, currentDepth: Int): List<FieldInfo> {
        return obj.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    val typeName = field.type.name
                    when {
                        field.type.isPrimitive || field.type == String::class.java -> {
                            FieldInfo(name = field.name, type = typeName, value = value?.toString())
                        }
                        value is Collection<*> -> {
                            val elemType = value.firstOrNull()?.javaClass?.name
                            FieldInfo(
                                name = field.name, type = typeName,
                                size = value.size, elementType = elemType,
                            )
                        }
                        value is Map<*, *> -> {
                            FieldInfo(name = field.name, type = typeName, size = value.size)
                        }
                        value != null && currentDepth < maxDepth -> {
                            FieldInfo(
                                name = field.name, type = typeName,
                                nested = scanFields(value, maxDepth, currentDepth + 1),
                            )
                        }
                        else -> {
                            FieldInfo(name = field.name, type = typeName, value = value?.toString())
                        }
                    }
                } catch (_: Exception) {
                    FieldInfo(name = field.name, type = field.type.name, value = "<inaccessible>")
                }
            }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :bridge:testDebugUnitTest --tests "party.qwer.iris.bridge.ChatRoomIntrospectorTest" 2>&1 | tail -10`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add bridge/src/main/java/party/qwer/iris/bridge/ChatRoomIntrospector.kt bridge/src/test/java/party/qwer/iris/bridge/ChatRoomIntrospectorTest.kt
git commit -m "feat(bridge): add ChatRoomIntrospector for runtime object reflection"
```

---

## Task 10: Diagnostics Endpoint for ChatRoom Fields

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`

This task adds `GET /diagnostics/chatroom-fields/{chatId}` to IrisServer. The endpoint calls the bridge via a provider function (same pattern as `bridgeHealthProvider`).

- [ ] **Step 1: Add introspection provider to IrisServer constructor**

```kotlin
val chatRoomIntrospectProvider: ((Long) -> String?)? = null,
```

This is a function that takes a chatId and returns a JSON string of the scan result. The bridge module implements it and passes it to IrisServer at startup.

- [ ] **Step 2: Add the diagnostic route**

Add inside `configureHealthRoutes()` or a new `configureDiagnosticRoutes()`:

```kotlin
get("/diagnostics/chatroom-fields/{chatId}") {
    if (!requireBotToken(call)) return@get
    val chatId = call.parameters["chatId"]?.toLongOrNull()
        ?: invalidRequest("chatId must be a number")
    val result = chatRoomIntrospectProvider?.invoke(chatId)
        ?: invalidRequest("bridge introspection unavailable")
    call.respondText(result, ContentType.Application.Json)
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /home/kapu/gemini/Iris && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/party/qwer/iris/IrisServer.kt
git commit -m "feat(server): add /diagnostics/chatroom-fields endpoint for bridge introspection"
```

---

## Task 11: Wire Everything at Startup

**Files:**
- Modify: Wherever `IrisServer` is instantiated (find with grep for `IrisServer(`)
- Modify: Wherever `ObserverHelper` is instantiated

- [ ] **Step 1: Find instantiation sites**

Run: `grep -rn "IrisServer(" app/src/main/java/ --include="*.kt"` and `grep -rn "ObserverHelper(" app/src/main/java/ --include="*.kt"` to locate where these classes are created.

- [ ] **Step 2: Create MemberRepository and SseEventBus instances**

At the startup site, before IrisServer construction:

```kotlin
val memberRepo = MemberRepository(
    executeQuery = chatLogRepo::executeQuery,
    decrypt = KakaoDecrypt.Companion::decrypt,
    botId = configManager.botId,
)
val sseEventBus = SseEventBus(bufferSize = 100)
val snapshotManager = RoomSnapshotManager()
```

- [ ] **Step 3: Pass to IrisServer and ObserverHelper constructors**

Add `memberRepo = memberRepo, sseEventBus = sseEventBus` to IrisServer constructor call.
Add `memberRepo = memberRepo, snapshotManager = snapshotManager, sseEventBus = sseEventBus` to ObserverHelper constructor call.

- [ ] **Step 4: Verify full build and tests**

Run: `cd /home/kapu/gemini/Iris && ./gradlew lint ktlintCheck assembleDebug test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: wire MemberRepository, SseEventBus, and RoomSnapshotManager at startup"
```

---

## Task 12: Integration Verification

- [ ] **Step 1: Run full verification suite**

```bash
cd /home/kapu/gemini/Iris && ./gradlew lint ktlintCheck assembleDebug assembleRelease test
```

Expected: BUILD SUCCESSFUL on all tasks.

- [ ] **Step 2: Fix any lint/ktlint issues**

Address any formatting or warning issues. `allWarningsAsErrors=true` means all warnings are build failures.

- [ ] **Step 3: Final commit if fixes were needed**

```bash
git add -A
git commit -m "fix: address lint and formatting issues in member management code"
```
