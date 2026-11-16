package com.nousresearch.hermesmobile.data

/**
 * Where an approval request is shown: as a card in the bot chat that is on screen, or as the ordinary dialog.
 *
 * A request is a card only while its bot chat is actually visible (the Chat page is showing that chat). The
 * view model keeps the open chat when the user goes to another page through the drawer or the rail, so "is
 * there an active chat" is not enough: a card on a page nobody sees would let the request time out unseen
 * (Shomer P2 review). When the chat stops being visible its waiting cards go back to the dialog, and when
 * it is visible again the dialog's requests for it come back into the chat.
 */
class InlineApprovalRouter<R>(
    private val idOf: (R) -> String,
    private val methodOf: (R) -> String,
    private val sessionOf: (R) -> String
) {
    /** The bot chat on screen. Build it only when the Chat page shows a writable bot chat; otherwise null. */
    data class Surface(val runtimeSessionId: String?, val storedSessionId: String?)

    companion object {
        /** The surface for the current page: only the Chat page showing a writable bot chat is one. */
        fun surfaceFor(chatPageShowing: Boolean, botChat: Boolean, readOnly: Boolean, runtimeSessionId: String?, storedSessionId: String?): Surface? =
            if (chatPageShowing && botChat && !readOnly) Surface(runtimeSessionId, storedSessionId) else null
    }

    enum class Route { CARD, HOLD, DIALOG }

    /** What [reconcile] moved: requests to take out of the dialog and show as cards, and requests to show in the dialog. */
    data class Moves<R>(val toCards: List<R>, val toDialog: List<R>)

    private val cards = linkedMapOf<String, R>()
    private val held = mutableListOf<R>()

    /** A chat is opening: its replayed open requests arrive before it is on screen and wait for [reconcile]. */
    var opening = false
        private set

    val pendingIds: Set<String> get() = cards.keys.toSet()

    /** Every request shown as a card or waiting for an opening chat. */
    val requests: List<R> get() = cards.values.toList() + held

    fun belongs(request: R, surface: Surface?): Boolean {
        if (surface == null || methodOf(request) != "approval") return false
        val sid = sessionOf(request)
        return sid.isNotBlank() && (sid == surface.runtimeSessionId || sid == surface.storedSessionId)
    }

    fun beginOpen() { opening = true }

    /** A new approval request. CARD means the caller shows a card (and drops any dialog copy of the same id). */
    fun onRequest(request: R, surface: Surface?): Route = when {
        belongs(request, surface) -> { cards[idOf(request)] = request; Route.CARD }
        opening -> { held += request; Route.HOLD }
        else -> Route.DIALOG
    }

    /**
     * The visible chat changed (opened, closed, another page, back to the chat). Ends any opening.
     * Cards whose chat is no longer visible go to the dialog; dialog and held requests for the visible chat become cards;
     * the other held requests go to the dialog. [dialog] is the current dialog plus its queue.
     */
    fun reconcile(surface: Surface?, dialog: List<R>): Moves<R> {
        opening = false
        val toDialog = mutableListOf<R>()
        val gone = cards.values.filter { !belongs(it, surface) }
        gone.forEach { cards.remove(idOf(it)) }
        toDialog += gone
        val waiting = held.toList().also { held.clear() }
        val toCards = mutableListOf<R>()
        (dialog + waiting).forEach { r ->
            if (belongs(r, surface) && toCards.none { idOf(it) == idOf(r) }) { cards[idOf(r)] = r; toCards += r }
        }
        waiting.filterNot { belongs(it, surface) }.forEach { r -> if (toDialog.none { idOf(it) == idOf(r) }) toDialog += r }
        return Moves(toCards, toDialog)
    }

    /** The connection these requests came from ended: every card and held request is dead (none is answerable). */
    fun reset() {
        cards.clear()
        held.clear()
        opening = false
    }

    /** The user answered a card: the request, if it is still waiting as a card. */
    fun answer(requestId: String): R? = cards.remove(requestId)

    /** The server cancelled a request: true when it was a card (held copies are dropped silently). */
    fun withdraw(requestId: String): Boolean {
        held.removeAll { idOf(it) == requestId }
        return cards.remove(requestId) != null
    }
}
