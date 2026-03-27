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
