package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RoomCacheTest {
    private fun ev(seq: Long, text: String = "t$seq") = GroupEvent(
        "r", seq, "e$seq", "message.member", GroupActor("member", "m1", "shomer", "Shomer"),
        JSONObject().put("text", text).put("member_id", "m1"), createdAt = seq.toDouble())
    private val room = GroupRoom("r", "Dev", listOf(GroupMember("m1", "shomer", "shomer", "Shomer")), updatedAt = 10.0, latestSeq = 5L)
    private fun tmp() = File.createTempFile("roomcache", "").let { it.delete(); it.mkdirs(); it }

    @Test fun mergeDedupesBySeqAndFreshWins() {
        val m = RoomCache.merge(listOf(ev(1), ev(2, "old"), ev(3)), listOf(ev(2, "new"), ev(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), m.map { it.seq })
        assertEquals("new", m.first { it.seq == 2L }.text)
    }

    @Test fun mergeSortsAndKeepsNewestN() {
        val m = RoomCache.merge(listOf(ev(5), ev(1)), listOf(ev(3), ev(4), ev(2)), limit = 3)
        assertEquals(listOf(3L, 4L, 5L), m.map { it.seq })
    }

    @Test fun mergeIsIdempotent() {
        val a = RoomCache.merge(listOf(ev(1), ev(2)), listOf(ev(2), ev(3)))
        assertEquals(a.map { it.seq }, RoomCache.merge(a, a).map { it.seq })
    }

    @Test fun roomRoundTripKeepsLastNOnly() {
        val c = RoomCache(tmp(), "conn/1", limit = 3)
        c.saveRoom(room, (1L..10L).map { ev(it) })
        val back = c.loadRoom("r")!!
        assertEquals(listOf(8L, 9L, 10L), back.events.map { it.seq })
        assertEquals("Dev", back.room.name)
        assertEquals("Shomer", back.room.members.single().label)
        assertEquals("t9", back.events[1].text)
        assertEquals("Shomer", back.events[0].actor.displayName)
    }

    @Test fun listRoundTripWithPreviews() {
        val c = RoomCache(tmp(), "c")
        val p = RoomPreview(7, false, "Shomer", "hi", 99.0)
        c.saveList(listOf(room), mapOf("r" to p))
        val back = c.loadList()!!
        assertEquals("r", back.rooms.single().roomId)
        assertEquals(p, back.previews["r"])
    }

    @Test fun missingOrCorruptIsNoCache() {
        val dir = tmp()
        val c = RoomCache(dir, "c")
        assertNull(c.loadRoom("nope")); assertNull(c.loadList())
        c.saveRoom(room, listOf(ev(1)))
        File(File(dir, "c"), "room_r.json").writeText("{not json")
        assertNull(c.loadRoom("r"))
    }

    @Test fun clearDropsEverything() {
        val c = RoomCache(tmp(), "c")
        c.saveRoom(room, listOf(ev(1))); c.clear()
        assertNull(c.loadRoom("r"))
    }
}
