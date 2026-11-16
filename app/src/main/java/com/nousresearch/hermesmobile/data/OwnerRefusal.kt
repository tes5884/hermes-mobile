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
 *    be queued, check the chat" until you send or remove it.
 * Either way the message is kept: a refused message is never lost.
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

    /** What a failed send becomes. */
    enum class Outcome { RETRY_QUEUE, HOLD_QUEUE, DRAFT, QUEUE, DROP_EDIT }

    fun outcome(reason: String?, composerEmpty: Boolean, rewind: Boolean): Outcome = when {
        // An edit cannot wait in the queue (it rewinds the chat): back in the composer when it is free, as before.
        rewind -> if (composerEmpty) Outcome.DRAFT else Outcome.DROP_EDIT
        reason == BUSY -> Outcome.RETRY_QUEUE
        reason == UNCONFIRMED -> if (composerEmpty) Outcome.DRAFT else Outcome.HOLD_QUEUE
        composerEmpty -> Outcome.DRAFT
        else -> Outcome.QUEUE
    }

    /** The next queued message that may go out by itself: never one that may already be queued on the gateway. */
    fun nextToSend(queue: List<QueuedPrompt>): QueuedPrompt? = queue.firstOrNull { it.hold != QueuedPrompt.HOLD_UNCONFIRMED }

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
