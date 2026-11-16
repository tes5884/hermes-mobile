package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ChatComposerStore
import com.nousresearch.hermesmobile.data.GatewayRpcException
import com.nousresearch.hermesmobile.data.OwnerRefusal
import com.nousresearch.hermesmobile.data.OwnerRefusal.Banner
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

    @Test fun whatAFailedSendWaitsAs() {
        // Busy: nothing was sent, retried by itself.
        assertEquals(QueuedPrompt.HOLD_BUSY, OwnerRefusal.holdFor(refusal(OwnerRefusal.BUSY), submitted = true))
        // The owner did not confirm, or no answer came back at all: it may be on the gateway.
        assertEquals(QueuedPrompt.HOLD_UNCONFIRMED, OwnerRefusal.holdFor(refusal(OwnerRefusal.UNCONFIRMED), submitted = true))
        assertEquals(QueuedPrompt.HOLD_UNCONFIRMED, OwnerRefusal.holdFor(IllegalStateException("Reconnecting to Hermes…"), submitted = true))
        // The gateway refused it (SESSION_NOT_OWNED, anything else it answered with), or prompt.submit never went out.
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, OwnerRefusal.holdFor(refusal("SESSION_NOT_OWNED"), submitted = true))
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, OwnerRefusal.holdFor(GatewayRpcException("model needs confirmation"), submitted = true))
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, OwnerRefusal.holdFor(IllegalStateException("upload failed"), submitted = false))
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, OwnerRefusal.holdFor(refusal(OwnerRefusal.UNCONFIRMED), submitted = false))
    }

    private val empty = ChatComposerStore.State()

    @Test fun aRefusedSendIsKeptInItsChatWhateverTheRefusal() {
        for (hold in QueuedPrompt.HOLDS) {
            // Composer free, nothing queued: back in the composer (busy waits in the queue, retried by itself).
            val kept = OwnerRefusal.keep(empty, "r", "run checks first", emptyList(), hold, edit = false)
            if (hold == QueuedPrompt.HOLD_BUSY) {
                assertEquals(listOf("run checks first"), kept.queue.map { it.text })
                assertEquals(QueuedPrompt.HOLD_BUSY, kept.queue.single().hold)
            } else {
                assertEquals("run checks first", kept.draft)
                assertTrue(kept.queue.isEmpty())
            }
            // Something typed: never dropped, at the head of the queue with its hold.
            val typed = OwnerRefusal.keep(empty.copy(draft = "half a thought"), "r", "run checks first", emptyList(), hold, edit = false)
            assertEquals("half a thought", typed.draft)
            assertEquals(QueuedPrompt("r", "run checks first", hold = hold, createdAt = typed.queue.single().createdAt), typed.queue.single())
        }
    }

    @Test fun aRefusedSendStaysAheadOfWhatWasQueuedAfterIt() {
        val later = QueuedPrompt("2", "deploy now")
        val kept = OwnerRefusal.keep(empty.copy(queue = listOf(later)), "1", "run checks first", emptyList(),
            QueuedPrompt.HOLD_UNCONFIRMED, edit = false)
        // Not the composer (the queue would overtake it): the head of the queue, and it fences what follows.
        assertEquals("", kept.draft)
        assertEquals(listOf("run checks first", "deploy now"), kept.queue.map { it.text })
        assertNull(OwnerRefusal.nextToSend(kept.queue))
    }

    @Test fun aRefusedEditIsKeptAndNeverSentByItself() {
        // Composer free: back in it, as before.
        assertEquals("fixed wording", OwnerRefusal.keep(empty, "e", "fixed wording", emptyList(), QueuedPrompt.HOLD_NOT_SENT, edit = true).draft)
        // Composer busy: was dropped; now held in the queue, never re-sent by itself (it would not rewind), even if busy.
        for (hold in listOf(QueuedPrompt.HOLD_NOT_SENT, QueuedPrompt.HOLD_BUSY)) {
            val kept = OwnerRefusal.keep(empty.copy(draft = "x"), "e", "fixed wording", emptyList(), hold, edit = true)
            assertEquals(QueuedPrompt.HOLD_EDIT, kept.queue.single().hold)
            assertNull(OwnerRefusal.nextToSend(kept.queue))
        }
        // An edit that may have been applied says so.
        assertEquals(QueuedPrompt.HOLD_UNCONFIRMED,
            OwnerRefusal.keep(empty.copy(draft = "x"), "e", "w", emptyList(), QueuedPrompt.HOLD_UNCONFIRMED, edit = true).queue.single().hold)
    }

    @Test fun aHeldHeadFencesDrainAndHandoffUntilYouResolveIt() {
        val checks = QueuedPrompt("1", "run checks first", hold = QueuedPrompt.HOLD_UNCONFIRMED)
        val deploy = QueuedPrompt("2", "deploy now")
        val busy = QueuedPrompt("3", "and tell me", hold = QueuedPrompt.HOLD_BUSY)
        val queue = listOf(checks, deploy, busy)
        // Drain (turn end, busy retry) sends nothing past it.
        assertNull(OwnerRefusal.nextToSend(queue))
        // Leaving the chat hands nothing past it to the gateway; all of it stays, in order.
        assertEquals(emptyList<QueuedPrompt>() to queue, OwnerRefusal.handOffPlan(queue))
        // What was queued before it still goes, in order; it and what follows stay.
        val first = QueuedPrompt("0", "status?")
        assertEquals(listOf(first) to queue, OwnerRefusal.handOffPlan(listOf(first) + queue))
        assertEquals(first, OwnerRefusal.nextToSend(listOf(first) + queue))
        // Each held kind fences; busy alone does not (it is the one that goes again).
        for (hold in listOf(QueuedPrompt.HOLD_UNCONFIRMED, QueuedPrompt.HOLD_NOT_SENT, QueuedPrompt.HOLD_EDIT)) {
            assertNull(OwnerRefusal.nextToSend(listOf(checks.copy(hold = hold), deploy)))
        }
        assertEquals(busy, OwnerRefusal.nextToSend(listOf(busy, deploy)))
        // Removed: what waited goes, in order.
        assertEquals(deploy, OwnerRefusal.nextToSend(queue - checks))
        // Edited and saved (the hold released): it goes first, then what waited.
        assertEquals("1", OwnerRefusal.nextToSend(listOf(checks.copy(hold = null), deploy, busy))?.id)
    }

    @Test fun aRefusedHandoffGoesBackInOrderWithItsHead() {
        val a = QueuedPrompt("a", "one")
        val b = QueuedPrompt("b", "two")
        // Refused as not owned at "one": both back, "one" held, "two" behind it, neither sent by itself.
        val back = OwnerRefusal.afterHandOffFailure(listOf(a, b), refusal("SESSION_NOT_OWNED"), submitted = true)
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, back.first().hold)
        assertNull(back[1].hold)
        assertNull(OwnerRefusal.nextToSend(back))
        // Busy: back, retried by itself, in order.
        val busy = OwnerRefusal.afterHandOffFailure(listOf(a, b), refusal(OwnerRefusal.BUSY), submitted = true)
        assertEquals("a", OwnerRefusal.nextToSend(busy)?.id)
        // The connection dropped mid-send: may be on the gateway.
        assertEquals(QueuedPrompt.HOLD_UNCONFIRMED,
            OwnerRefusal.afterHandOffFailure(listOf(a), IllegalStateException("WebSocket closed"), submitted = true).single().hold)
    }

    @Test fun aMessageThatMayAlreadyBeQueuedIsNeverSentByItself() {
        val held = QueuedPrompt("1", "deploy it", hold = QueuedPrompt.HOLD_UNCONFIRMED)
        val busy = QueuedPrompt("2", "and then this", hold = QueuedPrompt.HOLD_BUSY)
        val plain = QueuedPrompt("3", "later")
        // Shomer t_8d113995: nothing behind it overtakes it either.
        assertNull(OwnerRefusal.nextToSend(listOf(held, busy, plain)))
        assertNull(OwnerRefusal.nextToSend(listOf(held, plain)))
        assertNull(OwnerRefusal.nextToSend(listOf(held)))
        assertNull(OwnerRefusal.nextToSend(emptyList()))
        assertEquals(busy, OwnerRefusal.nextToSend(listOf(busy, held, plain)))
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

    @Test fun theViewModelRoutesEveryRefusalThroughOneRuleAndNeverResendsAHeldOne() {
        val submit = body("private fun submitPromptNow(")
        assertTrue(submit.contains("OwnerRefusal.holdFor(t, submitted)"))
        assertTrue(submit.contains("OwnerRefusal.keep("))
        assertTrue(submit.contains("keepRefused(composer, chat.storedSessionId)"))
        assertTrue(submit.contains("retryHeldSoon(it.runtimeSessionId)"))
        // Kept whether or not the chat is still open: the keep is outside the "still on screen" branch.
        assertFalse(Regex("""else if \(rewind == null\)""").containsMatchIn(submit))
        val drain = body("private fun drainQueuedPrompt(")
        assertTrue(drain.contains("OwnerRefusal.nextToSend(uiState.queuedPrompts)"))
        assertTrue(drain.contains("composerKeeper.isLoaded(current.storedSessionId)"))
        val handoff = body("private fun handOffQueue()")
        assertTrue(handoff.contains("OwnerRefusal.handOffPlan(uiState.queuedPrompts)"))
        assertTrue(handoff.contains("OwnerRefusal.afterHandOffFailure("))
        assertTrue(handoff.contains("keepRefused(composer, chat.storedSessionId)"))
        assertTrue(body("private fun loadChatEnhancements(").contains("composerKeeper.load(key, chat.storedSessionId)"))
        assertTrue(body("private suspend fun keepRefused(").contains("composerKeeper.keep("))
        val retry = body("private fun retryHeldSoon(")
        assertTrue(retry.contains("OwnerRefusal.RETRY_MS"))
        assertTrue(retry.contains("chat.storedSessionId != stored"))
        assertTrue(retry.contains("appForeground"))
        assertTrue(body("fun removeQueuedPrompt(").contains("drainIdleChatSoon()"))
        assertTrue(body("fun updateQueuedPrompt(").contains("OwnerRefusal.fences(it)"))
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
