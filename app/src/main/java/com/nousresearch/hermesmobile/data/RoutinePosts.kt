package com.nousresearch.hermesmobile.data

/**
 * LOCAL (routine-chat): what a scheduled routine wrote, as opposed to what a person or a bot said.
 *
 * - In a bot's own chat, our hermes-android plugin patch stores a routine's result as a bot-side message wrapped in
 *   two label lines (the model reads them as "automated output"; people should not have to). [parts] lifts those
 *   lines out and says which stretches of a message are routine output.
 * - In a room, Hermes writes a scheduled post as `message.user` with an actor id "cron:<job>" / "cron-notice:<job>",
 *   so it would otherwise be drawn as the user's own message. [isScheduled] tells them apart.
 */
object RoutinePosts {
    private val HEAD = Regex("""^\[Automated routine result: "(.*)"\. Produced by a scheduled job.*]$""")
    private const val TAIL = "[End of automated routine result]"

    /** A stretch of one message: routine output when [routine] is non-null (the routine's name, possibly blank). */
    data class Part(val routine: String?, val text: String)

    fun hasRoutine(content: String): Boolean = content.contains("[Automated routine result: \"")

    /**
     * Splits [content] into ordinary text and labelled routine results, in order, label lines removed. Hermes merges
     * back-to-back bot turns, so one message can hold a reply followed by one or more routine results. A head line
     * with no tail (cut off) still counts as routine output up to the end.
     */
    fun parts(content: String): List<Part> {
        if (!hasRoutine(content)) return listOf(Part(null, content))
        val out = mutableListOf<Part>()
        val buffer = StringBuilder()
        var routine: String? = null
        fun flush() {
            val text = buffer.toString().trim('\n')
            if (text.isNotBlank()) out += Part(routine, text)
            buffer.setLength(0)
        }
        for (line in content.split("\n")) {
            val head = if (routine == null) HEAD.matchEntire(line.trim()) else null
            when {
                head != null -> { flush(); routine = head.groupValues[1] }
                routine != null && line.trim() == TAIL -> { flush(); routine = null }
                else -> buffer.append(line).append('\n')
            }
        }
        flush()
        return out.ifEmpty { listOf(Part(null, "")) }
    }

    /** [content] as a person should read or copy it: label lines gone. */
    fun plain(content: String): String =
        if (!hasRoutine(content)) content else parts(content).joinToString("\n\n") { it.text }

    /** True for a room message written by a scheduled job (not by the user, whatever its event kind says). */
    fun isScheduled(event: GroupEvent): Boolean = event.kind == "message.user" && isScheduledActor(event.actor.id)

    fun isScheduledActor(actorId: String): Boolean = actorId.startsWith("cron:") || actorId.startsWith("cron-notice:")

    /** The job's display name without its "[bot:x]" tag. */
    fun name(displayName: String): String = displayName.replace(Regex("""^\[bot:[^\]]+]\s*"""), "").trim()

    /**
     * True when the notice's content already sits in a chat or a room, so the Notifications inbox must not keep a
     * second copy: a routine result written into the bot's chat (plugin patch: a `reminder` that names a session) or
     * a scheduled room post (room-push: `room_update` on "room:<id>"). Approvals, mentions, failures and plain
     * reminders stay in the inbox.
     */
    fun livesInChat(eventType: String, sessionId: String): Boolean {
        val session = sessionId.takeUnless { it == "null" }.orEmpty()
        if (session.isBlank()) return false
        return when (eventType) {
            "reminder" -> !session.startsWith("room:")
            "room_update" -> session.startsWith("room:")
            else -> false
        }
    }

    /** First real line of a notice body, markdown marks dropped: what to look for in the chat to land on it. */
    fun anchorLine(body: String): String =
        body.lineSequence().map { normalize(it) }.firstOrNull { it.isNotEmpty() && !it.startsWith("MEDIA:") }.orEmpty().take(80)

    /** Same folding as the room push's first line (marks removed), so the two can be compared. */
    fun normalize(line: String): String =
        line.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1").replace(Regex("[*_`#>|]+"), "").trim().trimEnd('…').trim()

    /** True when [content] (a chat message) holds the line a notice was made from. */
    fun holdsAnchor(content: String, anchor: String): Boolean =
        anchor.isNotBlank() && content.lineSequence().any { normalize(it).startsWith(anchor) }

    /**
     * The scheduled room post a room notice ("<routine>: <first line>") was made from: the newest one whose first
     * line the notice ends with, else the newest scheduled post. Null when the room shows none.
     */
    fun roomTarget(events: List<GroupEvent>, noticeBody: String): GroupEvent? {
        val posts = events.filter { isScheduled(it) }.sortedByDescending { it.seq }
        val notice = normalize(noticeBody)
        return posts.firstOrNull { post ->
            val first = post.text.lineSequence().map { normalize(it) }.firstOrNull { it.isNotEmpty() }.orEmpty().take(60)
            first.isNotEmpty() && notice.contains(first)
        } ?: posts.firstOrNull()
    }
}
