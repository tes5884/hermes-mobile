package com.nousresearch.hermesmobile.data

/** Rooms-list row: the newest real message ("Sender: first line") and its time. Built from groups.log only. */
data class RoomPreview(val seq: Long, val fromUser: Boolean, val sender: String, val line: String, val createdAt: Double)

object RoomPreviews {
    /** Newest message.user / message.member; status lines (turn.*, member.*, ...) are skipped. */
    fun last(room: GroupRoom, events: List<GroupEvent>): RoomPreview? {
        val e = events.filter { it.kind == "message.user" || it.kind == "message.member" }.maxByOrNull { it.seq } ?: return null
        val scheduled = RoutinePosts.isScheduled(e)
        val fromUser = e.kind == "message.user" && !scheduled
        val member = room.members.firstOrNull { it.memberId == e.memberId }
        val sender = if (fromUser) "" else if (scheduled) RoutinePosts.name(e.actor.displayName).ifBlank { "Routine" } else
            (member?.label ?: e.actor.displayName.ifBlank { e.actor.profile.ifBlank { e.actor.id } })
        val firstLine = e.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: if (e.attachments.isNotEmpty()) "📎" else ""
        return RoomPreview(e.seq, fromUser, sender, firstLine, e.createdAt)
    }

    /** "Sender: line" ("You: line" for the user's own; [you] is the localized word). */
    fun text(p: RoomPreview, you: String): String {
        val who = if (p.fromUser) you else p.sender
        return if (who.isBlank()) p.line else "$who: ${p.line}"
    }

    /** Newest activity first: last message time, else the room's own updatedAt. */
    fun sorted(rooms: List<GroupRoom>, previews: Map<String, RoomPreview>): List<GroupRoom> =
        rooms.sortedByDescending { previews[it.roomId]?.createdAt?.takeIf { t -> t > 0 } ?: it.updatedAt }
}
