package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ChatComposerStore
import com.nousresearch.hermesmobile.data.ComposerKeeper
import com.nousresearch.hermesmobile.data.ComposerRows
import com.nousresearch.hermesmobile.data.GatewayRpcException
import com.nousresearch.hermesmobile.data.LegacyMove
import com.nousresearch.hermesmobile.data.Outbox
import com.nousresearch.hermesmobile.data.OutboxChange
import com.nousresearch.hermesmobile.data.OutboxEdit
import com.nousresearch.hermesmobile.data.OutboxEntry
import com.nousresearch.hermesmobile.data.OutboxReceipt
import com.nousresearch.hermesmobile.data.OutboxStorage
import com.nousresearch.hermesmobile.data.QueuedPrompt
import com.nousresearch.hermesmobile.data.deliver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A failed storage write stops and says so (Tzvi 2026-11-15, Shomer t_737920a6), and nothing found in the outbox at
 * start ever goes out by itself. Real [Outbox], real [ChatComposerStore] and [ComposerKeeper], with disks in memory that
 * can be made to refuse (all or nothing, as one SharedPreferences commit()).
 */
class OutboxFailedWriteTest {
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

    /** The composer file, in clear text. */
    private class Rows : ComposerRows {
        val rows = ConcurrentHashMap<String, String>()
        @Volatile var full = false
        override fun read(key: String): String? = rows[key]
        override fun write(key: String, row: String?) {
            check(!full) { "Could not save the draft" }
            if (row == null) rows.remove(key) else rows[key] = row
        }
        override fun keys(): Set<String> = rows.keys.toSet()
    }

    private val ezra = "gw:ezra:ezra-chat"
    private val disk = Disk()
    private val box = Outbox(disk)
    private val receipt = { r: String -> OutboxReceipt("queued", r) }

    private fun send(b: Outbox, text: String) = b.enqueue(ezra, "ezra-chat", "ezra", text)

    /** A composer as an older version saved it: a draft, and a queue with a refused message in it. */
    private fun legacyComposer(rows: Rows) {
        rows.rows[ezra] = JSONObject()
            .put("draft", "half-typed")
            .put("attachments", org.json.JSONArray())
            .put("queue", org.json.JSONArray()
                .put(JSONObject().put("id", "q1").put("text", "restart the gateway").put("hold", QueuedPrompt.HOLD_NOT_SENT))
                .put(JSONObject().put("id", "q2").put("text", "then check the logs")))
            .toString()
    }

    // Shomer t_737920a6, Medium 1: migration.
    @Test fun aMigrationTheOutboxCannotWriteKeepsTheOldQueueAndSaysSo() = runBlocking {
        val rows = Rows()
        legacyComposer(rows)
        val store = ChatComposerStore(rows)
        val keeper = ComposerKeeper(store, Dispatchers.Unconfined)
        keeper.beginLoad()
        assertNotNull(keeper.load(ezra, "ezra-chat") { true })
        // The outbox write fails while the composer could still be written.
        disk.full = true
        val moved = keeper.migrateLegacy(ezra) { queue -> box.importLegacy(ezra, "ezra-chat", "ezra", queue) }
        assertEquals(LegacyMove.KEPT, moved)
        assertTrue(box.entries(ezra).isEmpty())
        // No entry and no marker reached the disk (all or none).
        assertTrue(disk.rows.isEmpty())
        // Typing, closing, clearing the draft: every draft save carries the old queue over.
        assertTrue(keeper.persistNow(ezra, keeper.token(ezra), ChatComposerStore.State(draft = "new words")))
        assertTrue(keeper.persistNow(ezra, keeper.token(ezra), ChatComposerStore.State()))
        // The process restarts: the refused message is still in the old composer, in order.
        assertEquals(listOf("q1", "q2"), ChatComposerStore(rows).load(ezra).queue.map { it.id })
        assertTrue(Outbox(disk).entries(ezra).isEmpty())
        // The disk recovers; the next open moves it, and only then does the composer let go of it.
        disk.full = false
        val again = keeper.migrateLegacy(ezra) { queue -> Outbox(disk).importLegacy(ezra, "ezra-chat", "ezra", queue) }
        assertEquals(LegacyMove.MOVED, again)
        assertTrue(ChatComposerStore(rows).load(ezra).queue.isEmpty())
        val after = Outbox(disk)
        assertEquals(listOf("q1", "q2"), after.entries(ezra).map { it.id })
        // Moved in, it waits for Tzvi: not sent, never sent by itself.
        assertEquals(listOf(Outbox.NOT_SENT, Outbox.NOT_SENT), after.entries(ezra).map { it.state })
        assertNull(after.next(ezra, idle = true))
    }

    @Test fun aComposerThatCannotLetGoOfTheMovedQueueNeverDoublesIt() = runBlocking {
        val rows = Rows()
        legacyComposer(rows)
        val keeper = ComposerKeeper(ChatComposerStore(rows), Dispatchers.Unconfined)
        rows.full = true
        assertEquals(LegacyMove.MOVED_COPY_LEFT, keeper.migrateLegacy(ezra) { q -> box.importLegacy(ezra, "ezra-chat", "ezra", q) })
        assertEquals(2, box.entries(ezra).size)
        // Send q1 again, it goes; the old composer still has it. Restart and reopen: it does not come back.
        box.discard("q2")
        box.retry("q1"); box.claim("q1"); box.deliver("q1", receipt) { s -> s(); "adm" }
        rows.full = false
        val reopened = Outbox(disk)
        assertEquals(LegacyMove.MOVED, keeper.migrateLegacy(ezra) { q -> reopened.importLegacy(ezra, "ezra-chat", "ezra", q) })
        assertTrue(reopened.entries(ezra).isEmpty())
        assertTrue(ChatComposerStore(rows).load(ezra).queue.isEmpty())
        assertEquals("half-typed", ChatComposerStore(rows).load(ezra).draft)
    }

    // Shomer t_737920a6, Medium 2: Clear.
    @Test fun aClearTheDiskRefusesRemovesNothingAndNothingComesBackToSendByItself() {
        val a = send(box, "a"); val b = send(box, "b"); val c = send(box, "c")
        disk.full = true
        assertEquals(OutboxChange.NOT_SAVED, box.discardAll(ezra))
        // Still on screen, all of them, as they were.
        assertEquals(listOf(a.id, b.id, c.id), box.view(ezra).map { it.id })
        // After a restart they are there too, and none goes out by itself.
        val restarted = Outbox(disk)
        assertEquals(listOf(a.id, b.id, c.id), restarted.entries(ezra).map { it.id })
        assertTrue(restarted.entries(ezra).all { it.state == Outbox.NOT_SENT })
        assertNull(restarted.next(ezra, idle = true))
        assertTrue(restarted.handOffPlan(ezra).isEmpty())
        // The disk recovers: Clear works, and a restart finds nothing.
        disk.full = false
        assertEquals(OutboxChange.DONE, restarted.discardAll(ezra))
        assertTrue(Outbox(disk).entries(ezra).isEmpty())
    }

    @Test fun removeEditSendAgainAndMoveTheDiskRefusesChangeNothing() {
        val a = send(box, "a"); val b = send(box, "b")
        box.claim(a.id); box.refused(a.id, QueuedPrompt.HOLD_NOT_SENT)
        val before = box.entries(ezra)
        val onDisk = HashMap(disk.rows)
        disk.full = true
        assertEquals(OutboxChange.NOT_SAVED, box.discard(b.id))
        assertEquals(OutboxChange.NOT_SAVED, box.update(a.id, "changed"))
        assertEquals(OutboxChange.NOT_SAVED, box.retry(a.id))
        assertEquals(OutboxChange.NOT_SAVED, box.move(b.id, -1))
        assertEquals(before, box.entries(ezra))
        assertEquals(onDisk, HashMap(disk.rows))
        // The held head still holds: nothing went out.
        assertNull(box.next(ezra, idle = true))
    }

    // Nothing found at start ever sends by itself.
    @Test fun nothingFoundAtStartGoesOutByItself() {
        val chats = listOf("queued", "busy", "sending", "submitted", "edit", "direct").map { "gw:p:$it" }
        val e = chats.associateWith { box.enqueue(it, it.substringAfterLast(':'), "p", it, direct = it.endsWith("direct"),
            edit = if (it.endsWith("edit")) OutboxEdit("row:1", 1L, 0, 0, false) else null) }
        box.claim(e.getValue("gw:p:busy").id); box.refused(e.getValue("gw:p:busy").id, QueuedPrompt.HOLD_BUSY)
        box.claim(e.getValue("gw:p:sending").id)
        box.claim(e.getValue("gw:p:submitted").id); box.markSubmitted(e.getValue("gw:p:submitted").id)
        assertEquals(Outbox.BUSY, disk.entry(e.getValue("gw:p:busy").id)?.state)
        val started = Outbox(disk)
        for (chat in chats) {
            assertNull(chat, started.next(chat, idle = true))
            assertNull(chat, started.next(chat, idle = false))
            assertTrue(chat, started.handOffPlan(chat).isEmpty())
            assertNull(chat, started.claim(e.getValue(chat).id))
        }
        assertEquals(Outbox.UNSURE, started.get(e.getValue("gw:p:submitted").id)?.state)
        assertEquals(QueuedPrompt.HOLD_EDIT, started.view("gw:p:edit").single().hold)
        assertNull(started.get(e.getValue("gw:p:edit").id)?.edit)
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, started.view("gw:p:queued").single().hold)
        // Tzvi taps Send again: then (and only then) it may go.
        assertEquals(OutboxChange.DONE, started.retry(e.getValue("gw:p:queued").id))
        assertEquals(e.getValue("gw:p:queued").id, started.next("gw:p:queued", idle = true)?.id)
    }

    // Shomer t_737920a6, Low: a claim the disk refused leaves no stale hold.
    @Test fun aClaimTheDiskRefusedDoesNotStallTheChat() {
        val a = send(box, "a")
        disk.full = true
        assertTrue(runCatching { box.claim(a.id) }.isFailure)
        assertEquals(Outbox.QUEUED, box.get(a.id)?.state)
        disk.full = false
        assertNotNull(box.claim(a.id))
    }

    // After-send outcomes: recorded in memory (the safer side), said, and written once the disk takes writes again.
    @Test fun anOutcomeTheDiskRefusesIsSaidKeptAndWrittenLater() = runBlocking {
        val said = mutableListOf<Throwable>()
        box.onWriteFailed = { said += it }
        val a = send(box, "a")
        box.claim(a.id)
        val result = box.deliver(a.id, receipt) { s -> s(); disk.full = true; throw GatewayRpcException("refused", 4090, JSONObject().put("reason", "SESSION_NOT_OWNED")) }
        assertTrue(result.isFailure)
        assertTrue(said.isNotEmpty())
        assertEquals(Outbox.NOT_SENT, box.get(a.id)?.state)
        assertEquals(setOf(a.id), box.unsaved())
        // A restart now: the disk's older "sending, submitted" never sends by itself.
        assertEquals(Outbox.UNSURE, Outbox(disk).get(a.id)?.state)
        // The disk recovers: the next change writes the outcome first.
        disk.full = false
        send(box, "b")
        assertTrue(box.unsaved().isEmpty())
        assertEquals(Outbox.NOT_SENT, disk.entry(a.id)?.state)
    }

    @Test fun aTakenMessageTheDiskCannotForgetIsNeverResentByItself() = runBlocking {
        val said = mutableListOf<Throwable>()
        box.onWriteFailed = { said += it }
        val a = send(box, "a")
        box.claim(a.id)
        box.deliver(a.id, receipt) { s -> s(); disk.full = true; "adm" }
        assertNull(box.get(a.id))
        assertTrue(said.isNotEmpty())
        assertEquals(Outbox.UNSURE, Outbox(disk).get(a.id)?.state)
        disk.full = false
        send(box, "b")
        assertNull(disk.entry(a.id))
    }

    @Test fun aRotatedSessionTheDiskRefusesStaysVisibleAndIsWrittenLater() {
        val said = mutableListOf<Throwable>()
        box.onWriteFailed = { said += it }
        val a = send(box, "a")
        disk.full = true
        box.rekey(ezra, "gw:ezra:compressed", "compressed")
        assertTrue(said.isNotEmpty())
        assertEquals(listOf(a.id), box.entries("gw:ezra:compressed").map { it.id })
        disk.full = false
        send(box, "b")
        assertEquals("gw:ezra:compressed", disk.entry(a.id)?.chatKey)
    }

    // Drafts.
    @Test fun aDraftTheDiskRefusesIsSaid() = runBlocking {
        val rows = Rows()
        val keeper = ComposerKeeper(ChatComposerStore(rows), Dispatchers.Unconfined)
        val said = mutableListOf<Throwable>()
        keeper.onSaveFailed = { said += it }
        keeper.beginLoad(); keeper.load(ezra, "ezra-chat") { true }
        rows.full = true
        assertFalse(keeper.persistNow(ezra, keeper.token(ezra), ChatComposerStore.State(draft = "x")))
        assertFalse(keeper.persist(ezra, keeper.token(ezra), ChatComposerStore.State(draft = "y")))
        assertEquals(2, said.size)
        rows.full = false
        assertTrue(keeper.persist(ezra, keeper.token(ezra), ChatComposerStore.State(draft = "z")))
        assertEquals("z", ChatComposerStore(rows).load(ezra).draft)
    }

    // The sweep: no other place in the ViewModel writes or deletes the outbox, the queue or a draft.
    private val vm: String = listOf(
        File("src/main/java/com/nousresearch/hermesmobile/HermesViewModel.kt"),
        File("app/src/main/java/com/nousresearch/hermesmobile/HermesViewModel.kt")
    ).first { it.exists() }.readText()

    private val app: String = listOf(
        File("src/main/java/com/nousresearch/hermesmobile/HermesApplication.kt"),
        File("app/src/main/java/com/nousresearch/hermesmobile/HermesApplication.kt")
    ).first { it.exists() }.readText()

    /** The name of the function each match sits in. */
    private fun enclosing(pattern: String): Set<String> = Regex(pattern).findAll(vm).map { m ->
        Regex("""fun (\w+)\(""").findAll(vm.substring(0, m.range.first)).last().groupValues[1]
    }.toSet()

    @Test fun everyOutboxAndDraftWriteIsInItsOnePlace() {
        assertEquals(setOf("enqueueSend"), enclosing("""outbox\.enqueue\("""))
        assertEquals(setOf("sendEntry", "handOffQueue"), enclosing("""outbox\.claim\("""))
        assertEquals(setOf("deliverEntry"), enclosing("""outbox\.deliver\("""))
        assertEquals(setOf("removeQueuedPrompt"), enclosing("""outbox\.discard\("""))
        assertEquals(setOf("clearQueuedPrompts"), enclosing("""outbox\.discardAll\("""))
        assertEquals(setOf("updateQueuedPrompt"), enclosing("""outbox\.update\("""))
        assertEquals(setOf("retryQueuedPrompt"), enclosing("""outbox\.retry\("""))
        assertEquals(setOf("moveQueuedPrompt"), enclosing("""outbox\.move\("""))
        assertEquals(setOf("compressActiveChat"), enclosing("""outbox\.rekey\("""))
        assertEquals(setOf("loadChatEnhancements"), enclosing("""outbox\.importLegacy\("""))
        // Nothing else touches an entry: no refused/admitted/settle/markSubmitted outside the outbox's own deliver.
        assertTrue(enclosing("""outbox\.(refused|admitted|settle|markSubmitted)\(""").isEmpty())
        // Drafts are written only through the keeper; the store itself is only read for cleanup.
        assertTrue(enclosing("""composerStore\.(save|dropLegacyQueue)\(""").isEmpty())
        assertEquals(setOf("persistComposerSoon"), enclosing("""composerKeeper\.persist\("""))
        assertEquals(setOf("persistCurrentComposerNow"), enclosing("""composerKeeper\.persistNow\("""))
        assertEquals(setOf("loadChatEnhancements"), enclosing("""composerKeeper\.migrateLegacy\("""))
        // Logout, forgetting or switching a connection, switching profile, deleting a chat: none writes the outbox.
        for (fn in listOf("fun disconnect(", "fun switchConnection(", "fun forgetConnection(", "fun signOutActive(",
            "fun selectProfile(", "fun deleteSession(", "fun closeActiveChat(")) {
            val body = vm.substringAfter(fn).substringBefore("\n    fun ").substringBefore("\n    private fun ")
            assertFalse("$fn writes the outbox", Regex("""outbox\.(enqueue|discard|discardAll|update|retry|move|rekey|importLegacy)\(""").containsMatchIn(body))
        }
        // Every queue change says when the disk refused it.
        assertTrue(vm.contains("OutboxChange.NOT_SAVED -> uiState = uiState.copy(error = string(failed))"))
        assertTrue(vm.substringAfter("fun clearQueuedPrompts()").substringBefore("\n    fun ").contains("R.string.queue_clear_failed"))
        assertTrue(vm.substringAfter("composerKeeper.migrateLegacy(key)").substringBefore("refreshSessionMeta()").contains("R.string.queue_import_failed"))
        assertTrue(vm.contains("box.onWriteFailed = onOutboxWriteFailed"))
        assertTrue(vm.contains("keeper.onSaveFailed ="))
        // Cleanup never deletes a file while the outbox cannot say which it holds.
        assertTrue(app.contains("Outboxes.filesToKeep(this)"))
        assertTrue(app.contains("if (outboxFiles == null) emptyList()"))
        assertTrue(app.contains("if (outboxFiles != null) runCatching"))
        assertTrue(vm.contains("Outboxes.filesToKeep(getApplication())?.let"))
    }
}
