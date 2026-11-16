package com.nousresearch.hermesmobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Approvals raised in turns the GATEWAY owns (a teammate's message, ping-ezra, a cron or Kanban run in a bot chat), card
 * t_cde1eeab. They never reach the chat wire this app uses: the gateway parks them in its own session authority and only
 * Desktop is told. The hermes-android plugin lists them (`approval.pending {session_id}`) with a single-use token per
 * request for this phone, and answers one (`approval.answer {token, choice}`, once or deny only).
 *
 * Each listed approval becomes an ordinary approval request (method "approval", id [PREFIX] + the gateway's request id),
 * so it is routed like every other one: a card in the bot chat on screen (header "Needs your OK"), else the dialog. Only
 * the answer differs: it goes back through the plugin with the token, never as a reply on the chat socket.
 */
object GatewayApprovals {
    const val PREFIX = "gw:"
    /** While the chat is open (and a listed approval waits anywhere) it is asked again this often. */
    const val POLL_MS = 4_000L
    const val TOKEN = "gateway_token"
    const val CONNECTION = "gateway_connection"
    const val PROFILE = "gateway_profile"

    fun isGateway(requestId: String): Boolean = requestId.startsWith(PREFIX)
    fun isGateway(request: ServerInteraction): Boolean = isGateway(request.requestId) && request.params.optString(TOKEN).isNotBlank()
    fun token(request: ServerInteraction): String = request.params.optString(TOKEN)
    fun session(request: ServerInteraction): String = request.params.optString("session_id")
    /** The server and bot the approval was listed by (its token is only good there). */
    fun watchOf(request: ServerInteraction): Watch? {
        val connection = request.params.optString(CONNECTION)
        val profile = request.params.optString(PROFILE)
        val sid = session(request)
        return if (!isGateway(request) || connection.isBlank() || profile.isBlank() || sid.isBlank()) null else Watch(connection, profile, sid)
    }

    /** One `approval.pending` item as an approval request for [watch]'s chat; null when it is malformed. */
    fun interaction(watch: Watch, item: JSONObject): ServerInteraction? {
        val sessionId = watch.sessionId
        val id = item.optString("request_id").trim()
        val token = item.optString("token").trim()
        if (id.isEmpty() || token.isEmpty() || sessionId.isBlank()) return null
        val listed = item.optJSONArray("choices") ?: JSONArray()
        val choices = (0 until listed.length()).map { listed.optString(it) }.filter { it == "once" || it == "deny" }.distinct()
        // Deny is always possible; a list without it is not one this app understands.
        if ("deny" !in choices) return null
        val params = JSONObject()
            .put("session_id", sessionId)
            .put("request_id", id)
            .put("command", item.optString("command"))
            .put("description", item.optString("description"))
            .put("choices", JSONArray(choices.sortedBy { if (it == "once") 0 else 1 }))
            .put("allow_permanent", false)
            .put("allow_session", false)
            .put(TOKEN, token)
            .put(CONNECTION, watch.connection)
            .put(PROFILE, watch.profile)
        return ServerInteraction(PREFIX + id, "approval", params, replayed = true)
    }

    /** The `approval.pending` result for [watch]'s chat: its approvals as requests, in the server's order. */
    fun parse(watch: Watch, result: JSONObject): List<ServerInteraction> {
        if (result.optString("session_id", watch.sessionId) != watch.sessionId) return emptyList()
        val arr = result.optJSONArray("approvals") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { interaction(watch, it) } }
    }

    /** Of the gateway approvals shown for [watch]'s chat, those its latest listing no longer has: answered elsewhere, timed out or the turn ended. */
    fun gone(watch: Watch, shown: Collection<ServerInteraction>, listed: Collection<ServerInteraction>): Set<String> {
        val now = listed.map { it.requestId }.toSet()
        return shown.filter { watchOf(it) == watch && it.requestId !in now }.map { it.requestId }.toSet()
    }

    /** A plugin that predates these operations: the app stops asking that server, and says nothing. */
    fun unsupported(code: String?): Boolean = code == "unknown_operation" || code == "HTTP 404"

    /** What to tell the user when an answer did not go through (null: nothing to say, the card just goes). */
    fun answerProblem(code: String?): String? = when (code) {
        null -> null
        "approval_used", "approval_expired", "approval_not_found" -> "That approval is no longer waiting."
        "invalid_choice" -> "That answer isn't offered for this approval."
        else -> "Your answer didn't reach Hermes ($code). Check the chat and try again."
    }

    /**
     * The chats to ask, as (connection, profile, session): the bot chat on screen, plus every chat a listed approval still
     * waits for in the dialog or as a card, so one answered elsewhere or timed out also leaves the dialog.
     */
    data class Watch(val connection: String, val profile: String, val sessionId: String)

    fun watches(onScreen: Watch?, waiting: Collection<ServerInteraction>, connection: String?): List<Watch> {
        if (connection == null) return emptyList()
        val fromWaiting = waiting.mapNotNull { watchOf(it) }.filter { it.connection == connection }
        return (listOfNotNull(onScreen?.takeIf { it.connection == connection }) + fromWaiting).distinct()
    }
}
