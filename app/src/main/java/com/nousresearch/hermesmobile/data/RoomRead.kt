package com.nousresearch.hermesmobile.data

import android.content.SharedPreferences

/**
 * Where the reader left off in a room. Phone-only (the gateway has no read marker for rooms): per room id we keep the
 * highest seq that was actually on screen. Reading on the desktop does not move this.
 */
object RoomRead {
    private fun isFromBot(e: GroupEvent) = e.kind == "message.member"

    /** First message from a bot newer than what was seen; null when nothing is unread or there is no saved position. */
    fun firstUnreadSeq(events: List<GroupEvent>, saved: Long?): Long? =
        if (saved == null) null else events.firstOrNull { isFromBot(it) && it.seq > saved }?.seq

    fun unreadCount(events: List<GroupEvent>, seen: Long): Int = events.count { isFromBot(it) && it.seq > seen }

    /** Tzvi's own newest message: sending counts as having read everything up to it. */
    fun newestOwnSeq(events: List<GroupEvent>): Long = events.filter { it.kind == "message.user" }.maxOfOrNull { it.seq } ?: 0L

    fun chipLabel(unread: Int): String = if (unread > 0) "↓ $unread new" else "↓"

    private fun key(roomId: String) = "seen:$roomId"
    fun load(prefs: SharedPreferences, roomId: String): Long? =
        if (prefs.contains(key(roomId))) prefs.getLong(key(roomId), 0L) else null

    /** Only ever moves forward. */
    fun save(prefs: SharedPreferences, roomId: String, seq: Long) {
        if (seq <= (load(prefs, roomId) ?: 0L)) return
        prefs.edit().putLong(key(roomId), seq).apply()
    }
}
