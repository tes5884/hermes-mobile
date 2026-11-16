package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.GatewayRpcException
import com.nousresearch.hermesmobile.data.OwnerRefusal
import com.nousresearch.hermesmobile.data.OwnerRefusal.Banner
import com.nousresearch.hermesmobile.data.OwnerRefusal.Outcome
import com.nousresearch.hermesmobile.data.QueuedPrompt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * t_cde1eeab: "The gateway is running a turn in this chat and the gateway refused it. Nothing was sent." A send refused
 * because the gateway's turn runs waits, visibly, and goes again by itself; one the gateway may already hold is kept but
 * never re-sent by itself; neither is ever lost.
 */
class OwnerRefusalTest {
    private fun refusal(reason: String?) = GatewayRpcException(
        "The gateway is running a turn in this chat and the gateway refused it. Nothing was sent. Send it again when the current turn ends.",
        4090, reason?.let { JSONObject().put("reason", it) })

    @Test fun theRefusalReasonComesFromTheErrorData() {
        assertEquals(OwnerRefusal.BUSY, OwnerRefusal.reason(refusal("SESSION_OWNER_BUSY")))
        assertEquals(OwnerRefusal.UNCONFIRMED, OwnerRefusal.reason(refusal("SESSION_OWNER_UNCONFIRMED")))
        assertNull(OwnerRefusal.reason(refusal("SESSION_NOT_OWNED")))
        assertNull(OwnerRefusal.reason(refusal(null)))
        assertNull(OwnerRefusal.reason(IllegalStateException("session busy")))
        assertNull(OwnerRefusal.reason(null))
    }

    @Test fun whatAFailedSendBecomes() {
        // Busy: always the visible queue, retried by itself, whether or not something else is typed.
        assertEquals(Outcome.RETRY_QUEUE, OwnerRefusal.outcome(OwnerRefusal.BUSY, composerEmpty = true, rewind = false))
        assertEquals(Outcome.RETRY_QUEUE, OwnerRefusal.outcome(OwnerRefusal.BUSY, composerEmpty = false, rewind = false))
        // Unconfirmed: back in the composer, or held in the queue; never retried by itself.
        assertEquals(Outcome.DRAFT, OwnerRefusal.outcome(OwnerRefusal.UNCONFIRMED, composerEmpty = true, rewind = false))
        assertEquals(Outcome.HOLD_QUEUE, OwnerRefusal.outcome(OwnerRefusal.UNCONFIRMED, composerEmpty = false, rewind = false))
        // Anything else: as before (composer when free, else the head of the queue).
        assertEquals(Outcome.DRAFT, OwnerRefusal.outcome(null, composerEmpty = true, rewind = false))
        assertEquals(Outcome.QUEUE, OwnerRefusal.outcome(null, composerEmpty = false, rewind = false))
        // An edit cannot be queued (it rewinds the chat).
        assertEquals(Outcome.DRAFT, OwnerRefusal.outcome(OwnerRefusal.BUSY, composerEmpty = true, rewind = true))
    }

    @Test fun aMessageThatMayAlreadyBeQueuedIsNeverSentByItself() {
        val held = QueuedPrompt("1", "deploy it", hold = QueuedPrompt.HOLD_UNCONFIRMED)
        val busy = QueuedPrompt("2", "and then this", hold = QueuedPrompt.HOLD_BUSY)
        val plain = QueuedPrompt("3", "later")
        assertEquals(busy, OwnerRefusal.nextToSend(listOf(held, busy, plain)))
        assertEquals(plain, OwnerRefusal.nextToSend(listOf(held, plain)))
        assertNull(OwnerRefusal.nextToSend(listOf(held)))
        assertNull(OwnerRefusal.nextToSend(emptyList()))
    }

    @Test fun theLineOverTheComposer() {
        val busy = listOf(QueuedPrompt("1", "hi", hold = QueuedPrompt.HOLD_BUSY))
        val plain = listOf(QueuedPrompt("1", "hi"))
        assertEquals(Banner.QueuedForTurn("Ezra"), OwnerRefusal.banner(busy, approvalWaiting = false, bot = "Ezra"))
        // The turn waits on an approval in this chat: say exactly that.
        assertEquals(Banner.WaitingForOk("Ezra"), OwnerRefusal.banner(busy, approvalWaiting = true, bot = "Ezra"))
        assertEquals(Banner.WaitingForOk("Ezra"), OwnerRefusal.banner(plain, approvalWaiting = true, bot = "Ezra"))
        assertEquals(Banner.QueuedForTurn(null), OwnerRefusal.banner(busy, approvalWaiting = false, bot = null))
        assertNull(OwnerRefusal.banner(plain, approvalWaiting = false, bot = "Ezra"))
        assertNull(OwnerRefusal.banner(emptyList(), approvalWaiting = true, bot = "Ezra"))   // the card itself says it
    }

    private val vm: String = listOf(
        File("src/main/java/com/nousresearch/hermesmobile/HermesViewModel.kt"),
        File("app/src/main/java/com/nousresearch/hermesmobile/HermesViewModel.kt")
    ).first { it.exists() }.readText()

    private fun body(signature: String): String {
        val start = vm.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = vm.indexOf('{', start)
        var depth = 0
        for (i in open until vm.length) {
            when (vm[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return vm.substring(open, i + 1) } }
        }
        error("unbalanced $signature")
    }

    @Test fun theViewModelRoutesEveryRefusalThroughOneRuleAndNeverResendsAnUnconfirmedOne() {
        val submit = body("private fun submitPromptNow(")
        assertTrue(submit.contains("OwnerRefusal.outcome(reason, canRestore, rewind != null)"))
        assertTrue(submit.contains("retryHeldSoon(chat.runtimeSessionId)"))
        // Refused after the chat was left: kept in that chat's queue, not dropped.
        assertTrue(submit.contains("restoreQueue(chat, listOf(QueuedPrompt("))
        assertTrue(body("private fun drainQueuedPrompt(").contains("OwnerRefusal.nextToSend(uiState.queuedPrompts)"))
        val handoff = body("private fun handOffQueue()")
        assertTrue(handoff.contains("it.hold != QueuedPrompt.HOLD_UNCONFIRMED"))
        assertTrue(handoff.contains("OwnerRefusal.reason(t)"))
        val retry = body("private fun retryHeldSoon(")
        assertTrue(retry.contains("OwnerRefusal.RETRY_MS"))
        assertTrue(retry.contains("chat.storedSessionId != stored"))
        assertTrue(retry.contains("appForeground"))
        assertFalse("the busy text is no longer an error banner", Regex("""error = t\.message\s*\)""").containsMatchIn(submit.substringAfter("val held = when")))
    }

    @Test fun gatewayApprovalsAreAskedForAndAnsweredThroughThePlugin() {
        assertTrue(body("override fun onGatewayReady(payload: JSONObject) {").contains("startGatewayApprovals()"))
        assertTrue(body("private fun reconcileChatChange()").contains("gatewayApprovalsPoke.trySend(Unit)"))
        assertTrue(body("fun answerInlineApproval(").contains("GatewayApprovals.isGateway(request)) answerGatewayApproval(request, choice)"))
        assertTrue(body("fun answerApproval(").contains("answerGatewayApproval("))
        assertTrue(body("fun cancelInteraction(").contains("answerGatewayApproval(interaction, \"deny\")"))
        assertTrue(body("private fun answerGatewayApproval(").contains("\"approval.answer\""))
        assertTrue(body("private suspend fun pollGatewayApprovals()").contains("\"approval.pending\""))
        assertTrue(body("override fun onServerRequest(request: ServerInteraction) {").contains("\"approval\" -> routeApproval(request)"))
        assertTrue(body("private fun applyGatewayApprovals(").contains("forEach(::routeApproval)"))
    }
}
