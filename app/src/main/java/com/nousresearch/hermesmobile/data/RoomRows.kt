package com.nousresearch.hermesmobile.data

/** One row of the room transcript: a day divider or an event. */
sealed class RoomRow(val key: String) {
    class Day(val millis: Long, epochDay: Long) : RoomRow("day:$epochDay")
    class Event(val event: GroupEvent) : RoomRow("e:${event.seq}")
}

object RoomRows {
    /** createdAt is epoch seconds (Double); 0 means unknown. Returns millis or null. */
    fun millis(event: GroupEvent): Long? =
        event.createdAt.takeIf { it > 0.0 }?.let { (it * 1000.0).toLong() }

    /**
     * Chronological events -> display rows with a Day row before the first dated event
     * of each local day. Events without a timestamp get no divider.
     * Returned oldest-first; reverse it for a reverseLayout list (the divider then
     * sits above the day's first message).
     */
    fun build(chronological: List<GroupEvent>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<RoomRow> {
        val out = ArrayList<RoomRow>(chronological.size + 4)
        var lastDay = Long.MIN_VALUE
        for (e in chronological) {
            val ms = millis(e)
            if (ms != null) {
                val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
                if (day != lastDay) { out.add(RoomRow.Day(ms, day)); lastDay = day }
            }
            out.add(RoomRow.Event(e))
        }
        return out
    }
}
