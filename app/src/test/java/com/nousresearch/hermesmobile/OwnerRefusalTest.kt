package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.GatewayNotSentException
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
 * t_cde1eeab: "The gateway is running a turn in this chat and the gateway refused it. Nothing was sent." How a refusal
 * is classified, and (Shomer t_478c28f5) that every send path of the ViewModel goes through the one outbox.
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
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, OwnerRefusal.holdFor(GatewayNotSentException("no socket"), submitted = true))
    }

    @Test fun whatWaitsForYou() {
        assertTrue(OwnerRefusal.waitsForYou(QueuedPrompt("1", "a", hold = QueuedPrompt.HOLD_UNCONFIRMED)))
        assertTrue(OwnerRefusal.waitsForYou(QueuedPrompt("1", "a", hold = QueuedPrompt.HOLD_NOT_SENT)))
        assertTrue(OwnerRefusal.waitsForYou(QueuedPrompt("1", "a", hold = QueuedPrompt.HOLD_EDIT)))
        assertFalse(OwnerRefusal.waitsForYou(QueuedPrompt("1", "a", hold = QueuedPrompt.HOLD_BUSY)))
        assertFalse(OwnerRefusal.waitsForYou(QueuedPrompt("1", "a", hold = QueuedPrompt.HOLD_SENDING)))
        assertFalse(OwnerRefusal.waitsForYou(QueuedPrompt("1", "a")))
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

    @Test fun everySendPathOfTheViewModelGoesThroughTheOneOutbox() {
        // One prompt.submit call in the whole ViewModel, inside the outbox delivery, right after the disk says submitted.
        assertEquals(1, Regex("""repo\.submitPrompt\(""").findAll(vm).count())
        val deliver = vm.substringAfter("private suspend fun deliverEntry(").substringBefore("\n    private ")
        assertTrue(deliver.contains("outbox.deliver("))
        assertTrue(Regex("""submitting\(\)\s+repo\.submitPrompt\(""").containsMatchIn(deliver))
        // Only claimed entries are delivered: the screen's send and the handoff both claim first.
        val send = body("private fun sendEntry(")
        assertTrue(send.contains("outbox.claim(entry.id) }"))
        assertTrue(send.contains("getOrNull() ?: return"))
        assertTrue(send.contains("launch(start = CoroutineStart.ATOMIC)"))
        assertTrue(send.contains("deliverEntry("))
        val handoff = body("private fun handOffQueue()")
        assertTrue(handoff.contains("outbox.handOffPlan(key)"))
        assertTrue(handoff.contains("outbox.claim(id) }.getOrNull() ?: break"))
        assertTrue(handoff.contains("deliverEntry("))
        // Every way a message is sent writes it to the outbox first.
        assertTrue(body("private fun enqueueSend(").contains("outbox.enqueue("))
        for (path in listOf("private fun sendMessage(text: String, deepConfirmed: Boolean)", "private fun postChatVoice(",
            "private fun runSlashCommand(", "private suspend fun applySlashResult(", "private fun submitVoiceTurn(")) {
            assertTrue("$path does not enqueue", body(path).contains("enqueueSend("))
        }
        // The composer is cleared only after the outbox has it (both the edit and the plain send).
        val composerSend = body("private fun sendMessage(text: String, deepConfirmed: Boolean)")
        assertFalse(composerSend.substringBefore("enqueueSend(edited, prompt, attachments").contains("chatDraft = \"\""))
        assertFalse(composerSend.substringAfter("runSlashCommand(chat, prompt)").substringBefore("enqueueSend(chat, prompt, attachments)")
            .contains("chatDraft"))
        assertTrue(composerSend.substringAfter("enqueueSend(chat, prompt, attachments) ?: return").contains("chatDraft = \"\""))
        // Drain and busy retry take only the outbox head.
        assertTrue(body("private fun drainQueuedPrompt(").contains("outbox.next(composerKey(current), idle = !current.running)"))
        val retry = body("private fun retryHeldSoon(")
        assertTrue(retry.contains("OwnerRefusal.RETRY_MS"))
        assertTrue(retry.contains("chat.storedSessionId != stored"))
        assertTrue(retry.contains("appForeground"))
        // The composer never writes the queue; its saves need the chat's own load token (Shomer t_478c28f5, Medium 1).
        assertFalse(vm.contains("uiState.queuedPrompts +"))
        assertTrue(body("private fun persistComposerSoon(").contains("composerKeeper.token(key) ?: return"))
        assertTrue(body("private fun persistCurrentComposerNow()").contains("composerKeeper.persistNow(key, composerKeeper.token(key)"))
        assertTrue(body("fun closeActiveChat()").contains("composerKeeper.beginLoad()"))
        // The lane is the outbox's, refreshed on every change of the open chat.
        assertTrue(body("private fun reconcileChatChange()").contains("refreshOutboxView()"))
        assertTrue(body("private fun refreshOutboxView()").contains("outbox::view"))
        assertTrue(body("fun removeQueuedPrompt(").contains("outbox.discard(id)"))
        assertTrue(body("fun retryQueuedPrompt(").contains("outbox.retry(id)"))
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
