package com.nousresearch.hermesmobile.data

/**
 * LOCAL (routine-chat): what a scheduled routine wrote, as opposed to what a person or a bot said.
 *
 * - In a bot's own chat, our hermes-android plugin patch stores a routine's result as a bot-side message: a head
 *   label line and an end label line that share one random id, and between them every line of the result quoted
 *   with "> " (the model reads that as "automated output"; people should not have to). [parts] lifts the two label
 *   lines out, takes the quote prefix off, and says which stretches of a message are routine output.
 * - In a room, Hermes writes a scheduled post as `message.user` with an actor id "cron:<job>" / "cron-notice:<job>",
 *   so it would otherwise be drawn as the user's own message. [isScheduled] tells them apart.
 */
object RoutinePosts {
    private val HEAD = Regex("""^\[Automated routine result id=([0-9a-f]{8}): "(.*)"\. Produced by a scheduled job.*]$""")
    private const val MARK = "[Automated routine result id="
    private fun tail(id: String) = "[End of automated routine result id=$id]"

    /** A stretch of one message: routine output when [routine] is non-null (the routine's name, possibly blank). */
    data class Part(val routine: String?, val text: String)

    fun hasRoutine(content: String): Boolean = content.contains(MARK)

    /** A quoted line as the routine wrote it: the "> " the plugin put in front is taken off (">" for an empty line). */
    private fun unquote(line: String): String =
        if (line.startsWith("> ")) line.substring(2) else if (line.startsWith(">")) line.substring(1) else line

    /**
     * Splits [content] into ordinary text and labelled routine results, in order: label lines removed, quote prefix
     * taken off each result line. Hermes merges back-to-back bot turns, so one message can hold a reply followed by
     * one or more routine results. A result runs from a head line to the end line with the SAME id and nothing else
     * closes it; a head line with no such end line is ordinary text.
     */
    fun parts(content: String): List<Part> {
        if (!hasRoutine(content)) return listOf(Part(null, content))
        val lines = content.split("\n")
        val out = mutableListOf<Part>()
        val plain = mutableListOf<String>()
        fun add(routine: String?, body: List<String>) {
            val text = body.joinToString("\n").trim('\n')
            if (text.isNotBlank()) out += Part(routine, text)
        }
        var i = 0
        while (i < lines.size) {
            val head = HEAD.matchEntire(lines[i].trim())
            val end = if (head == null) -1 else tail(head.groupValues[1]).let { t ->
                (i + 1 until lines.size).firstOrNull { lines[it].trim() == t } ?: -1
            }
            if (head == null || end < 0) { plain += lines[i]; i++; continue }
            add(null, plain); plain.clear()
            add(head.groupValues[2], lines.subList(i + 1, end).map(::unquote))
            i = end + 1
        }
        add(null, plain)
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
