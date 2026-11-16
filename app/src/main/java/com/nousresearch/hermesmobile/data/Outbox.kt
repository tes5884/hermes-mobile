package com.nousresearch.hermesmobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

/** Where the outbox keeps its entries: one string per entry id. Every call is on disk when it returns, or throws. */
interface OutboxStorage {
    fun readAll(): Map<String, String>
    /**
     * Writes (null: deletes) all of [changes] in one go and returns only once they are durable: all of them or none.
     * Throws when the disk refused (then none of them is written).
     */
    fun writeAll(changes: Map<String, String?>)
    /** Writes (null: deletes) one entry; see [writeAll]. */
    fun write(id: String, value: String?) = writeAll(mapOf(id to value))
}

/** What a change you asked for did. */
enum class OutboxChange {
    DONE,
    /** Not allowed now (it is going out, it is gone, nothing to move): nothing changed. */
    NOT_ALLOWED,
    /** The disk refused it: nothing changed, on disk or on screen. The caller says so. */
    NOT_SAVED
}

/** The rewind an edit asks for while it is still waiting or going out (Desktop's truncateSubmitParams). */
data class OutboxEdit(val key: String, val rowId: Long?, val ordinal: Int, val laterTurns: Int, val confirmDeep: Boolean) {
    fun turn(): EditingTurn = EditingTurn(key = key, rowId = rowId, ordinal = ordinal, laterTurns = laterTurns)
}

/** What the gateway answered when it took a message: the only thing that removes an outbox entry. */
data class OutboxReceipt(val status: String, val admissionId: String?)

/**
 * One message you sent (or edit you saved). It lives in the outbox, in its chat, from the moment you press send until
 * the gateway confirms it took it.
 */
data class OutboxEntry(
    val id: String,
    /** The chat it was written in: connection, profile and stored session (the composer key). */
    val chatKey: String,
    val storedSessionId: String,
    val profile: String,
    val text: String,
    val attachments: List<ComposerAttachment> = emptyList(),
    /** Order within the chat: lower goes first. */
    val seq: Long,
    val createdAt: Long,
    val state: String = Outbox.QUEUED,
    /** Set (on disk) just before prompt.submit is called: from then on the gateway may have it. */
    val submitted: Boolean = false,
    /** The state it was claimed from: where it goes back to if it never reached prompt.submit. */
    val claimedFrom: String = Outbox.QUEUED,
    /** Goes out as soon as it is the chat's head, even while a turn runs (a live call's spoken turn). */
    val direct: Boolean = false,
    val voiceTurn: Boolean = false,
    val interrupted: Boolean = false,
    /** An edit that has not been sent yet: the rewind it asks for. Dropped once it fails (never re-sent as a rewind). */
    val edit: OutboxEdit? = null,
    /** It was an edit the gateway did not apply (the label says so). */
    val editFailed: Boolean = false
)

/**
 * The outbox (Tzvi 2026-11-15, Shomer t_478c28f5): every prompt and edit is written here, on disk, BEFORE
 * prompt.submit is called, and only a confirmed gateway receipt ([admitted]) removes it. Nothing else does: not
 * closing or reopening the chat, not the ViewModel being cleared, not a cancelled coroutine, not a connection switch,
 * not the process dying. The chat draws its waiting messages from here when it opens.
 *
 * States:
 *  - queued: waits for its turn (the chat's turn to end, or the messages ahead of it).
 *  - sending: claimed by one sender in this process; [OutboxEntry.submitted] says whether prompt.submit went out.
 *  - busy: the gateway refused it because its own turn runs (SESSION_OWNER_BUSY): nothing was sent; tried again.
 *  - not_sent: refused (SESSION_NOT_OWNED, any other gateway error) or it never went out. Kept, never re-sent by itself.
 *  - unsure: it may have reached the gateway (SESSION_OWNER_UNCONFIRMED, no answer, cancelled or killed after
 *    prompt.submit went out). Kept, never re-sent by itself.
 *
 * Order: only the head of a chat may go out, and only when it is queued or busy. Anything sending, not sent or unsure
 * at the head stops every entry after it (no skip-ahead), until you resolve it: send again, edit, or remove.
 *
 * A failed write stops and says so (Tzvi 2026-11-15, Shomer t_737920a6):
 *  - Anything you ask for (send, send again, edit, remove, clear, move), every claim and the legacy move change memory
 *    only after the disk has them; when the disk refuses, nothing changes and the caller is told
 *    ([OutboxChange.NOT_SAVED], false, or a throw) and says so.
 *  - What the gateway answered (taken, refused, a cancelled sender, a rotated session) is recorded in memory even when
 *    the disk refuses it, because what the disk still holds then is the earlier, safer state ("sending"), which no
 *    start ever sends by itself. [onWriteFailed] says so, and the write is tried again with the next change.
 *  - Nothing read from the disk at start is ever sent by itself: it loads as not sent (unsure once prompt.submit went
 *    out) and waits for you to tap. The same goes for a queue moved in from an older version.
 */
class Outbox(private val storage: OutboxStorage, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val QUEUED = "queued"
        const val SENDING = "sending"
        const val BUSY = "busy"
        const val NOT_SENT = "not_sent"
        const val UNSURE = "unsure"
        val STATES = setOf(QUEUED, SENDING, BUSY, NOT_SENT, UNSURE)
        /** Waiting for you: never sent by itself, and nothing after it goes. */
        val FENCES = setOf(NOT_SENT, UNSURE)
        /** May go out by itself when it is the head. */
        val SENDABLE = setOf(QUEUED, BUSY)
        private const val IMPORTED = "imported:"

        fun encode(e: OutboxEntry): String = JSONObject()
            .put("id", e.id).put("chat", e.chatKey).put("session", e.storedSessionId).put("profile", e.profile)
            .put("text", e.text).put("seq", e.seq).put("created_at", e.createdAt).put("state", e.state)
            .put("submitted", e.submitted).put("claimed_from", e.claimedFrom).put("direct", e.direct)
            .put("voice_turn", e.voiceTurn).put("interrupted", e.interrupted).put("edit_failed", e.editFailed)
            .put("attachments", JSONArray(e.attachments.map {
                JSONObject().put("id", it.id).put("uri", it.uri).put("name", it.name).put("mime", it.mimeType).put("size", it.size)
            }))
            .apply {
                e.edit?.let { edit ->
                    put("edit", JSONObject().put("key", edit.key).put("ordinal", edit.ordinal)
                        .put("later", edit.laterTurns).put("deep", edit.confirmDeep)
                        .apply { edit.rowId?.let { put("row_id", it) } })
                }
            }.toString()

        fun decode(raw: String): OutboxEntry? = runCatching {
            val o = JSONObject(raw)
            val files = o.optJSONArray("attachments") ?: JSONArray()
            OutboxEntry(
                id = o.getString("id"),
                chatKey = o.getString("chat"),
                storedSessionId = o.optString("session"),
                profile = o.optString("profile"),
                text = o.optString("text"),
                attachments = (0 until files.length()).mapNotNull { i ->
                    files.optJSONObject(i)?.let {
                        ComposerAttachment(it.optString("id"), it.optString("uri"), it.optString("name"),
                            it.optString("mime", "application/octet-stream"), it.optLong("size", 0L))
                    }
                },
                seq = o.optLong("seq"),
                createdAt = o.optLong("created_at"),
                state = o.optString("state").takeIf { it in STATES } ?: UNSURE,
                submitted = o.optBoolean("submitted"),
                claimedFrom = o.optString("claimed_from").takeIf { it in SENDABLE } ?: QUEUED,
                direct = o.optBoolean("direct"),
                voiceTurn = o.optBoolean("voice_turn"),
                interrupted = o.optBoolean("interrupted"),
                edit = o.optJSONObject("edit")?.let {
                    OutboxEdit(it.optString("key"), if (it.has("row_id")) it.optLong("row_id") else null,
                        it.optInt("ordinal"), it.optInt("later"), it.optBoolean("deep"))
                },
                editFailed = o.optBoolean("edit_failed")
            )
        }.getOrNull()
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, OutboxEntry>()
    /** Entries a sender in THIS process holds. A sending entry not in here has no sender left (it is settled). */
    private val inFlight = HashSet<String>()
    /** Ids of legacy queue items already moved in (kept on disk), so a composer saved by an old version never re-adds one. */
    private val imported = HashSet<String>()
    /** Entries whose latest outcome is only in memory so far (recorded while the disk refused it). */
    private val dirty = HashSet<String>()
    private var nextSeq = 0L
    /** Called after every change (on the thread that made it). */
    @Volatile var onChange: (() -> Unit)? = null
    /** Called when the disk refused to record what happened to a message (on the thread that saw it). */
    @Volatile var onWriteFailed: ((Throwable) -> Unit)? = null

    init {
        storage.readAll().forEach { (id, raw) ->
            if (id.startsWith(IMPORTED)) imported += id.removePrefix(IMPORTED)
            else decode(raw)?.let { entries[id] = restored(it) }
        }
        nextSeq = (entries.values.maxOfOrNull { it.seq } ?: 0L) + 1
    }

    /**
     * What an entry read from the disk at start becomes: never something that goes out by itself (Tzvi 2026-11-15).
     * In memory only, on every start, so there is nothing to write and nothing that can fail; the disk keeps what it
     * had until you act on the entry.
     */
    private fun restored(e: OutboxEntry): OutboxEntry = when {
        e.state == UNSURE || e.state == NOT_SENT -> e
        e.state == SENDING && e.submitted -> failed(e, UNSURE)
        else -> failed(e, NOT_SENT)
    }

    /** Writes [changes] in one durable go, or throws having written none of them. */
    private fun commit(changes: Map<String, String?>) {
        if (changes.isNotEmpty()) storage.writeAll(changes)
    }

    /** On disk first; memory only once the disk has it. Throws (memory untouched) when the disk refused. */
    private fun put(e: OutboxEntry) {
        storage.write(e.id, encode(e))
        entries[e.id] = e
        dirty -= e.id
    }

    private fun drop(id: String) {
        storage.write(id, null)
        entries.remove(id)
        dirty -= id
    }

    /**
     * What the gateway answered (or a cancelled sender) is recorded in memory even if the disk refuses it: what the
     * disk still holds then is the earlier, safer state ("sending", which no start ever sends by itself). Says so, and
     * the write is tried again with the next change.
     */
    private fun record(e: OutboxEntry) {
        entries[e.id] = e
        persistOutcome(e.id, encode(e))
    }

    private fun forget(id: String) {
        entries.remove(id)
        persistOutcome(id, null)
    }

    private fun persistOutcome(id: String, value: String?) {
        try {
            storage.write(id, value)
            dirty -= id
        } catch (t: Throwable) {
            dirty += id
            onWriteFailed?.invoke(t)
        }
    }

    /** Outcomes the disk refused earlier: written now, before the next change (best effort, all or none). */
    private fun flushDirty() {
        if (dirty.isEmpty()) return
        runCatching { commit(dirty.associateWith { id -> entries[id]?.let(::encode) }) }.onSuccess { dirty.clear() }
    }

    /** A change you asked for: [OutboxChange.NOT_SAVED] when the disk refused it (then nothing changed). */
    private inline fun userChange(block: () -> OutboxChange): OutboxChange =
        try { mutate(block) } catch (t: CancellationException) { throw t } catch (_: Throwable) { OutboxChange.NOT_SAVED }

    private fun changed() = onChange?.invoke()

    private inline fun <T> mutate(block: () -> T): T {
        val result = synchronized(lock) {
            flushDirty()
            block()
        }
        changed()
        return result
    }

    /** Ids whose latest outcome is only in memory (the disk has refused it so far). */
    fun unsaved(): Set<String> = synchronized(lock) { HashSet(dirty) }

    fun get(id: String): OutboxEntry? = synchronized(lock) { entries[id] }

    /** A chat's entries, in the order they go out. */
    fun entries(chatKey: String): List<OutboxEntry> = synchronized(lock) {
        entries.values.filter { it.chatKey == chatKey }.sortedBy { it.seq }
    }

    fun all(): List<OutboxEntry> = synchronized(lock) { entries.values.sortedBy { it.seq } }

    /** Writes a new message at the end of its chat, durably, before anything sends it. */
    fun enqueue(
        chatKey: String,
        storedSessionId: String,
        profile: String,
        text: String,
        attachments: List<ComposerAttachment> = emptyList(),
        id: String = java.util.UUID.randomUUID().toString(),
        direct: Boolean = false,
        voiceTurn: Boolean = false,
        interrupted: Boolean = false,
        edit: OutboxEdit? = null
    ): OutboxEntry = mutate {
        OutboxEntry(
            id = id, chatKey = chatKey, storedSessionId = storedSessionId, profile = profile, text = text,
            attachments = attachments, seq = nextSeq++, createdAt = clock(), direct = direct, voiceTurn = voiceTurn,
            interrupted = interrupted, edit = edit
        ).also(::put)
    }

    /** The chat's head, when it may go out now: queued or busy, and the chat idle (or the head goes straight away). */
    fun next(chatKey: String, idle: Boolean): OutboxEntry? =
        entries(chatKey).firstOrNull()?.takeIf { it.state in SENDABLE && (idle || it.direct) }

    /** Whether anything in the chat waits ahead of a new message (it would not be the head). */
    fun hasWaiting(chatKey: String): Boolean = entries(chatKey).isNotEmpty()

    /**
     * Claims [id] for sending: only the chat's head, only when queued or busy. Durable before it returns; exactly one
     * sender wins. Null: someone else has it, it is not the head, or it waits for you.
     */
    fun claim(id: String): OutboxEntry? = mutate {
        val e = entries[id] ?: return@mutate null
        val head = entries.values.filter { it.chatKey == e.chatKey }.minByOrNull { it.seq }
        if (head?.id != id || e.state !in SENDABLE || id in inFlight) return@mutate null
        // On disk first (a throw: not claimed, and no stale hold left behind); only then is it this sender's.
        e.copy(state = SENDING, submitted = false, claimedFrom = e.state).also(::put).also { inFlight += id }
    }

    /** prompt.submit is about to go out: from here on, an unknown outcome means it may be on the gateway. */
    fun markSubmitted(id: String) {
        mutate {
            entries[id]?.takeIf { it.state == SENDING && !it.submitted }?.let { put(it.copy(submitted = true)) }
        }
    }

    /** The gateway confirmed it took the message: the one way an entry leaves the outbox. */
    fun admitted(id: String, receipt: OutboxReceipt): OutboxReceipt = mutate {
        inFlight -= id
        if (entries.containsKey(id)) forget(id)
        receipt
    }

    /** The gateway (or the upload before it) did not take it: kept as [hold] (busy, not_sent or unsure). */
    fun refused(id: String, hold: String) {
        mutate {
            inFlight -= id
            val e = entries[id] ?: return@mutate
            val state = when (hold) {
                QueuedPrompt.HOLD_BUSY -> BUSY
                QueuedPrompt.HOLD_UNCONFIRMED -> UNSURE
                else -> NOT_SENT
            }
            record(failed(e, state))
        }
    }

    private fun failed(e: OutboxEntry, state: String): OutboxEntry = when {
        // An edit is never sent again by itself as a rewind, not even after busy: it waits for you.
        e.edit != null && state == BUSY -> e.copy(state = NOT_SENT, submitted = false, edit = null, editFailed = true)
        e.edit != null -> e.copy(state = state, submitted = false, edit = null, editFailed = true)
        else -> e.copy(state = state, submitted = false)
    }

    /** Where an entry whose sender is gone lands: unsure once submitted; else back where it was, unless it had files. */
    private fun orphaned(e: OutboxEntry): OutboxEntry = when {
        e.submitted -> failed(e, UNSURE)
        // Uploads may already sit staged on the session: sending them again by itself could double them.
        e.attachments.isNotEmpty() || e.edit != null -> failed(e, NOT_SENT)
        else -> e.copy(state = e.claimedFrom, submitted = false)
    }

    /**
     * The sender of [id] stopped without an answer (cancelled: connection switch, chat left, ViewModel cleared). Runs
     * in its `finally`, synchronously, so cancellation cannot skip it. No-op once the entry is resolved.
     */
    fun settle(id: String) {
        mutate {
            val held = inFlight.remove(id)
            val e = entries[id] ?: return@mutate
            if (held && e.state == SENDING) record(orphaned(e))
        }
    }

    /** You chose to send a held message again (not sent, or not sure it sent): it waits for its turn as new. */
    fun retry(id: String): OutboxChange = userChange {
        val e = entries[id]?.takeIf { it.state in FENCES } ?: return@userChange OutboxChange.NOT_ALLOWED
        put(e.copy(state = QUEUED, editFailed = false, edit = null))
        OutboxChange.DONE
    }

    /** You edited a waiting message: new text, and a held one is released to be sent again. */
    fun update(id: String, text: String): OutboxChange = userChange {
        val e = entries[id]?.takeIf { it.state != SENDING } ?: return@userChange OutboxChange.NOT_ALLOWED
        put(e.copy(text = text, state = if (e.state in FENCES) QUEUED else e.state, editFailed = false, edit = null))
        OutboxChange.DONE
    }

    /** You removed it (never while it is going out): off the disk first, then off the screen. */
    fun discard(id: String): OutboxChange = userChange {
        val e = entries[id]?.takeIf { it.state != SENDING } ?: return@userChange OutboxChange.NOT_ALLOWED
        drop(e.id)
        OutboxChange.DONE
    }

    /**
     * Clear: every waiting entry of the chat (not one going out) off the disk in one go, then off the screen. When the
     * disk refuses, none is removed anywhere: they all stay, and the caller says Clear failed.
     */
    fun discardAll(chatKey: String): OutboxChange = userChange {
        val ids = entries.values.filter { it.chatKey == chatKey && it.state != SENDING }.map { it.id }
        if (ids.isEmpty()) return@userChange OutboxChange.NOT_ALLOWED
        commit(ids.associateWith { null })
        ids.forEach { entries.remove(it); dirty -= it }
        OutboxChange.DONE
    }

    /** Moves a waiting entry among the chat's waiting ones; one going out keeps its place at the front. */
    fun move(id: String, delta: Int): OutboxChange = userChange {
        val e = entries[id]?.takeIf { it.state != SENDING } ?: return@userChange OutboxChange.NOT_ALLOWED
        val waiting = entries.values.filter { it.chatKey == e.chatKey && it.state != SENDING }.sortedBy { it.seq }
        val from = waiting.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, waiting.lastIndex)
        if (from < 0 || to == from) return@userChange OutboxChange.NOT_ALLOWED
        val seqs = waiting.map { it.seq }
        val reordered = waiting.toMutableList().apply { add(to, removeAt(from)) }
        val moved = reordered.mapIndexedNotNull { i, row -> if (row.seq != seqs[i]) row.copy(seq = seqs[i]) else null }
        // Every new place in one go: a half-written reorder never reaches the disk.
        commit(moved.associate { it.id to encode(it) })
        moved.forEach { entries[it.id] = it; dirty -= it.id }
        OutboxChange.DONE
    }

    /**
     * A chat whose stored session id rotated (compression, already done on the gateway): its entries follow it, in one
     * write. If the disk refuses, they follow it on screen anyway (else the chat you are in would lose sight of them),
     * [onWriteFailed] says so, and the write is tried again with the next change.
     */
    fun rekey(oldKey: String, newKey: String, storedSessionId: String) {
        if (oldKey == newKey) return
        mutate {
            val moved = entries.values.filter { it.chatKey == oldKey }.map { it.copy(chatKey = newKey, storedSessionId = storedSessionId) }
            if (moved.isEmpty()) return@mutate
            moved.forEach { entries[it.id] = it }
            try {
                commit(moved.associate { it.id to encode(it) })
                moved.forEach { dirty -= it.id }
            } catch (t: Throwable) {
                moved.forEach { dirty += it.id }
                onWriteFailed?.invoke(t)
            }
        }
    }

    /**
     * A queue saved in a chat's composer by an older version moves here: every entry and its marker in one durable
     * write. True only once all of them are on disk (or already were): only then may the caller remove the old queue
     * from the composer. False: the disk refused, nothing moved, and the old composer must keep its queue.
     * Moved entries never go out by themselves: they wait for you as not sent (unsure stays unsure).
     */
    fun importLegacy(chatKey: String, storedSessionId: String, profile: String, queue: List<QueuedPrompt>): Boolean {
        if (queue.isEmpty()) return true
        return try {
            mutate {
                val fresh = queue.filter { q -> q.id.isNotBlank() && q.id !in imported && !entries.containsKey(q.id) }
                    .distinctBy { it.id }
                    .mapIndexed { i, q ->
                        OutboxEntry(
                            id = q.id, chatKey = chatKey, storedSessionId = storedSessionId, profile = profile,
                            text = q.text, attachments = q.attachments, seq = nextSeq + i, createdAt = q.createdAt,
                            state = if (q.hold == QueuedPrompt.HOLD_UNCONFIRMED) UNSURE else NOT_SENT,
                            editFailed = q.hold == QueuedPrompt.HOLD_EDIT
                        )
                    }
                // Entries and markers together: no marker without its entry (the message would be lost), no entry
                // without its marker (the old composer could bring it back after it was sent).
                commit(fresh.associate { it.id to encode(it) } + fresh.associate { IMPORTED + it.id to "1" })
                nextSeq += fresh.size
                fresh.forEach { entries[it.id] = it; imported += it.id }
                true
            }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            false
        }
    }

    /** Leaving a chat: what may be handed to the gateway now, in order (everything up to the first that may not go). */
    fun handOffPlan(chatKey: String): List<OutboxEntry> = entries(chatKey).takeWhile { it.state in SENDABLE }

    /** The chat's waiting messages as the queue lane shows them. */
    fun view(chatKey: String): List<QueuedPrompt> = entries(chatKey).map { e ->
        QueuedPrompt(
            id = e.id, text = e.text, attachments = e.attachments, createdAt = e.createdAt,
            hold = when (e.state) {
                SENDING -> QueuedPrompt.HOLD_SENDING
                BUSY -> QueuedPrompt.HOLD_BUSY
                UNSURE -> QueuedPrompt.HOLD_UNCONFIRMED
                NOT_SENT -> if (e.editFailed) QueuedPrompt.HOLD_EDIT else QueuedPrompt.HOLD_NOT_SENT
                else -> null
            }
        )
    }

    /** Files the outbox still needs (cache cleanup must keep them). */
    fun referencedAttachmentUris(): Set<String> = synchronized(lock) {
        entries.values.flatMap { e -> e.attachments.map { it.uri } }.toSet()
    }
}

/**
 * The queue lane under the chat: the chat's outbox entries, except one going out whose bubble the chat already shows
 * (sent from this screen). After a reopen there is no bubble yet, so it shows as "Sending…".
 */
fun outboxLane(queue: List<QueuedPrompt>, messages: List<ChatMessage>): List<QueuedPrompt> {
    val shown = messages.mapNotNullTo(HashSet()) { it.outboxId }
    return queue.filterNot { it.hold == QueuedPrompt.HOLD_SENDING && it.id in shown }
}

/**
 * Sends one claimed entry. [send] does the uploads and the gateway call and must call `submitting()` right before
 * prompt.submit. A result is the gateway's receipt and removes the entry; a failure (a timeout included) keeps it as
 * busy, not sent or unsure ([OwnerRefusal.holdFor]); a cancellation of the sender itself (connection switch, the
 * ViewModel cleared) settles it synchronously, unsure once submitted, and rethrows.
 */
suspend fun <T> Outbox.deliver(id: String, receipt: (T) -> OutboxReceipt, send: suspend (submitting: () -> Unit) -> T): Result<T> {
    try {
        val result = send { markSubmitted(id) }
        admitted(id, receipt(result))
        return Result.success(result)
    } catch (t: Throwable) {
        // A request timeout is a CancellationException too, but its sender is still alive: that is a failure.
        if (t is CancellationException && !coroutineContext.isActive) {
            settle(id)
            throw t
        }
        refused(id, OwnerRefusal.holdFor(t, get(id)?.submitted == true))
        return Result.failure(t)
    } finally {
        // Whatever happened above, a sender that leaves never leaves its entry sending.
        settle(id)
    }
}
