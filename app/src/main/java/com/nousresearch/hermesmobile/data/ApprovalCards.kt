package com.nousresearch.hermesmobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Inline approval cards in a bot's chat (Nova's mock, Tzvi 2026-11-15): Approve once / Deny, plus Always only when
 * Hermes offers it (allow_permanent); "session" is never shown, since a bot's one chat never ends.
 *
 * Hermes keeps no record of how an approval was answered, so the phone keeps one per chat: that is what lets the
 * collapsed "Approved once · 9:41" / "You said no" card survive a reload. A card is placed by time: after the last
 * message at or before the moment the request arrived.
 */
object ApprovalCards {
    enum class State { PENDING, ONCE, ALWAYS, DENIED }

    data class Card(
        val requestId: String,
        val description: String,
        val command: String,
        val reason: String = "",
        val allowAlways: Boolean = false,
        val createdAtMs: Long,
        val state: State = State.PENDING,
        val resolvedAtMs: Long = 0L
    ) {
        /** The plain-words line: the description, else the command's first line. */
        val title: String get() = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: command.lineSequence().firstOrNull().orEmpty().trim()
        /** Anything the description says after its first line (the effect, in the mock). */
        val effect: String get() = description.lineSequence().filter { it.isNotBlank() }.drop(1).joinToString(" ").trim()
    }

    /** What an approval request offers on this card: once, always (only when Hermes allows it), deny. */
    fun choices(params: JSONObject): List<String> {
        val arr = params.optJSONArray("choices")
        val offered = if (arr == null || arr.length() == 0) listOf("once", "deny") else (0 until arr.length()).map { arr.optString(it) }
        val always = "always" in offered && params.optBoolean("allow_permanent", true) && !params.optBoolean("smart_denied", false)
        return listOfNotNull("once", "always".takeIf { always }, "deny")
    }

    fun fromRequest(requestId: String, params: JSONObject, nowMs: Long): Card = Card(
        requestId = requestId,
        description = params.optString("description").trim(),
        command = params.optString("command").trim(),
        reason = params.optString("pattern_key").trim().takeIf { it.isNotBlank() && it != params.optString("description").trim() }.orEmpty(),
        allowAlways = "always" in choices(params),
        createdAtMs = nowMs
    )

    fun stateFor(choice: String): State = when (choice) {
        "once" -> State.ONCE
        "always" -> State.ALWAYS
        else -> State.DENIED
    }

    /** Adds or replaces (same request id) a card, oldest first, keeping the newest [cap]. A replayed request keeps its first time. */
    fun upsert(cards: List<Card>, card: Card, cap: Int = 100): List<Card> {
        val existing = cards.firstOrNull { it.requestId == card.requestId }
        val merged = if (existing == null) card else card.copy(createdAtMs = existing.createdAtMs)
        return (cards.filterNot { it.requestId == card.requestId } + merged).sortedBy { it.createdAtMs }.takeLast(cap)
    }

    fun resolve(cards: List<Card>, requestId: String, choice: String, nowMs: Long): List<Card> =
        cards.map { if (it.requestId == requestId && it.state == State.PENDING) it.copy(state = stateFor(choice), resolvedAtMs = nowMs) else it }

    /** A request withdrawn by the server (answered on another device, timed out): a pending card goes; an answered one stays. */
    fun withdraw(cards: List<Card>, requestId: String): List<Card> =
        cards.filterNot { it.requestId == requestId && it.state == State.PENDING }

    /**
     * Index of the row each answered card follows: the last row whose time is at or before the card's. Rows carry
     * epoch seconds or milliseconds (0 = unknown, skipped); -1 = before every loaded row (not shown: older history).
     */
    fun anchors(cards: List<Card>, rowTimes: List<Long>): Map<String, Int> = cards.filter { it.state != State.PENDING }.associate { card ->
        var at = -1
        rowTimes.forEachIndexed { i, t ->
            val ms = if (t in 1..9_999_999_999L) t * 1000L else t
            if (ms in 1..card.createdAtMs) at = i
        }
        card.requestId to at
    }

    fun encode(cards: List<Card>): String = JSONArray().apply {
        cards.forEach { c ->
            put(JSONObject().put("id", c.requestId).put("description", c.description).put("command", c.command)
                .put("reason", c.reason).put("always", c.allowAlways).put("at", c.createdAtMs).put("state", c.state.name).put("resolved", c.resolvedAtMs))
        }
    }.toString()

    fun decode(raw: String?): List<Card> = runCatching {
        val arr = JSONArray(raw ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Card(
                requestId = o.optString("id").ifBlank { return@mapNotNull null },
                description = o.optString("description"), command = o.optString("command"), reason = o.optString("reason"),
                allowAlways = o.optBoolean("always"), createdAtMs = o.optLong("at"),
                state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.PENDING),
                resolvedAtMs = o.optLong("resolved")
            )
        }
    }.getOrDefault(emptyList())
}

/** Answered cards per chat on this phone (SharedPreferences); pending ones are never stored, the server replays those. */
class ApprovalCardStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("hermes_approval_cards", android.content.Context.MODE_PRIVATE)

    private fun key(profile: String, storedSessionId: String) = "$profile::$storedSessionId"

    fun load(profile: String, storedSessionId: String): List<ApprovalCards.Card> =
        ApprovalCards.decode(prefs.getString(key(profile, storedSessionId), null))

    fun save(profile: String, storedSessionId: String, cards: List<ApprovalCards.Card>) {
        val answered = cards.filter { it.state != ApprovalCards.State.PENDING }
        prefs.edit().apply {
            if (answered.isEmpty()) remove(key(profile, storedSessionId)) else putString(key(profile, storedSessionId), ApprovalCards.encode(answered))
        }.apply()
    }
}
