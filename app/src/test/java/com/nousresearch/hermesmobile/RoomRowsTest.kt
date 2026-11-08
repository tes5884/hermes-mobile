package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomRowsTest {
    private fun ev(seq: Long, at: Double) = GroupEvent("r", seq, "e$seq", "message.user",
        GroupActor.fromJson(JSONObject()), JSONObject(), createdAt = at)
    private val utc = java.time.ZoneOffset.UTC

    @Test fun dayDividerBeforeFirstMessageOfEachDay() {
        // 2026-11-07 23:00Z, 23:30Z, 2026-11-08 00:10Z
        val rows = RoomRows.build(listOf(ev(1, 1794092400.0), ev(2, 1794094200.0), ev(3, 1794096600.0)), utc)
        assertEquals(listOf("D", "E", "E", "D", "E"), rows.map { if (it is RoomRow.Day) "D" else "E" })
        // reversed list: the divider comes AFTER (above) the day's first message
        val rev = rows.asReversed()
        assertTrue(rev[0] is RoomRow.Event && rev[1] is RoomRow.Day)
    }

    @Test fun zeroTimestampGetsNoDivider() {
        val rows = RoomRows.build(listOf(ev(1, 0.0), ev(2, 0.0)), utc)
        assertEquals(2, rows.size)
        assertNull(RoomRows.millis(ev(1, 0.0)))
    }

    @Test fun keysAreUnique() {
        val rows = RoomRows.build(listOf(ev(1, 1794092400.0), ev(2, 1794096600.0)), utc)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
