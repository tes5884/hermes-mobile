package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ApprovalCards
import com.nousresearch.hermesmobile.data.GatewayApprovals
import com.nousresearch.hermesmobile.data.GatewayApprovals.Watch
import com.nousresearch.hermesmobile.data.InlineApprovalRouter
import com.nousresearch.hermesmobile.data.ServerInteraction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t_cde1eeab: an approval raised in a turn the GATEWAY owns (ping-ezra, a teammate, cron, Kanban) never reached the app,
 * so Ezra looked stuck while the turn waited. The plugin's `approval.pending` listing now becomes ordinary approval
 * requests: a card in the bot chat on screen (header "Needs your OK"), answered back through the plugin with its token.
 */
class GatewayApprovalsTest {
    private val ezra = Watch("conn-1", "ezra", "bot-chat-ezra")

    private fun item(id: String, vararg choices: String, command: String = "git push origin main", token: String = "tok-$id") = JSONObject()
        .put("request_id", id).put("token", token).put("command", command).put("description", "push to the remote")
        .put("choices", JSONArray(choices.toList())).put("expires_at", 1e12)

    private fun listing(vararg items: JSONObject, session: String = ezra.sessionId) =
        JSONObject().put("session_id", session).put("approvals", JSONArray(items.toList()))

    @Test fun aListedApprovalIsAnApprovalRequestForThatChatWithItsToken() {
        val r = GatewayApprovals.interaction(ezra, item("6ec036b5", "once", "deny"))!!
        assertEquals("gw:6ec036b5", r.requestId)
        assertEquals("approval", r.method)
        assertEquals("bot-chat-ezra", r.params.optString("session_id"))
        assertEquals("git push origin main", r.params.optString("command"))
        assertTrue(GatewayApprovals.isGateway(r))
        assertEquals("tok-6ec036b5", GatewayApprovals.token(r))
        assertEquals(ezra, GatewayApprovals.watchOf(r))
        // The card: Approve once + Deny, never Always or Session from a gateway turn.
        val card = ApprovalCards.fromRequest(r.requestId, r.params, 1L)
        assertTrue(card.allowOnce)
        assertFalse(card.allowAlways)
        assertEquals("git push origin main", card.command)
    }

    @Test fun choicesOtherThanOnceAndDenyAreDroppedAndDenyOnlyHasNoApprove() {
        val widened = GatewayApprovals.interaction(ezra, item("a", "once", "session", "always", "deny"))!!
        assertEquals(listOf("once", "deny"), (0 until widened.params.getJSONArray("choices").length()).map { widened.params.getJSONArray("choices").getString(it) })
        val denyOnly = GatewayApprovals.interaction(ezra, item("b", "deny"))!!
        val card = ApprovalCards.fromRequest(denyOnly.requestId, denyOnly.params, 1L)
        assertFalse(card.allowOnce)
        assertEquals(listOf("deny"), ApprovalCards.choices(denyOnly.params))
    }

    @Test fun malformedItemsAreSkipped() {
        assertNull(GatewayApprovals.interaction(ezra, item("", "once", "deny")))
        assertNull(GatewayApprovals.interaction(ezra, item("x", "once", "deny", token = "")))
        assertNull(GatewayApprovals.interaction(ezra, item("x", "once")))          // no deny: not understood
        assertNull(GatewayApprovals.interaction(ezra.copy(sessionId = ""), item("x", "once", "deny")))
        assertEquals(1, GatewayApprovals.parse(ezra, listing(item("a", "once", "deny"), item("", "deny"))).size)
        // A listing for another chat is never taken for this one.
        assertTrue(GatewayApprovals.parse(ezra, listing(item("a", "once", "deny"), session = "other")).isEmpty())
    }

    @Test fun aNonGatewayRequestIsNeverAnsweredThroughThePlugin() {
        assertFalse(GatewayApprovals.isGateway(ServerInteraction("42", "approval", JSONObject().put("session_id", "s"))))
        assertFalse(GatewayApprovals.isGateway(ServerInteraction("gw:42", "approval", JSONObject())))  // no token
        assertNull(GatewayApprovals.watchOf(ServerInteraction("42", "approval", JSONObject())))
    }

    @Test fun anApprovalNoLongerListedLeavesOnlyItsOwnChat() {
        val a = GatewayApprovals.interaction(ezra, item("a", "once", "deny"))!!
        val b = GatewayApprovals.interaction(ezra, item("b", "once", "deny"))!!
        val other = GatewayApprovals.interaction(Watch("conn-1", "ward", "bot-chat-ward"), item("c", "once", "deny"))!!
        val plain = ServerInteraction("7", "approval", JSONObject().put("session_id", ezra.sessionId))
        // Answered on Desktop / timed out / the turn ended: b is gone; ward's and the chat wire's own stay.
        assertEquals(setOf("gw:b"), GatewayApprovals.gone(ezra, listOf(a, b, other, plain), listOf(a)))
        assertEquals(setOf("gw:a", "gw:b"), GatewayApprovals.gone(ezra, listOf(a, b, other, plain), emptyList()))
    }

    @Test fun watchedChatsAreTheOneOnScreenPlusEveryChatAnApprovalStillWaitsFor() {
        val ward = Watch("conn-1", "ward", "bot-chat-ward")
        val waitingInDialog = GatewayApprovals.interaction(ward, item("c", "once", "deny"))!!
        val otherServer = GatewayApprovals.interaction(Watch("conn-2", "ezra", "x"), item("d", "once", "deny"))!!
        assertEquals(listOf(ezra, ward), GatewayApprovals.watches(ezra, listOf(waitingInDialog, otherServer), "conn-1"))
        assertEquals(listOf(ward), GatewayApprovals.watches(null, listOf(waitingInDialog), "conn-1"))
        assertTrue(GatewayApprovals.watches(ezra, listOf(waitingInDialog), null).isEmpty())
        assertEquals(listOf(ezra), GatewayApprovals.watches(ezra, listOf(GatewayApprovals.interaction(ezra, item("a", "once", "deny"))!!), "conn-1"))
    }

    @Test fun itIsACardInTheBotChatOnScreenAndTheDialogElsewhere() {
        val router = InlineApprovalRouter<ServerInteraction>({ it.requestId }, { it.method }, { it.params.optString("session_id") })
        val r = GatewayApprovals.interaction(ezra, item("a", "once", "deny"))!!
        // The bot chat is open: matched by its stored session id (the gateway's id), whatever the runtime id.
        val onScreen = InlineApprovalRouter.surfaceFor(true, botChat = true, readOnly = false, "runtime-9", "bot-chat-ezra")
        assertEquals(InlineApprovalRouter.Route.CARD, router.onRequest(r, onScreen))
        assertEquals(listOf(r), router.requests)
        val r2 = GatewayApprovals.interaction(ezra, item("b", "once", "deny"))!!
        assertEquals(InlineApprovalRouter.Route.DIALOG, router.onRequest(r2, null))
        assertNotNull(router.answer("gw:a"))
    }

    @Test fun anOldPluginStopsTheAskingSilentlyAndAnswerProblemsAreWords() {
        assertTrue(GatewayApprovals.unsupported("unknown_operation"))
        assertTrue(GatewayApprovals.unsupported("HTTP 404"))
        assertFalse(GatewayApprovals.unsupported("device_auth_failed"))
        assertFalse(GatewayApprovals.unsupported(null))
        assertEquals("That approval is no longer waiting.", GatewayApprovals.answerProblem("approval_used"))
        assertTrue(GatewayApprovals.answerProblem("HTTP 500")!!.contains("didn't reach Hermes"))
    }
}
