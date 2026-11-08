package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomReadTest {
    private fun ev(seq: Long, kind: String = "message.member", at: Double = 1794092400.0 + seq) =
        GroupEvent("r", seq, "e$seq", kind, GroupActor.fromJson(JSONObject()), JSONObject(), createdAt = at)
    private val events = listOf(ev(1), ev(2, "message.user"), ev(3), ev(4), ev(5, "turn.failed"), ev(6))

    @Test fun firstUnreadIsFirstBotMessageAfterSaved() {
        assertEquals(3L, RoomRead.firstUnreadSeq(events, 2L))
        assertEquals(6L, RoomRead.firstUnreadSeq(events, 4L))
        assertNull(RoomRead.firstUnreadSeq(events, 6L))
        assertNull(RoomRead.firstUnreadSeq(events, null))
    }

    @Test fun countsOnlyBotMessages() {
        assertEquals(3, RoomRead.unreadCount(events, 1L)) // 3, 4, 6
        assertEquals(0, RoomRead.unreadCount(events, 6L))
    }

    @Test fun ownMessageCountsAsRead() {
        assertEquals(2L, RoomRead.newestOwnSeq(events))
        assertEquals(0L, RoomRead.newestOwnSeq(listOf(ev(1))))
    }

    @Test fun chipLabel() {
        assertEquals("↓ 3 new", RoomRead.chipLabel(3))
        assertEquals("↓", RoomRead.chipLabel(0))
    }

    @Test fun dividerSitsAboveFirstUnreadAndBelowItsDayDivider() {
        val rows = RoomRows.build(events, java.time.ZoneOffset.UTC, newMessagesBeforeSeq = 3L)
        val kinds = rows.map { when (it) { is RoomRow.Day -> "D"; is RoomRow.NewMessages -> "N"; is RoomRow.Event -> "E${it.event.seq}" } }
        assertEquals(listOf("D", "E1", "E2", "N", "E3", "E4", "E5", "E6"), kinds)
        // In the reversed (displayed) list the divider comes after (= above) the first unread.
        val rev = rows.asReversed()
        assertTrue(rev[rev.indexOfFirst { it is RoomRow.NewMessages } - 1].let { it is RoomRow.Event && it.event.seq == 3L })
    }

    @Test fun noDividerWhenNothingUnread() {
        assertTrue(RoomRows.build(events, java.time.ZoneOffset.UTC).none { it is RoomRow.NewMessages })
    }
}

class UnreadIndicatorsTest {
    private fun ev(seq: Long, kind: String) =
        GroupEvent("r", seq, "e$seq", kind, GroupActor.fromJson(JSONObject()), JSONObject())

    @Test fun ownMessageAfterMarkerIsZero() {
        assertEquals(0, RoomRead.listUnread(listOf(ev(5, "message.user"), ev(6, "message.user")), 4L))
    }

    @Test fun mixedBotAndStatusLinesCountsBotsOnly() {
        val events = listOf(ev(5, "message.member"), ev(6, "turn.completed"), ev(7, "room.settled"), ev(8, "message.user"), ev(9, "message.member"))
        assertEquals(2, RoomRead.listUnread(events, 4L))
    }

    @Test fun neverOpenedRoomShowsNothing() {
        assertEquals(0, RoomRead.listUnread(listOf(ev(1, "message.member")), null))
    }

    @Test fun botUnreadFromRosterFlagOrSession() {
        val p = HermesProfile(name = "ezra", canonicalSession = "s1")
        assertFalse(botIsUnread(p, emptyList()))
        assertTrue(botIsUnread(p.copy(canonicalUnread = true), emptyList()))
        assertTrue(botIsUnread(p, listOf(HermesSession(id = "s1", unread = true))))
        assertFalse(botIsUnread(p, listOf(HermesSession(id = "s2", unread = true))))
    }
}
