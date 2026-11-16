package com.nousresearch.hermesmobile.data

import java.util.Calendar

/**
 * The bot-first home (Nova's mock, 2026-11-15): pinned bots on top, then the departments in a FIXED order so a
 * row never moves, then channels. Each row carries one status, loudest first, from what the gateway already
 * reports (liveness dot + the canonical chat's unread flag + its newest message); no new server fields.
 */
object BotHome {
    enum class Kind { NEEDS_OK, WAITING, WORKING, IDLE }

    data class Status(val kind: Kind, val text: String)

    /** Departments in the order the mock fixes; any other named section follows A–Z, unassigned bots last. */
    val SECTION_ORDER = listOf("Front office", "Engineering", "Personal")
    const val OTHER = "Other"

    /** The bot the floating "Talk to …" button reaches: the first pinned bot, else Ezra (chief of staff). */
    fun chief(bots: List<RoutedBot>): RoutedBot? {
        val live = bots.filter { it.activeConnection && !it.profile.botHidden }
        return live.filter { it.profile.botPinned }.minByOrNull { it.label.lowercase() }
            ?: live.firstOrNull { it.profile.name.equals("ezra", ignoreCase = true) }
    }

    /** Bots drawn as cards above the sections: every pinned bot, or the chief when nothing is pinned. */
    fun pinned(bots: List<RoutedBot>): List<RoutedBot> {
        val visible = bots.filter { !it.profile.botHidden }
        val pinned = visible.filter { it.profile.botPinned }.sortedBy { it.label.lowercase() }
        return pinned.ifEmpty { listOfNotNull(chief(visible)) }
    }

    /** Department sections below the pinned cards; [section] names a bot's department (blank = Other). */
    fun sections(bots: List<RoutedBot>, section: (RoutedBot) -> String): List<Pair<String, List<RoutedBot>>> {
        val top = pinned(bots).map { it.routeKey }.toSet()
        val grouped = bots.filter { !it.profile.botHidden && it.routeKey !in top }
            .groupBy { section(it).trim().ifBlank { OTHER } }
        val names = grouped.keys.sortedWith(compareBy<String>(
            { name -> SECTION_ORDER.indexOfFirst { it.equals(name, true) }.let { if (it < 0) Int.MAX_VALUE else it } },
            { it == OTHER },
            { it.lowercase() }
        ))
        return names.map { name -> name to grouped.getValue(name).sortedBy { it.label.lowercase() } }
    }

    /**
     * One line under the bot's name. A turn waiting on an approval or a question is "needs your OK"; a running turn
     * is "working on"; a finished turn he hasn't read is "waiting on you"; otherwise the newest line, greyed.
     */
    fun status(dot: SessionDot, unread: Boolean, preview: String): Status {
        val line = previewLine(preview)
        return when {
            dot == SessionDot.NEEDS_INPUT -> Status(Kind.NEEDS_OK, line)
            dot == SessionDot.WORKING -> Status(Kind.WORKING, line)
            unread || dot == SessionDot.UNREAD -> Status(Kind.WAITING, line)
            else -> Status(Kind.IDLE, line)
        }
    }

    /**
     * The "Needs you" strip (Nova v2, Tzvi 2026-11-16): every visible bot on this server waiting on an approval or
     * question, then every unread one, each newest first. The pinned bot is included; channels are not.
     */
    fun needsYou(bots: List<RoutedBot>, statuses: Map<String, Status>): List<RoutedBot> {
        val live = bots.filter { it.activeConnection && !it.profile.botHidden }
        fun of(kind: Kind) = live.filter { statuses[it.routeKey]?.kind == kind }
            .sortedWith(compareByDescending<RoutedBot> { it.profile.activityAt }.thenBy { it.label.lowercase() })
        return of(Kind.NEEDS_OK) + of(Kind.WAITING)
    }

    /** List or tiles, remembered on the phone (key `bots_home_view`); anything unknown is the list. */
    enum class View { LIST, TILES }
    const val VIEW_PREF = "bots_home_view"
    fun view(saved: String?): View = if (saved == "tiles") View.TILES else View.LIST
    fun viewValue(view: View): String = if (view == View.TILES) "tiles" else "list"

    data class Summary(val needYou: Int, val working: Int, val idle: Int)

    fun summary(statuses: Collection<Status>): Summary = Summary(
        needYou = statuses.count { it.kind == Kind.NEEDS_OK || it.kind == Kind.WAITING },
        working = statuses.count { it.kind == Kind.WORKING },
        idle = statuses.count { it.kind == Kind.IDLE }
    )

    /** The bot's role for the row: the description's first line (after a "Name|" prefix), trimmed to a few words. */
    fun role(profile: HermesProfile): String {
        val desc = profile.description.substringAfter('|', profile.description).lineSequence().firstOrNull().orEmpty().trim()
        val short = desc.split(Regex("""[.;,(—–-]\s""")).first().trim()
        return if (short.length > 28) short.take(27).trimEnd() + "…" else short
    }

    /** Right-hand time: clock today, weekday this week, else a short date. [epochSec] 0 = nothing. */
    fun timeLabel(epochSec: Long, nowMs: Long = System.currentTimeMillis(), use24h: Boolean = false): String {
        if (epochSec <= 0L) return ""
        val then = Calendar.getInstance().apply { timeInMillis = epochSec * 1000L }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) {
            val h = then.get(Calendar.HOUR_OF_DAY)
            val m = then.get(Calendar.MINUTE)
            return if (use24h) "%d:%02d".format(h, m) else "%d:%02d".format(if (h % 12 == 0) 12 else h % 12, m)
        }
        val days = (nowMs - then.timeInMillis) / 86_400_000L
        val locale = java.util.Locale.getDefault()
        return if (days in 0..5) then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale).orEmpty()
        else "%s %d".format(then.getDisplayName(Calendar.MONTH, Calendar.SHORT, locale).orEmpty(), then.get(Calendar.DAY_OF_MONTH))
    }
}
