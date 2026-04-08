package party.qwer.iris.imagebridge.runtime

import party.qwer.iris.ImageBridgeProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatRoomMemberExtractorTest {
    @Test
    fun `extracts members from obfuscated nested collection`() {
        data class NickBox(
            val a: String,
        )

        data class Member(
            val a: Long,
            val b: NickBox,
            val c: Int,
            val d: String,
        )

        data class Room(
            val q: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor(clock = { 1234L })

        val result =
            extractor.snapshot(
                roomId = 55L,
                room =
                    Room(
                        q =
                            listOf(
                                Member(7L, NickBox("Alice Updated"), 4, "https://example.com/a.png"),
                                Member(9L, NickBox("Bob"), 2, "https://example.com/b.png"),
                            ),
                    ),
                expectedMemberIds = setOf(7L, 9L),
            )

        assertEquals(55L, result.roomId)
        assertEquals(1234L, result.scannedAtEpochMs)
        assertEquals(listOf(7L, 9L), result.members.map { it.userId })
        assertEquals("Alice Updated", result.members.first().nickname)
        assertEquals(4, result.members.first().roleCode)
        assertEquals("https://example.com/a.png", result.members.first().profileImageUrl)
    }

    @Test
    fun `prefers expected member id path over constant room id field`() {
        data class Member(
            val a: Long,
            val b: Long,
            val c: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 77L,
                room = Room(members = listOf(Member(7L, 77L, "Alice"), Member(9L, 77L, "Bob"))),
                expectedMemberIds = setOf(7L, 9L),
            )

        assertEquals(listOf(7L, 9L), result.members.map { it.userId })
    }

    @Test
    fun `filters extracted members to expected ids`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 77L,
                room = Room(members = listOf(Member(7L, "Alice"), Member(99L, "Unexpected"))),
                expectedMemberIds = setOf(7L),
            )

        assertEquals(listOf(7L), result.members.map { it.userId })
    }

    @Test
    fun `extracts direct user id to nickname maps`() {
        data class Room(
            val a: Map<Long, String>,
        )

        val extractor = ChatRoomMemberExtractor(clock = { 55L })

        val result =
            extractor.snapshot(
                roomId = 88L,
                room = Room(a = linkedMapOf(7L to "Alice", 9L to "Bob")),
                expectedMemberIds = setOf(7L, 9L),
            )

        assertEquals(listOf(7L, 9L), result.members.map { it.userId })
        assertEquals(listOf("Alice", "Bob"), result.members.map { it.nickname })
    }

    @Test
    fun `extracts members when user id path is numeric string`() {
        data class Member(
            val userId: String,
            val nickname: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 5L,
                room = Room(members = listOf(Member("7", "Alice"), Member("9", "Bob"))),
                expectedMemberIds = setOf(7L, 9L),
            )

        assertEquals(listOf(7L, 9L), result.members.map { it.userId })
        assertEquals(listOf("Alice", "Bob"), result.members.map { it.nickname })
    }

    @Test
    fun `extracts members when user id is nested beyond depth two`() {
        data class IdBox(
            val raw: Long,
        )

        data class Profile(
            val nickname: String,
        )

        data class IdContainer(
            val wrapper: IdBox,
            val profile: Profile,
        )

        data class Member(
            val payload: IdContainer,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 6L,
                room = Room(members = listOf(Member(IdContainer(IdBox(7L), Profile("Alice"))))),
                expectedMemberIds = setOf(7L),
            )

        assertEquals(7L, result.members.single().userId)
        assertEquals("Alice", result.members.single().nickname)
    }

    @Test
    fun `omits profile image when no url-like field exists`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result = extractor.snapshot(1L, Room(members = listOf(Member(7L, "Alice"))), expectedMemberIds = setOf(7L))

        assertNull(result.members.single().profileImageUrl)
    }

    @Test
    fun `preserves numeric nicknames`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result = extractor.snapshot(1L, Room(members = listOf(Member(7L, "1234"))), expectedMemberIds = setOf(7L))

        assertEquals("1234", result.members.single().nickname)
    }

    @Test
    fun `preserves long camelcase nicknames without artifact tokens`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 1L,
                room = Room(members = listOf(Member(7L, "OpenWorldProGamer99"))),
                expectedMemberHints =
                    listOf(
                        ImageBridgeProtocol.ChatRoomMemberHint(userId = 7L, nickname = "OpenWorldProGamer99"),
                    ),
            )

        assertEquals("OpenWorldProGamer99", result.members.single().nickname)
    }

    @Test
    fun `uses preferred plan when previous nickname hint is stale after rename`() {
        data class NickBox(
            val a: String,
        )

        data class Member(
            val a: Long,
            val b: NickBox,
            val displayName: String,
        )

        data class Room(
            val q: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()
        val preferredPlan =
            ImageBridgeProtocol.ChatRoomMemberExtractionPlan(
                containerPath = "$.q",
                sourceClassName = Member::class.java.name,
                userIdPath = "a",
                nicknamePath = "b.a",
                fingerprint = "$.q|${Member::class.java.name}|a|b.a",
            )

        val result =
            extractor.snapshot(
                roomId = 1L,
                room = Room(q = listOf(Member(7L, NickBox("Alice Updated"), "Notice"))),
                expectedMemberHints = listOf(ImageBridgeProtocol.ChatRoomMemberHint(userId = 7L, nickname = "Alice")),
                preferredPlan = preferredPlan,
            )

        assertEquals("Alice Updated", result.members.single().nickname)
        assertEquals(ImageBridgeProtocol.ChatRoomSnapshotConfidence.HIGH, result.confidence)
        assertEquals(true, result.usedPreferredPlan)
    }

    @Test
    fun `returns low confidence for single member ambiguous notice label`() {
        data class NickBox(
            val a: String,
        )

        data class Member(
            val a: Long,
            val b: NickBox,
            val displayName: String,
        )

        data class Room(
            val q: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 1L,
                room = Room(q = listOf(Member(7L, NickBox("Alice Updated"), "Notice"))),
                expectedMemberHints = listOf(ImageBridgeProtocol.ChatRoomMemberHint(userId = 7L, nickname = "Alice")),
            )

        assertEquals(ImageBridgeProtocol.ChatRoomSnapshotConfidence.LOW, result.confidence)
    }

    @Test
    fun `prefers member objects over backup map artifacts`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val q: List<Member>,
            val backups: Map<Long, String>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 1L,
                room =
                    Room(
                        q = listOf(Member(7L, "박준우")),
                        backups = linkedMapOf(7L to "openLinkChatMemberIdBackup"),
                    ),
                expectedMemberHints =
                    listOf(
                        ImageBridgeProtocol.ChatRoomMemberHint(userId = 7L, nickname = "박준우"),
                    ),
            )

        assertEquals("박준우", result.members.single().nickname)
        assertEquals("$.q", result.sourcePath)
    }

    @Test
    fun `falls back to discovery when preferred plan no longer validates`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val q: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()
        val preferredPlan =
            ImageBridgeProtocol.ChatRoomMemberExtractionPlan(
                containerPath = "$.old",
                sourceClassName = Member::class.java.name,
                userIdPath = "a",
                nicknamePath = "missing",
                fingerprint = "$.old|${Member::class.java.name}|a|missing",
            )

        val result =
            extractor.snapshot(
                roomId = 1L,
                room = Room(q = listOf(Member(7L, "Alice Updated"))),
                expectedMemberHints = listOf(ImageBridgeProtocol.ChatRoomMemberHint(userId = 7L, nickname = "Alice")),
                preferredPlan = preferredPlan,
            )

        assertEquals("Alice Updated", result.members.single().nickname)
        assertEquals(false, result.usedPreferredPlan)
        assertEquals("$.q", result.sourcePath)
    }

    @Test
    fun `keeps direct id nickname map as medium confidence when no member object exists`() {
        data class Room(
            val a: Map<Long, String>,
        )

        val extractor = ChatRoomMemberExtractor()

        val result =
            extractor.snapshot(
                roomId = 1L,
                room = Room(linkedMapOf(7L to "Alice", 9L to "Bob")),
                expectedMemberIds = setOf(7L, 9L),
            )

        assertEquals(true, result.confidence == ImageBridgeProtocol.ChatRoomSnapshotConfidence.MEDIUM || result.confidence == ImageBridgeProtocol.ChatRoomSnapshotConfidence.HIGH)
    }

    @Test
    fun `requires expected member ids for anchored extraction`() {
        data class Member(
            val a: Long,
            val b: String,
        )

        data class Room(
            val members: List<Member>,
        )

        val extractor = ChatRoomMemberExtractor()

        assertFailsWith<IllegalStateException> {
            extractor.snapshot(1L, Room(members = listOf(Member(7L, "Alice"))))
        }
    }
}
