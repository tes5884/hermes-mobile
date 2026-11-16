package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ChatComposerStore
import com.nousresearch.hermesmobile.data.ChatMessage
import com.nousresearch.hermesmobile.data.ComposerAttachment
import com.nousresearch.hermesmobile.data.ComposerKeeper
import com.nousresearch.hermesmobile.data.ComposerSaves
import com.nousresearch.hermesmobile.data.GatewayNotSentException
import com.nousresearch.hermesmobile.data.GatewayRpcException
import com.nousresearch.hermesmobile.data.Outbox
import com.nousresearch.hermesmobile.data.OutboxChange
import com.nousresearch.hermesmobile.data.OutboxEdit
import com.nousresearch.hermesmobile.data.OutboxEntry
import com.nousresearch.hermesmobile.data.OutboxReceipt
import com.nousresearch.hermesmobile.data.OutboxStorage
import com.nousresearch.hermesmobile.data.OwnerRefusal
import com.nousresearch.hermesmobile.data.QueuedPrompt
import com.nousresearch.hermesmobile.data.deliver
import com.nousresearch.hermesmobile.data.outboxLane
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * The outbox (Tzvi 2026-11-15, Shomer t_478c28f5): every send is on disk before prompt.submit, only the gateway's
 * receipt removes it, and nothing else (closing/reopening the chat, a cancelled send, a connection switch, the process
 * dying) can delete or skip it. Run on the real [Outbox] and [deliver] with the disk in memory.
 */
class OutboxTest {
    /** The disk: what a new process would read back. [full] makes every write fail, writing nothing (as commit() does). */
    private class Disk : OutboxStorage {
        val rows = ConcurrentHashMap<String, String>()
        @Volatile var full = false
        override fun readAll(): Map<String, String> = HashMap(rows)
        override fun writeAll(changes: Map<String, String?>) {
            check(!full) { "Could not save the outbox" }
            changes.forEach { (id, value) -> if (value == null) rows.remove(id) else rows[id] = value }
        }
        fun entry(id: String): OutboxEntry? = rows[id]?.let(Outbox::decode)
    }

    private val disk = Disk()
    private val box = Outbox(disk)
    private val ezra = "gw:ezra:ezra-chat"
    private val nova = "gw:nova:nova-chat"

    private fun send(text: String, chat: String = ezra, files: List<ComposerAttachment> = emptyList(), edit: OutboxEdit? = null, direct: Boolean = false) =
        box.enqueue(chat, chat.substringAfterLast(':'), chat.split(':')[1], text, files, direct = direct, edit = edit)

    private fun refusal(reason: String?) = GatewayRpcException("refused", 4090, reason?.let { JSONObject().put("reason", it) })

    private val receipt = { r: String -> OutboxReceipt("queued", r) }
    private val file = ComposerAttachment("f", "file:///cache/hermes-voice-1.m4a", "voice-note-3s.m4a", "audio/mp4", 10)

    @Test fun everySendIsOnDiskBeforePromptSubmitAndOnlyTheReceiptRemovesIt() = runBlocking {
        val entry = send("deploy now")
        // Written before anything sends it.
        assertEquals(Outbox.QUEUED, disk.entry(entry.id)?.state)
        assertNotNull(box.claim(entry.id))
        var seenAtSubmit: OutboxEntry? = null
        val result = box.deliver(entry.id, receipt) { submitting ->
            assertFalse("not marked before the upload is done", disk.entry(entry.id)!!.submitted)
            submitting()
            // What prompt.submit would go out with on the wire: the disk already says it may be on the gateway.
            seenAtSubmit = disk.entry(entry.id)
            "adm-1"
        }
        assertTrue(result.isSuccess)
        assertEquals(Outbox.SENDING, seenAtSubmit?.state)
        assertTrue(seenAtSubmit!!.submitted)
        // The gateway's receipt is the one thing that removes it.
        assertNull(disk.entry(entry.id))
        assertTrue(box.entries(ezra).isEmpty())
    }

    @Test fun rapidReopenAndCloseMidSubmitNeverDropsIt() = runBlocking {
        // The chat's draft lives in the composer store; the sent message lives only in the outbox.
        val saves = object : ComposerSaves {
            val rows = ConcurrentHashMap<String, ChatComposerStore.State>()
            override fun load(key: String) = rows[key] ?: ChatComposerStore.State()
            override fun save(key: String, state: ChatComposerStore.State) { rows[key] = state }
            override fun dropLegacyQueue(key: String) = Unit
        }
        val keeper = ComposerKeeper(saves, Dispatchers.Unconfined)
        saves.rows[ezra] = ChatComposerStore.State(draft = "half-typed follow-up")
        val entry = send("run the checks first")
        box.claim(entry.id)
        val gate = CompletableDeferred<Unit>()
        val sending = launch(Dispatchers.Default) {
            box.deliver(entry.id, receipt) { submitting -> submitting(); gate.await(); throw refusal("SESSION_NOT_OWNED") }
        }
        // Leave for Nova, come back to Ezra and close it again before its draft has loaded: the screen still shows
        // Nova's composer, and the close tries to save it as Ezra's.
        keeper.beginLoad()
        keeper.load(nova, "nova-chat") { true }
        keeper.beginLoad()   // Ezra opening
        assertFalse(keeper.persistNow(ezra, keeper.token(ezra), ChatComposerStore.State(draft = "for nova")))
        keeper.beginLoad()   // closed
        // SESSION_NOT_OWNED comes back late, after all of that.
        gate.complete(Unit)
        sending.join()
        assertEquals("half-typed follow-up", saves.rows[ezra]?.draft)
        val kept = Outbox(disk).entries(ezra).single()
        assertEquals(Outbox.NOT_SENT, kept.state)
        assertEquals("run the checks first", kept.text)
        // The chat shows it when it opens (drawn from the outbox, not the composer).
        assertEquals(listOf(QueuedPrompt.HOLD_NOT_SENT), box.view(ezra).map { it.hold })
    }

    @Test fun aConnectionSwitchCancellingTheSendMidSubmitLeavesItNotSureItSent() = runBlocking {
        val entry = send("restart the gateway")
        val later = send("then check the logs")
        box.claim(entry.id)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val submitted = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            box.deliver(entry.id, receipt) { submitting -> submitting(); submitted.complete(Unit); awaitCancellation() }
        }
        submitted.await()
        // disconnect(): viewModelScope.coroutineContext.cancelChildren()
        job.cancelAndJoin()
        assertEquals(Outbox.UNSURE, disk.entry(entry.id)?.state)
        assertEquals(Outbox.QUEUED, disk.entry(later.id)?.state)
        // Never sent again by itself, and the message after it does not overtake it.
        assertNull(box.next(ezra, idle = true))
        assertTrue(box.handOffPlan(ezra).isEmpty())
        assertNull(box.claim(later.id))
    }

    @Test fun cancelledBeforePromptSubmitWentOut() = runBlocking {
        // During the upload: text alone goes back to waiting (nothing reached the gateway); files are held for you,
        // since an upload may already sit staged on the session.
        for ((files, expected) in listOf(emptyList<ComposerAttachment>() to Outbox.QUEUED, listOf(file) to Outbox.NOT_SENT)) {
            disk.rows.clear()
            val b = Outbox(disk)
            val e = b.enqueue(ezra, "ezra-chat", "ezra", "voice note", files)
            b.claim(e.id)
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) { b.deliver(e.id, receipt) { started.complete(Unit); awaitCancellation() } }
            started.await()
            job.cancelAndJoin()
            assertEquals(expected, disk.entry(e.id)?.state)
        }
    }

    @Test fun aSendWhoseScopeWasAlreadyCancelledStillSettlesItsClaim() = runBlocking {
        val entry = send("hello")
        val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Default)
        box.claim(entry.id)
        // ATOMIC (as sendEntry launches it): the body runs up to its first suspension even in a dead scope.
        scope.launch(start = CoroutineStart.ATOMIC) {
            box.deliver(entry.id, receipt) { submitting -> kotlinx.coroutines.yield(); submitting(); "never" }
        }.join()
        assertEquals(Outbox.QUEUED, disk.entry(entry.id)?.state)
        assertEquals(entry.id, box.next(ezra, idle = true)?.id)
    }

    @Test fun theProcessKilledMidSubmit() {
        val submittedOne = send("submitted")
        val uploading = send("uploading", chat = nova)
        val withFile = send("file", chat = "gw:lexi:lexi-chat", files = listOf(file))
        listOf(submittedOne, uploading, withFile).forEach { box.claim(it.id) }
        box.markSubmitted(submittedOne.id)
        // The process dies here. A new one reads the disk: nothing it finds goes out by itself.
        val after = Outbox(disk)
        assertEquals(Outbox.UNSURE, after.get(submittedOne.id)?.state)
        assertEquals(Outbox.NOT_SENT, after.get(uploading.id)?.state)
        assertEquals(Outbox.NOT_SENT, after.get(withFile.id)?.state)
        // Whatever the disk still says, every later start reads it the same way.
        assertEquals(Outbox.UNSURE, Outbox(disk).get(submittedOne.id)?.state)
        assertEquals(Outbox.NOT_SENT, Outbox(disk).get(uploading.id)?.state)
        assertNull(after.next(ezra, idle = true))
        assertNull(after.next(nova, idle = true))
    }

    @Test fun aLateSessionNotOwnedIsKeptAsNotSent() = runBlocking {
        val entry = send("ship it")
        box.claim(entry.id)
        val result = box.deliver(entry.id, receipt) { submitting -> submitting(); throw refusal("SESSION_NOT_OWNED") }
        assertTrue(result.isFailure)
        assertEquals(Outbox.NOT_SENT, Outbox(disk).get(entry.id)?.state)
        assertNull(box.next(ezra, idle = true))
        // Send again: it waits its turn as new, at its place.
        assertEquals(OutboxChange.DONE, box.retry(entry.id))
        assertEquals(entry.id, box.next(ezra, idle = true)?.id)
    }

    @Test fun anUnconfirmedHeadFencesEveryLaterEntry() = runBlocking {
        val head = send("migrate the db")
        val second = send("then deploy")
        val call = send("spoken turn", direct = true)
        box.claim(head.id)
        box.deliver(head.id, receipt) { submitting -> submitting(); throw refusal(OwnerRefusal.UNCONFIRMED) }
        assertEquals(Outbox.UNSURE, box.get(head.id)?.state)
        // No drain, no busy retry, no handoff, no call turn goes past it.
        assertNull(box.next(ezra, idle = true))
        assertNull(box.next(ezra, idle = false))
        assertTrue(box.handOffPlan(ezra).isEmpty())
        assertNull(box.claim(second.id))
        assertNull(box.claim(call.id))
        assertEquals(listOf(QueuedPrompt.HOLD_UNCONFIRMED, null, null), box.view(ezra).map { it.hold })
        // Only you resolve it: removing it lets the next one go.
        assertEquals(OutboxChange.DONE, box.discard(head.id))
        assertEquals(second.id, box.next(ezra, idle = true)?.id)
    }

    @Test fun aRefusedEditIsKeptAndNeverSentAgainAsARewind() = runBlocking {
        for (reason in listOf("SESSION_NOT_OWNED", OwnerRefusal.BUSY)) {
            val edit = send("fixed wording", edit = OutboxEdit("row:7", 7L, 2, 1, confirmDeep = true))
            box.claim(edit.id)
            box.deliver(edit.id, receipt) { submitting -> submitting(); throw refusal(reason) }
            val kept = Outbox(disk).get(edit.id)!!
            assertEquals(Outbox.NOT_SENT, kept.state)
            assertTrue(kept.editFailed)
            assertNull(kept.edit)
            assertEquals(QueuedPrompt.HOLD_EDIT, box.view(ezra).single { it.id == edit.id }.hold)
            assertNull(box.next(ezra, idle = true))
            box.discard(edit.id)
        }
    }

    @Test fun busyWaitsAndGoesAgainByItself() = runBlocking {
        val entry = send("note this")
        box.claim(entry.id)
        box.deliver(entry.id, receipt) { submitting -> submitting(); throw refusal(OwnerRefusal.BUSY) }
        assertEquals(Outbox.BUSY, disk.entry(entry.id)?.state)
        assertEquals(entry.id, box.next(ezra, idle = true)?.id)
        assertEquals(QueuedPrompt.HOLD_BUSY, box.view(ezra).single().hold)
    }

    @Test fun aTimeoutIsNotSureItSentAndNothingThatNeverLeftIsNotSent() = runBlocking {
        val timedOut = send("a")
        box.claim(timedOut.id)
        val result = box.deliver(timedOut.id, receipt) { submitting -> submitting(); withTimeout(10) { awaitCancellation() } }
        assertTrue(result.isFailure)
        assertEquals(Outbox.UNSURE, box.get(timedOut.id)?.state)
        box.discard(timedOut.id)
        val noSocket = send("b")
        box.claim(noSocket.id)
        box.deliver(noSocket.id, receipt) { submitting -> submitting(); throw GatewayNotSentException("Gateway WebSocket is not connected") }
        assertEquals(Outbox.NOT_SENT, box.get(noSocket.id)?.state)
    }

    @Test fun exactlyOneSenderAndOnlyTheHead() {
        val a = send("a")
        val b = send("b")
        val other = send("c", chat = nova)
        assertNull(box.claim(b.id))
        assertNotNull(box.claim(a.id))
        // A reopened chat (drain) and a handoff both try: the second never gets it.
        assertNull(box.claim(a.id))
        assertNull(box.next(ezra, idle = true))
        assertNotNull(box.claim(other.id))
        // A message going out cannot be removed, moved or edited out from under its sender.
        assertEquals(OutboxChange.NOT_ALLOWED, box.discard(a.id))
        assertEquals(OutboxChange.NOT_ALLOWED, box.update(a.id, "x"))
        box.discardAll(ezra)
        assertEquals(listOf(a.id), box.entries(ezra).map { it.id })
    }

    @Test fun orderMovesAndAChatWhoseSessionRotated() {
        val a = send("a")
        val b = send("b")
        val c = send("c")
        assertEquals(OutboxChange.DONE, box.move(c.id, -2))
        assertEquals(listOf(c.id, a.id, b.id), Outbox(disk).entries(ezra).map { it.id })
        box.rekey(ezra, "gw:ezra:compressed", "compressed")
        assertTrue(box.entries(ezra).isEmpty())
        assertEquals(listOf(c.id, a.id, b.id), box.entries("gw:ezra:compressed").map { it.id })
        assertEquals("compressed", box.get(a.id)?.storedSessionId)
    }

    @Test fun aQueueAnOlderVersionSavedMovesInOnceInOrder() = runBlocking {
        val legacy = listOf(
            QueuedPrompt("q1", "first", hold = QueuedPrompt.HOLD_UNCONFIRMED),
            QueuedPrompt("q2", "second"),
            QueuedPrompt("q3", "edit", hold = QueuedPrompt.HOLD_EDIT)
        )
        assertTrue(box.importLegacy(ezra, "ezra-chat", "ezra", legacy))
        // Moved in, none of them goes out by itself: not sent until you tap (unsure stays unsure).
        assertEquals(listOf(Outbox.UNSURE, Outbox.NOT_SENT, Outbox.NOT_SENT), box.entries(ezra).map { it.state })
        assertNull(box.next(ezra, idle = true))
        // Resolve and send q1 and q2; the old composer still holds them (its rewrite did not reach the disk).
        box.discard("q1")
        assertEquals(OutboxChange.DONE, box.retry("q2"))
        box.claim("q2")
        box.deliver("q2", receipt) { submitting -> submitting(); "adm" }
        assertTrue(Outbox(disk).importLegacy(ezra, "ezra-chat", "ezra", legacy))
        assertEquals(listOf("q3"), Outbox(disk).entries(ezra).map { it.id })
    }

    @Test fun theLaneHidesOnlyAMessageWhoseBubbleIsOnScreen() {
        val sending = QueuedPrompt("s", "hi", hold = QueuedPrompt.HOLD_SENDING)
        val waiting = QueuedPrompt("w", "next")
        val bubble = ChatMessage(role = "user", content = "hi", live = true, outboxId = "s")
        assertEquals(listOf(waiting), outboxLane(listOf(sending, waiting), listOf(bubble)))
        // Reopened: no bubble yet, so it shows as "Sending…".
        assertEquals(listOf(sending, waiting), outboxLane(listOf(sending, waiting), emptyList()))
    }

    @Test fun aDiskThatRefusesNeverLetsAMessageGoOutUnrecorded() = runBlocking {
        val flaky = Disk()
        val b = Outbox(flaky)
        // Not written: not accepted (the composer keeps the text), and nothing half-made stays behind.
        flaky.full = true
        assertTrue(runCatching { b.enqueue(ezra, "ezra-chat", "ezra", "x") }.isFailure)
        assertTrue(b.entries(ezra).isEmpty())
        flaky.full = false
        val e = b.enqueue(ezra, "ezra-chat", "ezra", "deploy")
        b.claim(e.id)
        // The disk fills before "submitted" can be written: prompt.submit must never go out.
        flaky.full = true
        var wentOut = false
        val result = b.deliver(e.id, receipt) { submitting -> submitting(); wentOut = true; "adm" }
        assertTrue(result.isFailure)
        assertFalse(wentOut)
        assertEquals(Outbox.NOT_SENT, b.get(e.id)?.state)
        // A user change the disk refuses changes nothing and says so.
        assertEquals(OutboxChange.NOT_SAVED, b.discard(e.id))
        assertNotNull(b.get(e.id))
        // On disk it is still "sending, not submitted": the next start shows it as not sent (never sends it by itself).
        assertEquals(Outbox.NOT_SENT, Outbox(flaky).get(e.id)?.state)
    }

    @Test fun theDiskFormatRoundTrips() {
        val e = send("with all", files = listOf(file), edit = OutboxEdit("row:3", null, 1, 0, false), direct = true)
        assertEquals(e, Outbox.decode(Outbox.encode(e)))
        assertNull(Outbox.decode("not json"))
    }
}
