package com.nousresearch.hermesmobile.data

import org.json.JSONObject

/**
 * How a send the gateway did not take is classified (card t_cde1eeab). Hermes answers 4090 with a reason
 * (tui_gateway/methods_prompt.py):
 *  - SESSION_OWNER_BUSY: nothing was sent and the owner's queue could not take it (an attachment, a long chat on an
 *    old gateway, the owner unreachable). The turn ends on its own: the message waits in the outbox, marked "sends when
 *    <bot>'s turn ends", and is sent again by itself until it goes through.
 *  - SESSION_OWNER_UNCONFIRMED: the owner did not confirm in time, so it may already be queued there. It is NEVER sent
 *    again by itself (a second copy would run): it waits in the outbox as "Not sure it sent" until you send it again
 *    or remove it, and what was queued after it waits too.
 * Any other failure is "Not sent", kept the same way. Where it is kept, and the order rules: [Outbox].
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
     * What a send (or edit) the gateway did not take waits as. [submitted] is whether prompt.submit was about to go out
     * (the outbox marks that on disk first): an upload or model switch that failed before it sent nothing.
     *  - busy: nothing was sent and the owner's turn runs; tried again by itself.
     *  - unconfirmed: it may have reached the gateway (the owner did not confirm, or no answer came back at all).
     *  - not sent: the gateway answered with a refusal (SESSION_NOT_OWNED, a model error, ...), or nothing went out
     *    (no socket: [GatewayNotSentException]).
     */
    fun holdFor(t: Throwable?, submitted: Boolean): String = when {
        !submitted || t is GatewayNotSentException -> QueuedPrompt.HOLD_NOT_SENT
        reason(t) == BUSY -> QueuedPrompt.HOLD_BUSY
        reason(t) == UNCONFIRMED -> QueuedPrompt.HOLD_UNCONFIRMED
        t is GatewayRpcException -> QueuedPrompt.HOLD_NOT_SENT
        else -> QueuedPrompt.HOLD_UNCONFIRMED
    }

    /** Held for you (red label, Send again): not sent, not sure it sent, or an edit not applied. */
    fun waitsForYou(item: QueuedPrompt): Boolean = item.hold == QueuedPrompt.HOLD_UNCONFIRMED ||
        item.hold == QueuedPrompt.HOLD_NOT_SENT || item.hold == QueuedPrompt.HOLD_EDIT

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
