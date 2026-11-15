package com.nousresearch.hermesmobile.data

/**
 * What is on screen, as far as per-chat state cares: the page, the open chat (and whether it is a writable bot chat),
 * the open room, the channels list, and the connection. Two equal views mean the user is looking at the same thing.
 *
 * [epoch] changes when the connection the open chat belongs to ends (disconnect, switch, sign-out), even if the
 * connection id stays the same: requests and recordings of the old connection are dead after that.
 */
data class ChatView(
    val epoch: Long,
    val connection: String?,
    val section: String,
    val chatPage: Boolean,
    val storedSessionId: String?,
    val runtimeSessionId: String?,
    val profile: String?,
    val botChat: Boolean,
    val readOnly: Boolean,
    val room: String?,
    val channelsList: Boolean
) {
    /** The bot chat actually on screen, for approval cards, or null. */
    val surface: InlineApprovalRouter.Surface?
        get() = if (storedSessionId == null && runtimeSessionId == null) null
        else InlineApprovalRouter.surfaceFor(chatPage, botChat, readOnly, runtimeSessionId, storedSessionId)

    /** The Chat page is showing [profile]'s bot chat on [connection]. */
    fun showsBotChatOf(profile: String, connection: String): Boolean =
        chatPage && botChat && !readOnly && this.profile == profile && this.connection.orEmpty() == connection
}

/**
 * The ONE place per-chat state is reconciled when what is on screen changes (Tzvi, 2026-11-15: fix it centrally).
 *
 * The view model runs [reconcile] on every change of the visible chat or page, and on every navigation intent
 * (new chat, branch, channel, All channels, drawer/rail, section or connection change, push tap, failed or stale
 * open, back), before that navigation has changed the screen. Each run:
 *  - (a) discards a pending "Talk to …" recording unless it is still for what is now happening: its own open is the
 *    newest navigation and the screen is either still where it was recorded or showing that bot's chat;
 *  - (b) sorts approvals: waiting cards of a chat no longer on screen go to the dialog, the dialog's (and held)
 *    requests for the bot chat on screen become cards;
 *  - forgets every approval of a connection that ended ([ChatView.epoch]).
 */
class ChatChange<R, T>(
    val router: InlineApprovalRouter<R>,
    val talk: PendingTalkSlot<T, ChatView>
) {
    var last: ChatView? = null
        private set

    /** True when [view] is not what the last reconcile saw: the screen changed and [reconcile] must run. */
    fun differs(view: ChatView): Boolean = view != last

    /** [generation] = the newest navigation; [dialog] = the dialog's request plus its queue. Returns the approval moves. */
    fun reconcile(view: ChatView, generation: Long, dialog: List<R>): InlineApprovalRouter.Moves<R> {
        val previous = last
        last = view
        val ended = previous != null && previous.epoch != view.epoch
        if (ended) router.reset()
        talk.reconcile(generation, view) { profile, connection -> view.showsBotChatOf(profile, connection) }
        return router.reconcile(view.surface, if (ended) emptyList() else dialog)
    }
}
