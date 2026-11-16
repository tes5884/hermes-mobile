package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ActiveChat
import com.nousresearch.hermesmobile.data.ActiveGroupRoom
import com.nousresearch.hermesmobile.data.ChatChange
import com.nousresearch.hermesmobile.data.ChatView
import com.nousresearch.hermesmobile.data.GroupRoom
import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.InlineApprovalRouter
import com.nousresearch.hermesmobile.data.InlineApprovalRouter.Route
import com.nousresearch.hermesmobile.data.PendingTalkSlot
import com.nousresearch.hermesmobile.data.ServerInteraction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The central chat-change reconcile (Tzvi, 2026-11-15; Shomer P1 t_6a8a7a75 + P2 t_462f014f), one test per navigation
 * path, each for both cases:
 *  - P1: a "Talk to Ezra" note recorded on the bots page is never sent once the user goes anywhere else first;
 *  - P2: a waiting approval card of a bot chat that leaves the screen goes to the dialog, and back to a card when the
 *    chat is shown again.
 *
 * [Vm] wires [ChatChange] exactly as HermesViewModel does: [Vm.set] is the uiState setter (it reconciles whenever
 * [chatViewOf] — the view model's own mapping — changes), [Vm.navigate] is navigate(), and every path below applies the
 * same uiState copies as the view-model function it is named after. [HermesViewModelWiringTest] checks the view model
 * really routes through these two points.
 */
class ChatChangeTest {
    private fun bot(name: String, canonical: String) = HermesProfile(
        name = name, canonicalSession = canonical, uiMeta = JSONObject().put("hermes-bots", JSONObject())
    )
    private val ezra = bot("ezra", "st-ezra")
    private val otto = bot("otto", "st-otto")
    private val ezraChat = ActiveChat(storedSessionId = "st-ezra", runtimeSessionId = "rt-ezra", profile = "ezra", title = "Ezra", botChat = true)
    private val ottoChat = ActiveChat(storedSessionId = "st-otto", runtimeSessionId = "rt-otto", profile = "otto", title = "Otto", botChat = true)
    private val plainChat = ActiveChat(storedSessionId = "st-new", runtimeSessionId = "rt-new", profile = "ezra", title = "New chat")
    private val branchChat = ActiveChat(storedSessionId = "st-branch", runtimeSessionId = "rt-branch", profile = "ezra", title = "Branch")
    private val room = GroupRoom(roomId = "room-1", name = "Ops", members = emptyList())
    private fun approval(id: String, session: String = "rt-ezra") = ServerInteraction(id, "approval", JSONObject().put("session_id", session))

    private inner class Vm {
        var state = HermesUiState(activeConnectionId = "c1", profiles = listOf(ezra, otto), section = MainSection.BOTS)
        var epoch = 0L
        var generation = 0L
        val dialog = mutableListOf<ServerInteraction>()
        val cards = linkedSetOf<String>()
        val discarded = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val router = InlineApprovalRouter<ServerInteraction>({ it.requestId }, { it.method }, { it.params.optString("session_id") })
        val change = ChatChange(router, PendingTalkSlot<String, ChatView> { discarded += it })

        fun view() = chatViewOf(state, epoch)

        /** The uiState setter. */
        fun set(next: HermesUiState) { state = next; if (change.differs(view())) reconcile() }

        /** reconcileChatChange(). */
        fun reconcile() {
            state = settleOverlays(state)
            val moves = change.reconcile(view(), generation, dialog.toList())
            cards.retainAll(router.pendingIds)
            moves.toCards.forEach { r -> dialog.removeAll { it.requestId == r.requestId }; cards += r.requestId }
            moves.toDialog.forEach { r -> if (dialog.none { it.requestId == r.requestId }) dialog += r }
        }

        /** navigate(). */
        fun navigate(): Long {
            generation += 1
            if (state.searchOpen || state.botDetails != null) set(state.copy(searchOpen = false, botDetails = null))
            reconcile(); return generation
        }

        /** showSearch (drawer Search, Home pill; dismiss or a result closes it first). */
        fun showSearch(open: Boolean) = set(state.copy(searchOpen = open))

        /** openBotDetails (the bot chat's header) up to its guard, and closeBotDetails (back). */
        fun openDetails(profile: String) {
            val chat = state.activeChat ?: return
            if (state.section != MainSection.CHAT || chat.profile != profile ||
                com.nousresearch.hermesmobile.ui.screens.botChatProfile(chat, state.profiles) == null) return
            set(state.copy(botDetails = com.nousresearch.hermesmobile.data.BotDetailsState(profile)))
        }
        fun closeDetails() { if (state.botDetails != null) set(state.copy(botDetails = null)) }

        /** onServerRequest for an approval. */
        fun request(r: ServerInteraction): Route = router.onRequest(r, view().surface).also { route ->
            when (route) {
                Route.CARD -> { dialog.removeAll { it.requestId == r.requestId }; cards += r.requestId }
                Route.HOLD -> Unit
                Route.DIALOG -> if (dialog.none { it.requestId == r.requestId }) dialog += r
            }
        }

        // ---- the view-model paths -----------------------------------------------------------------------------

        /** openBot / talkToBot up to the network call; returns the open's generation. */
        fun startOpenBot(profile: HermesProfile, talk: String? = null): Long {
            val g = navigate()
            if (talk != null) change.talk.hold(profile.name, g, state.activeConnectionId.orEmpty(), view(), talk)
            router.beginOpen()
            return g
        }

        /** The open of [g] returns. */
        fun landOpenBot(g: Long, profile: HermesProfile, chat: ActiveChat, connection: String = "c1") {
            val current = g == generation
            if (current) {
                set(state.copy(section = MainSection.CHAT, activeChat = chat, loading = false))
                reconcile() // the open is over
            }
            change.talk.settle(g, profile.name, connection, current && state.activeConnectionId.orEmpty() == connection)?.let { sent += it }
        }

        fun failOpenBot(g: Long, profile: HermesProfile, connection: String = "c1") {
            if (g == generation) { set(state.copy(loading = false, error = "boom")); reconcile() }
            change.talk.settle(g, profile.name, connection, current = false)
        }

        fun openBot(profile: HermesProfile, chat: ActiveChat) = landOpenBot(startOpenBot(profile), profile, chat)

        /** openSession (also a push tap naming a chat). */
        fun openSession(chat: ActiveChat) {
            val g = navigate(); router.beginOpen()
            if (g == generation) { set(state.copy(section = MainSection.CHAT, activeChat = chat)); reconcile() }
        }

        /** newWorkspaceChat ("New chat"). */
        fun newChat(): Long = navigate()
        fun landNewChat(g: Long) { if (g == generation) set(state.copy(section = MainSection.CHAT, activeChat = plainChat)) }

        /** branchSession / branchActiveChat. */
        fun branch() {
            val g = navigate()
            set(state.copy(loading = true, section = MainSection.CHAT))
            if (g == generation) set(state.copy(activeChat = branchChat, loading = false))
        }

        /** openGroup (a channel row, a room push, a created room). */
        fun openGroup() { navigate(); set(state.copy(activeGroup = ActiveGroupRoom(room = room))) }
        fun closeGroup() = set(state.copy(activeGroup = null))

        /** showChannels ("All channels" and back). */
        fun showChannels(open: Boolean) { if (open) navigate(); set(state.copy(channelsListOpen = open)) }

        /** switchSection (drawer, rail, bell, Kanban, back between pages). */
        fun switchSection(to: MainSection) = set(state.copy(section = to))

        /** closeActiveChat (back from a chat, rail "Chats"). */
        fun closeActiveChat(back: MainSection? = MainSection.BOTS) {
            navigate()
            set(state.copy(activeChat = null, section = MainSection.CHAT))
            if (back != null) switchSection(back)
        }

        /** disconnect + connect (switchConnection, also a push tap for another connection). */
        fun switchConnection(to: String) {
            epoch += 1
            navigate()
            set(HermesUiState(activeConnectionId = to, profiles = listOf(ezra, otto)))
            dialog.clear() // the fresh state has no dialog
        }
    }

    // ---- P1: the "Talk to Ezra" note -------------------------------------------------------------------------

    /** Records on the bots page and starts Ezra's open; returns (vm, generation of that open). */
    private fun talking(): Pair<Vm, Long> { val vm = Vm(); return vm to vm.startOpenBot(ezra, talk = "note") }

    private fun assertNoteDiscarded(vm: Vm, g: Long) {
        assertEquals(listOf("note"), vm.discarded)
        vm.landOpenBot(g, ezra, ezraChat) // the original open returns late
        assertEquals(emptyList<String>(), vm.sent)
        assertFalse(vm.change.talk.waiting)
    }

    @Test fun talkWithNothingElseSendsTheNote() {
        val (vm, g) = talking()
        vm.landOpenBot(g, ezra, ezraChat)
        assertEquals(listOf("note"), vm.sent)
        assertTrue(vm.discarded.isEmpty())
    }

    @Test fun talkThenNewChatDiscards() { val (vm, g) = talking(); vm.landNewChat(vm.newChat()); assertNoteDiscarded(vm, g) }

    @Test fun talkThenNewChatStillLoadingDiscards() { val (vm, g) = talking(); vm.newChat(); assertNoteDiscarded(vm, g) }

    @Test fun talkThenBranchDiscards() { val (vm, g) = talking(); vm.branch(); assertNoteDiscarded(vm, g) }

    @Test fun talkThenChannelRowDiscards() {
        val (vm, g) = talking(); vm.openGroup(); assertNoteDiscarded(vm, g)
        assertEquals("room-1", vm.state.activeGroup?.room?.roomId) // the late open did not replace the channel
        assertEquals(MainSection.BOTS, vm.state.section)
    }

    @Test fun talkThenAllChannelsDiscards() {
        val (vm, g) = talking(); vm.showChannels(true); assertNoteDiscarded(vm, g)
        assertTrue(vm.state.channelsListOpen)
        assertEquals(MainSection.BOTS, vm.state.section)
    }

    @Test fun talkThenDrawerOrRailOrSectionChangeDiscards() {
        for (page in listOf(MainSection.HOME, MainSection.CRON, MainSection.FILES, MainSection.MORE, MainSection.NOTIFICATIONS, MainSection.KANBAN)) {
            val (vm, g) = talking(); vm.switchSection(page); assertNoteDiscarded(vm, g)
        }
    }

    @Test fun talkThenDrawerBackToTheSamePageKeepsIt() {
        // Tapping Bots while on the bots page changes nothing on screen: no navigation, the note still goes to Ezra.
        val (vm, g) = talking(); vm.switchSection(MainSection.BOTS)
        vm.landOpenBot(g, ezra, ezraChat)
        assertEquals(listOf("note"), vm.sent)
    }

    @Test fun talkThenConnectionChangeDiscards() {
        val (vm, g) = talking(); vm.switchConnection("c2")
        assertEquals(listOf("note"), vm.discarded)
        vm.landOpenBot(g, ezra, ezraChat, connection = "c1")
        assertTrue(vm.sent.isEmpty())
    }

    @Test fun talkThenPushTapOpeningAChatDiscards() { val (vm, g) = talking(); vm.openSession(plainChat); assertNoteDiscarded(vm, g) }

    @Test fun talkThenPushTapOpeningAnotherBotDiscards() {
        val (vm, g) = talking(); val og = vm.startOpenBot(otto)
        assertEquals(listOf("note"), vm.discarded)
        vm.landOpenBot(og, otto, ottoChat)
        assertNoteDiscarded(vm, g)
    }

    @Test fun talkThenPushTapOpeningARoomDiscards() {
        val (vm, g) = talking(); vm.switchSection(MainSection.BOTS); vm.openGroup(); assertNoteDiscarded(vm, g)
    }

    @Test fun talkThenFailedOpenDiscards() {
        val (vm, g) = talking(); vm.failOpenBot(g, ezra)
        assertEquals(listOf("note"), vm.discarded)
        vm.openBot(ezra, ezraChat) // a later tap on Ezra sends nothing
        assertTrue(vm.sent.isEmpty())
    }

    @Test fun talkThenStaleOpenDiscards() {
        val (vm, g) = talking(); val og = vm.startOpenBot(otto) // Ezra's open is now stale
        vm.landOpenBot(g, ezra, ezraChat) // lands stale: no screen change, nothing sent
        assertEquals(MainSection.BOTS, vm.state.section)
        assertEquals(listOf("note"), vm.discarded); assertTrue(vm.sent.isEmpty())
        vm.landOpenBot(og, otto, ottoChat); assertTrue(vm.sent.isEmpty())
    }

    @Test fun talkThenBackDiscards() {
        // Back from the bots page to the previous page (system back).
        val (vm, g) = talking(); vm.switchSection(MainSection.HOME); assertNoteDiscarded(vm, g)
        // Back out of a retained chat (closeActiveChat) while the note waits.
        val vm2 = Vm(); vm2.set(vm2.state.copy(activeChat = ottoChat)) // a chat kept behind the bots page
        val g2 = vm2.startOpenBot(ezra, talk = "note"); vm2.closeActiveChat(); assertNoteDiscarded(vm2, g2)
        // Back from the channels list.
        val vm3 = Vm(); vm3.showChannels(true); val g3 = vm3.startOpenBot(ezra, talk = "note"); vm3.showChannels(false)
        assertNoteDiscarded(vm3, g3)
    }

    // ---- Search palette (Shomer t_4b71caa6): full-screen Search is a change of what is visible ------------------

    @Test fun talkThenSearchOpensDiscards() {
        val (vm, g) = talking(); vm.showSearch(true)
        assertEquals(listOf("note"), vm.discarded) // at once, not when Search closes
        assertNoteDiscarded(vm, g)
    }

    @Test fun talkThenSearchOpenedAndDismissedStillDiscards() {
        val (vm, g) = talking(); vm.showSearch(true); vm.showSearch(false); assertNoteDiscarded(vm, g)
    }

    @Test fun talkThenSearchResultDiscards() {
        // A palette result closes Search first, then runs (open a chat, a page, New chat).
        val (vm, g) = talking(); vm.showSearch(true); vm.showSearch(false); vm.openSession(plainChat); assertNoteDiscarded(vm, g)
        val (vm2, g2) = talking(); vm2.showSearch(true); vm2.showSearch(false); vm2.switchSection(MainSection.CRON); assertNoteDiscarded(vm2, g2)
        val (vm3, g3) = talking(); vm3.showSearch(true); vm3.showSearch(false); vm3.landNewChat(vm3.newChat()); assertNoteDiscarded(vm3, g3)
    }

    @Test fun searchCountsAsAChangeOfView() {
        val vm = Vm(); vm.openBot(ezra, ezraChat)
        val shown = vm.view()
        assertTrue(shown.surface != null)
        vm.showSearch(true)
        assertTrue(vm.view() != shown)
        assertNull("no bot chat is on screen behind Search", vm.view().surface)
        assertFalse(vm.view().showsBotChatOf("ezra", "c1"))
        vm.showSearch(false)
        assertEquals(shown, vm.view())
    }

    @Test fun pushTapWhileSearchIsOpenClosesSearch() {
        val vm = Vm(); vm.showSearch(true); vm.openSession(plainChat)
        assertFalse(vm.state.searchOpen)
        assertEquals(MainSection.CHAT, vm.state.section)
    }

    @Test fun cardThenSearchGoesToDialogAndBackWhenSearchCloses() {
        val vm = waitingCard(); vm.showSearch(true); assertInDialog(vm)
        vm.showSearch(false)
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    @Test fun approvalArrivingWhileSearchIsOpenIsADialogAndBecomesACardWhenSearchCloses() {
        val vm = Vm(); vm.openBot(ezra, ezraChat); vm.showSearch(true)
        assertEquals(Route.DIALOG, vm.request(approval("r1")))
        assertInDialog(vm)
        vm.showSearch(false)
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    @Test fun cardThenSearchResultOpeningAnotherChatGoesToDialogAndBack() {
        val vm = waitingCard(); vm.showSearch(true); vm.showSearch(false); vm.openSession(plainChat)
        assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun pushTapToTheSameBotChatWhileSearchIsOpenBringsTheCardBack() {
        val vm = waitingCard(); vm.showSearch(true); assertInDialog(vm)
        vm.openSession(ezraChat.copy(botChat = false))
        assertFalse(vm.state.searchOpen)
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    // ---- P4: Bot details over the bot chat ----------------------------------------------------------------------

    @Test fun detailsCountsAsAChangeOfView() {
        val vm = Vm(); vm.openBot(ezra, ezraChat)
        val shown = vm.view()
        vm.openDetails("ezra")
        assertEquals("ezra", vm.state.botDetails?.profile)
        assertTrue(vm.view() != shown)
        assertNull("no bot chat is on screen behind Bot details", vm.view().surface)
        assertFalse(vm.view().showsBotChatOf("ezra", "c1"))
        vm.closeDetails()
        assertEquals(shown, vm.view())
    }

    @Test fun detailsOpensOnlyOverThatBotsChat() {
        val vm = Vm(); vm.openDetails("ezra"); assertNull(vm.state.botDetails) // no chat
        vm.openSession(plainChat); vm.openDetails("ezra"); assertNull(vm.state.botDetails) // not a bot chat
        vm.openBot(ezra, ezraChat); vm.openDetails("otto"); assertNull(vm.state.botDetails) // another bot
    }

    @Test fun cardThenDetailsGoesToDialogAndBackWhenDetailsCloses() {
        val vm = waitingCard(); vm.openDetails("ezra"); assertInDialog(vm)
        vm.closeDetails()
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    @Test fun approvalArrivingWhileDetailsIsOpenIsADialogAndBecomesACardWhenItCloses() {
        val vm = Vm(); vm.openBot(ezra, ezraChat); vm.openDetails("ezra")
        assertEquals(Route.DIALOG, vm.request(approval("r1")))
        assertInDialog(vm)
        vm.closeDetails()
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    @Test fun pushTapWhileDetailsIsOpenClosesItAndLandsOnTheChat() {
        val vm = waitingCard(); vm.openDetails("ezra"); vm.openSession(plainChat)
        assertNull(vm.state.botDetails); assertEquals(MainSection.CHAT, vm.state.section)
        assertInDialog(vm); assertBackToCard(vm)
        // A push tap to Ezra's own chat closes details too, and the card is back on screen.
        val vm2 = waitingCard(); vm2.openDetails("ezra"); vm2.openSession(ezraChat.copy(botChat = false))
        assertNull(vm2.state.botDetails)
        assertEquals(setOf("r1"), vm2.cards); assertTrue(vm2.dialog.isEmpty())
    }

    @Test fun detailsClosesWhenThePageOrTheChatChanges() {
        val vm = waitingCard(); vm.openDetails("ezra"); vm.switchSection(MainSection.BOTS)
        assertNull("leaving the Chat page closes Bot details", vm.state.botDetails); assertInDialog(vm)
        vm.switchSection(MainSection.CHAT) // the rail back: the chat, not details
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
        val vm2 = waitingCard(); vm2.openDetails("ezra"); vm2.branch()
        assertNull(vm2.state.botDetails); assertInDialog(vm2)
        val vm3 = waitingCard(); vm3.openDetails("ezra"); vm3.closeActiveChat()
        assertNull(vm3.state.botDetails); assertInDialog(vm3)
        val vm4 = waitingCard(); vm4.openDetails("ezra"); vm4.switchConnection("c2")
        assertNull(vm4.state.botDetails); assertTrue(vm4.cards.isEmpty()); assertTrue(vm4.dialog.isEmpty())
    }

    @Test fun searchOverDetailsKeepsTheChatHidden() {
        val vm = waitingCard(); vm.openDetails("ezra"); vm.showSearch(true); assertInDialog(vm)
        vm.showSearch(false); assertInDialog(vm) // details is still over the chat
        vm.closeDetails(); assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    // ---- P2: a waiting approval card --------------------------------------------------------------------------

    /** Ezra's bot chat on screen with approval r1 waiting as a card. */
    private fun waitingCard(): Vm {
        val vm = Vm()
        vm.openBot(ezra, ezraChat)
        assertEquals(Route.CARD, vm.request(approval("r1")))
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
        return vm
    }

    private fun assertInDialog(vm: Vm) {
        assertTrue("r1 must not stay an invisible card", vm.cards.isEmpty())
        assertEquals(listOf("r1"), vm.dialog.map { it.requestId })
    }

    private fun assertBackToCard(vm: Vm) {
        vm.openBot(ezra, ezraChat)
        assertEquals(setOf("r1"), vm.cards)
        assertTrue(vm.dialog.isEmpty())
    }

    @Test fun cardThenNewChatGoesToDialogAndBack() {
        val vm = waitingCard(); val g = vm.newChat()
        assertEquals(setOf("r1"), vm.cards) // still on screen while the new chat loads
        vm.landNewChat(g); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun cardThenBranchGoesToDialogAndBack() { val vm = waitingCard(); vm.branch(); assertInDialog(vm); assertBackToCard(vm) }

    @Test fun cardThenChannelGoesToDialogAndBack() {
        val vm = waitingCard(); vm.closeActiveChat(); vm.openGroup(); assertInDialog(vm)
        vm.closeGroup(); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun cardThenAllChannelsGoesToDialogAndBack() {
        val vm = waitingCard(); vm.switchSection(MainSection.BOTS); vm.showChannels(true); assertInDialog(vm)
        vm.showChannels(false); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun cardThenDrawerOrRailOrSectionChangeGoesToDialogAndBack() {
        for (page in listOf(MainSection.BOTS, MainSection.HOME, MainSection.CRON, MainSection.FILES, MainSection.MORE, MainSection.NOTIFICATIONS)) {
            val vm = waitingCard(); vm.switchSection(page); assertInDialog(vm)
            vm.switchSection(MainSection.CHAT) // the rail back to the chat that was kept
            assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
        }
    }

    @Test fun cardThenConnectionChangeIsDroppedNotAnsweredOnTheNewConnection() {
        val vm = waitingCard(); vm.switchConnection("c2")
        assertTrue(vm.cards.isEmpty()); assertTrue(vm.dialog.isEmpty())
        assertTrue(vm.router.pendingIds.isEmpty())
        vm.openBot(ezra, ezraChat) // the old connection's request never comes back as a card
        assertTrue(vm.cards.isEmpty())
    }

    @Test fun cardThenPushTapOpeningAnotherChatGoesToDialogAndBack() {
        val vm = waitingCard(); vm.openSession(plainChat); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun cardThenPushTapOpeningAnotherBotGoesToDialogAndBack() {
        val vm = waitingCard(); vm.openBot(otto, ottoChat); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun pushTapOpeningTheSameBotChatAsAPlainSessionKeepsTheCard() {
        // A push names Ezra's canonical chat; it opens as a plain session but is still her bot chat.
        val vm = waitingCard(); vm.openSession(ezraChat.copy(botChat = false))
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
    }

    @Test fun cardThenFailedOpenOfAnotherBotStaysACardWhileItsChatIsOnScreen() {
        val vm = waitingCard(); val g = vm.startOpenBot(otto); vm.failOpenBot(g, otto)
        assertEquals(setOf("r1"), vm.cards); assertTrue(vm.dialog.isEmpty())
        vm.switchSection(MainSection.BOTS); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun cardThenStaleOpenGoesToDialogForTheChatThatWon() {
        val vm = waitingCard(); val og = vm.startOpenBot(otto); val g = vm.newChat()
        vm.landOpenBot(og, otto, ottoChat) // stale: ignored
        assertEquals(setOf("r1"), vm.cards)
        vm.landNewChat(g); assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun cardThenBackGoesToDialogAndBack() {
        val vm = waitingCard(); vm.closeActiveChat(); assertInDialog(vm); assertBackToCard(vm)
        val vm2 = waitingCard(); vm2.closeActiveChat(back = null); assertInDialog(vm2); assertBackToCard(vm2)
    }

    @Test fun approvalArrivingWhileAnotherPageShowsIsADialogAndBecomesACardOnReturn() {
        val vm = Vm(); vm.openBot(ezra, ezraChat); vm.switchSection(MainSection.BOTS)
        assertEquals(Route.DIALOG, vm.request(approval("r1")))
        assertInDialog(vm); assertBackToCard(vm)
    }

    @Test fun reconcileNeverDuplicatesARequest() {
        val vm = waitingCard()
        repeat(3) { vm.switchSection(MainSection.HOME); vm.switchSection(MainSection.CHAT) }
        vm.newChat().also { vm.landNewChat(it) }
        vm.request(approval("r1")) // a replay of the same request
        assertEquals(listOf("r1"), vm.dialog.map { it.requestId }); assertTrue(vm.cards.isEmpty())
        assertBackToCard(vm)
        assertNull(vm.dialog.firstOrNull())
    }
}
