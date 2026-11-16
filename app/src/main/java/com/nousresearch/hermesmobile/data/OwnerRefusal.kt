package com.nousresearch.hermesmobile.data

import org.json.JSONObject

/**
 * A send refused because the gateway that owns this chat is running a turn (card t_cde1eeab). Hermes answers 4090 with
 * a reason (tui_gateway/methods_prompt.py):
 *  - SESSION_OWNER_BUSY: nothing was sent and the owner's queue could not take it (an attachment, a long chat on an
 *    old gateway, the owner unreachable). The turn ends on its own: the message waits in the visible queue, marked
 *    "sends when <bot>'s turn ends", and is sent again by itself until it goes through.
 *  - SESSION_OWNER_UNCONFIRMED: the owner did not confirm in time, so it may already be queued there. It is NEVER sent
 *    again by itself (a second copy would run): it goes back to the composer, or waits in the queue marked "may already
 *    be queued, check the chat" until you send or remove it; what was queued after it waits too.
 * Any other failure is kept the same way (marked "not sent"), in the chat it was written in even after you left it
 * (Shomer t_8d113995): a refused message is never lost, and nothing queued after it overtakes it.
 */
object OwnerRefusal {
    const val BUSY = "SESSION_OWNER_BUSY"
    const val UNCONFIRMED = "SESSION_OWNER_UNCONFIRMED"
    /** How long a held message waits before it is tried again. */
    const val RETRY_MS = 5_000L

    fun reason(t: Throwable?): String? {
        val e = t as? GatewayRpcException ?: return null
        val reason = (e.data as? JSONObject)?.optString("reason").orEmpty()
        return reason.takeIf { it == BUSY || it == UNCONFIRMED }
    }

    /**
     * What a send (or edit) the gateway did not take waits as. [submitted] is whether prompt.submit went out at all: an
     * upload or model switch that failed first sent nothing.
     *  - busy: nothing was sent and the owner's turn runs; tried again by itself.
     *  - unconfirmed: it may have reached the gateway (the owner did not confirm, or no answer came back at all).
     *  - not sent: the gateway answered with a refusal (SESSION_NOT_OWNED, a model error, ...), or nothing went out.
     */
    fun holdFor(t: Throwable?, submitted: Boolean): String = when {
        !submitted -> QueuedPrompt.HOLD_NOT_SENT
        reason(t) == BUSY -> QueuedPrompt.HOLD_BUSY
        reason(t) == UNCONFIRMED -> QueuedPrompt.HOLD_UNCONFIRMED
        t is GatewayRpcException -> QueuedPrompt.HOLD_NOT_SENT
        else -> QueuedPrompt.HOLD_UNCONFIRMED
    }

    /**
     * Holds that stop the queue: neither that message nor anything queued after it goes out by itself, so a later
     * instruction never runs ahead of one whose fate is unknown or that failed. You resolve it: edit (and save) it to
     * send it again, move it, or remove it.
     */
    fun fences(item: QueuedPrompt): Boolean = item.hold == QueuedPrompt.HOLD_UNCONFIRMED ||
        item.hold == QueuedPrompt.HOLD_NOT_SENT || item.hold == QueuedPrompt.HOLD_EDIT

    /** What may go out by itself, in order: everything before the first fence. */
    fun autoSendable(queue: List<QueuedPrompt>): List<QueuedPrompt> = queue.takeWhile { !fences(it) }

    /** The next queued message that may go out by itself (drain, busy retry): the head, unless it is a fence. */
    fun nextToSend(queue: List<QueuedPrompt>): QueuedPrompt? = autoSendable(queue).firstOrNull()

    /** Leaving a chat: what is handed to the gateway, and what stays with the chat (a fence and all after it). */
    fun handOffPlan(queue: List<QueuedPrompt>): Pair<List<QueuedPrompt>, List<QueuedPrompt>> {
        val send = autoSendable(queue)
        return send to queue.drop(send.size)
    }

    /** A handoff refused at the first of [rest]: it and everything after it go back, in order, the first one held. */
    fun afterHandOffFailure(rest: List<QueuedPrompt>, t: Throwable?, submitted: Boolean): List<QueuedPrompt> =
        if (rest.isEmpty()) rest else listOf(rest.first().copy(hold = holdFor(t, submitted))) + rest.drop(1)

    /**
     * A send or edit the gateway did not take is kept in the chat it was written in, open or not: never lost. Back in
     * the composer when that is free and nothing is queued (nothing can overtake it there); otherwise at the head of the
     * queue with its hold, so it stays ahead of what was queued after it. An edit is never re-sent by itself: sent again
     * from the queue it would be a new message, not a rewind.
     */
    fun keep(
        state: ChatComposerStore.State,
        id: String,
        text: String,
        attachments: List<ComposerAttachment>,
        hold: String,
        edit: Boolean
    ): ChatComposerStore.State {
        val held = if (edit && hold != QueuedPrompt.HOLD_UNCONFIRMED) QueuedPrompt.HOLD_EDIT else hold
        val composerFree = state.draft.isBlank() && state.attachments.isEmpty()
        return if (held != QueuedPrompt.HOLD_BUSY && composerFree && state.queue.isEmpty()) {
            state.copy(draft = text, attachments = attachments)
        } else {
            state.copy(queue = listOf(QueuedPrompt(id, text, attachments, hold = held)) + state.queue)
        }
    }

    /** The line over the composer while messages wait for a turn (null: none). */
    sealed interface Banner {
        /** The turn is waiting for an approval in this chat: say so, with a jump to the card. */
        data class WaitingForOk(val bot: String) : Banner
        /** Messages wait for the gateway's turn to end. */
        data class QueuedForTurn(val bot: String?) : Banner
    }

    fun banner(queue: List<QueuedPrompt>, approvalWaiting: Boolean, bot: String?): Banner? {
        val held = queue.any { it.hold == QueuedPrompt.HOLD_BUSY }
        return when {
            approvalWaiting && bot != null && (held || queue.isNotEmpty()) -> Banner.WaitingForOk(bot)
            held -> Banner.QueuedForTurn(bot)
            else -> null
        }
    }
}
