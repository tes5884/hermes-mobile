package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomPreviewTest {
    private val room = GroupRoom("r", "Dev", listOf(GroupMember("m1", "shomer", "shomer", "Shomer")), updatedAt = 10.0)
    private fun ev(seq: Long, kind: String, text: String, at: Double, member: String = "m1") = GroupEvent(
        "r", seq, "e$seq", kind, GroupActor.fromJson(JSONObject()),
        JSONObject().put("text", text).put("member_id", member), createdAt = at)

    @Test fun skipsStatusLinesAndUsesFirstLine() {
        val p = RoomPreviews.last(room, listOf(ev(1, "message.member", "hi\nsecond", 100.0), ev(2, "turn.failed", "x", 200.0)))!!
        assertEquals(1L, p.seq)
        assertEquals("Shomer: hi", RoomPreviews.text(p, "You"))
        assertEquals(100.0, p.createdAt, 0.0)
    }

    @Test fun ownMessageIsYou() {
        val p = RoomPreviews.last(room, listOf(ev(1, "message.member", "a", 100.0), ev(2, "message.user", "\n  go", 150.0)))!!
        assertEquals("You: go", RoomPreviews.text(p, "You"))
    }

    @Test fun noMessagesIsNull() = assertNull(RoomPreviews.last(room, listOf(ev(1, "turn.failed", "", 1.0))))

    @Test fun sortsNewestActivityFirst() {
        val a = room.copy(roomId = "a", updatedAt = 5.0)
        val b = room.copy(roomId = "b", updatedAt = 1.0)
        val c = room.copy(roomId = "c", updatedAt = 99.0)
        val previews = mapOf("b" to RoomPreview(1, false, "x", "y", 500.0), "a" to RoomPreview(1, false, "x", "y", 400.0))
        assertEquals(listOf("b", "a", "c"), RoomPreviews.sorted(listOf(a, b, c), previews).map { it.roomId })
    }
}
