package com.nousresearch.hermesmobile.push

import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

/**
 * Opening a chat in the app, by any route (launcher, bot list, back from another app), clears that chat's notices
 * from the shade, the way tapping one already did. Other chats' notices stay. The bot's group summary is re-counted,
 * or removed when nothing of that bot is left (Tzvi 2026-11-16).
 */
object PushClear {
    /** Set on every notice [PushDelivery.show] posts: the chat and server it belongs to. */
    const val EXTRA_SESSION = "hermes.session"
    const val EXTRA_CONNECTION = "hermes.connection"
    const val EXTRA_PROFILE = "hermes.profile"
    const val EXTRA_TYPE = "hermes.type"

    /**
     * Never cleared by opening the chat: an approval stays in the shade until the server resolves it (answered
     * anywhere, or timed out → `approval_resolved` withdraws it). On resume the gateway may not have replayed the
     * card yet, and pending cards are not kept on the phone, so clearing it could leave nothing to act on in time.
     */
    val KEEP = setOf("approval_required")

    /** What the push inbox knows about a notice it stored (vc89 notices carry no extras). */
    data class Stored(val session: String, val connection: String, val profile: String, val type: String)

    /** One notice in the shade, as far as this rule cares. Empty strings when unknown. */
    data class Shown(val tag: String, val group: String, val summary: Boolean,
                     val session: String, val connection: String, val profile: String, val type: String = "")

    /** What a clear does: notices to cancel, then per bot group the summary to re-count (left > 0) or drop (0). */
    data class Plan(val cancel: List<String>, val groups: List<GroupLeft>)
    data class GroupLeft(val group: String, val summaryTag: String?, val left: Int, val connection: String, val profile: String)

    /**
     * Pure rule. A notice belongs to [session] on [connection] when its own extras say so; a notice posted before
     * the extras existed is looked up in [stored] (the push inbox: event id → session, connection). A notice with no
     * known connection matches on the session alone. Summaries are never matched directly: only re-counted.
     */
    fun plan(shown: List<Shown>, stored: Map<String, Stored>, session: String, connection: String): Plan {
        if (session.isBlank()) return Plan(emptyList(), emptyList())
        // A notice without extras takes its chat, bot, server and type from the inbox (also used for the recount).
        val known = shown.map { n ->
            val s = if (n.summary || n.session.isNotBlank()) null else stored[n.tag]
            if (s == null) n else n.copy(session = s.session, connection = s.connection, profile = s.profile, type = s.type)
        }
        val hits = known.filter { n ->
            !n.summary && n.type !in KEEP && n.session == session &&
                (n.connection.isBlank() || connection.isBlank() || n.connection == connection)
        }
        if (hits.isEmpty()) return Plan(emptyList(), emptyList())
        val gone = hits.map { it.tag }.toSet()
        val groups = hits.map { it.group }.filter { it.isNotBlank() }.distinct().map { g ->
            val members = known.filter { it.group == g }
            val hit = hits.first { it.group == g }
            GroupLeft(g, members.firstOrNull { it.summary }?.tag,
                members.count { !it.summary && it.tag !in gone }, hit.connection, hit.profile)
        }
        return Plan(hits.map { it.tag }, groups)
    }

    /** Clears [session]'s notices on [connection]. Binder and disk work: call off the main thread. */
    fun clear(context: Context, session: String, connection: String) {
        if (session.isBlank()) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { nm.activeNotifications }.getOrNull() ?: return
        val shown = active.filter { it.packageName == context.packageName && it.tag != null }.map { sbn ->
            val n = sbn.notification
            Shown(sbn.tag, n.group.orEmpty(), (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0,
                n.extras?.getString(EXTRA_SESSION).orEmpty(), n.extras?.getString(EXTRA_CONNECTION).orEmpty(),
                n.extras?.getString(EXTRA_PROFILE).orEmpty(), n.extras?.getString(EXTRA_TYPE).orEmpty())
        }
        if (shown.isEmpty()) return
        val stored = runCatching { PushStore(context).events() }.getOrDefault(emptyList()).associate { e: JSONObject ->
            e.optString("event_id") to Stored(e.optString("session_id"), e.optString("connection"),
                e.optString("profile"), e.optString("event_type"))
        }
        val plan = plan(shown, stored, session, connection)
        plan.cancel.forEach { nm.cancel(it, 0) }
        plan.groups.forEach { g ->
            if (g.left == 0) g.summaryTag?.let { nm.cancel(it, 0) }
            else if (g.profile.isNotBlank()) PushDelivery.refreshSummary(context, g.profile, g.connection)
        }
    }
}
