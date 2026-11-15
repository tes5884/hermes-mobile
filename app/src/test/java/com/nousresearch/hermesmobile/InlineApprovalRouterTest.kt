package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.InlineApprovalRouter
import com.nousresearch.hermesmobile.data.InlineApprovalRouter.Route
import com.nousresearch.hermesmobile.data.ServerInteraction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shomer P2 review: the view model keeps the open bot chat while the drawer or rail shows another page. An approval for
 * that chat must then reach the dialog, never a card on a page nobody sees (it would time out unseen).
 *
 * [Screen] wires the router the way HermesViewModel does: requests go through onRequest(visible surface), and every
 * change of page or open chat (switchSection, closeActiveChat, an open finishing) runs reconcile on the dialog queue.
 */
class InlineApprovalRouterTest {
    private fun approval(id: String, session: String) = ServerInteraction(id, "approval", JSONObject().put("session_id", session))

    private class Screen {
        val router = InlineApprovalRouter<ServerInteraction>({ it.requestId }, { it.method }, { it.params.optString("session_id") })
        var section = MainSection.CHAT
        var chat: Pair<String, String>? = null // runtime id, stored id of the bot chat kept open
        val dialog = mutableListOf<ServerInteraction>()
        val cards = linkedSetOf<String>()

        fun surface() = chat?.let { InlineApprovalRouter.surfaceFor(section == MainSection.CHAT, botChat = true, readOnly = false, it.first, it.second) }

        fun request(r: ServerInteraction) = router.onRequest(r, surface()).also { route ->
            when (route) {
                Route.CARD -> { dialog.removeAll { it.requestId == r.requestId }; cards += r.requestId }
                Route.HOLD -> Unit
                Route.DIALOG -> if (dialog.none { it.requestId == r.requestId }) dialog += r
            }
        }

        fun reconcile() {
            val moves = router.reconcile(surface(), dialog.toList())
            cards.retainAll(router.pendingIds)
            moves.toCards.forEach { r -> dialog.removeAll { it.requestId == r.requestId }; cards += r.requestId }
            moves.toDialog.forEach { r -> if (dialog.none { it.requestId == r.requestId }) dialog += r }
        }

        fun openBotChat(runtime: String, stored: String) { router.beginOpen(); section = MainSection.CHAT; chat = runtime to stored; reconcile() }
        /** Drawer or rail to another page: switchSection keeps the chat. */
        fun switchSection(to: MainSection) { section = to; if (to != MainSection.CHAT || !router.opening) reconcile() }
        /** Rail "Chats" / back from a bot chat: closeActiveChat. */
        fun closeActiveChat() { chat = null; section = MainSection.CHAT; reconcile() }
    }

    @Test fun approvalForTheBotChatOnScreenIsACard() {
        val s = Screen(); s.openBotChat("rt-a", "st-a")
        assertEquals(Route.CARD, s.request(approval("r1", "rt-a")))
        assertEquals(setOf("r1"), s.cards); assertTrue(s.dialog.isEmpty())
    }

    @Test fun afterDrawerOrRailToAnotherPageTheRetainedChatsApprovalIsADialog() {
        for (page in listOf(MainSection.BOTS, MainSection.MORE, MainSection.HOME, MainSection.CRON, MainSection.FILES)) {
            val s = Screen(); s.openBotChat("rt-a", "st-a")
            s.switchSection(page) // chat A is still kept, but not on screen
            assertEquals(page.name, Route.DIALOG, s.request(approval("r1", "rt-a")))
            assertEquals(page.name, listOf("r1"), s.dialog.map { it.requestId })
            assertTrue(page.name, s.cards.isEmpty())
            assertTrue(page.name, s.router.pendingIds.isEmpty())
        }
    }

    @Test fun storedSessionIdMatchAlsoGoesToTheDialogOffTheChatPage() {
        val s = Screen(); s.openBotChat("rt-a", "st-a"); s.switchSection(MainSection.BOTS)
        assertEquals(Route.DIALOG, s.request(approval("r1", "st-a")))
    }

    @Test fun aWaitingCardMovesToTheDialogWhenThePageChangesAndBackWhenTheChatShowsAgain() {
        val s = Screen(); s.openBotChat("rt-a", "st-a")
        s.request(approval("r1", "rt-a"))
        s.switchSection(MainSection.SERVER_SETTINGS)
        assertEquals(listOf("r1"), s.dialog.map { it.requestId }); assertTrue(s.cards.isEmpty())
        s.switchSection(MainSection.CHAT) // back to the kept chat: the card comes back, no dialog copy
        assertEquals(setOf("r1"), s.cards); assertTrue(s.dialog.isEmpty())
        // Answering the card is possible again; it is gone from the router afterwards.
        assertEquals("r1", s.router.answer("r1")?.requestId)
        assertNull(s.router.answer("r1"))
    }

    @Test fun closingTheChatSendsItsWaitingCardToTheDialog() {
        val s = Screen(); s.openBotChat("rt-a", "st-a")
        s.request(approval("r1", "rt-a"))
        s.closeActiveChat()
        assertEquals(listOf("r1"), s.dialog.map { it.requestId }); assertTrue(s.cards.isEmpty())
        // Reopening adopts it from the dialog; the server's replay of the same request stays one card.
        s.router.beginOpen()
        assertEquals(Route.HOLD, s.request(approval("r1", "rt-a")))
        s.chat = "rt-a" to "st-a"; s.reconcile()
        assertEquals(setOf("r1"), s.cards); assertTrue(s.dialog.isEmpty())
    }

    @Test fun reopenWithANewRuntimeIdTakesTheReplayAndDropsTheDialogCopy() {
        val s = Screen(); s.openBotChat("rt-a", "st-a")
        s.request(approval("r1", "rt-a"))
        s.closeActiveChat()
        s.router.beginOpen()
        assertEquals(Route.HOLD, s.request(approval("r1", "rt-a2"))) // the resumed chat's replay of the same request
        s.chat = "rt-a2" to "st-a"; s.reconcile()
        assertEquals(setOf("r1"), s.cards); assertTrue(s.dialog.isEmpty())
    }

    @Test fun approvalsHeldWhileOpeningGoToTheDialogIfTheUserLeavesFirst() {
        val s = Screen(); s.section = MainSection.BOTS
        s.router.beginOpen() // a bot chat is opening from the bots page
        assertEquals(Route.HOLD, s.request(approval("r1", "rt-a")))
        s.switchSection(MainSection.MORE) // the user moves on before it lands
        assertEquals(listOf("r1"), s.dialog.map { it.requestId })
        assertTrue(!s.router.opening)
    }

    @Test fun otherSessionsAndOtherMethodsNeverBecomeCards() {
        val s = Screen(); s.openBotChat("rt-a", "st-a")
        assertEquals(Route.DIALOG, s.request(approval("r1", "rt-b")))
        assertEquals(Route.DIALOG, s.request(approval("r2", "")))
        assertEquals(Route.DIALOG, s.request(ServerInteraction("r3", "clarify", JSONObject().put("session_id", "rt-a"))))
        assertTrue(s.cards.isEmpty())
    }

    @Test fun surfaceOnlyForAWritableBotChatOnTheChatPage() {
        assertEquals(InlineApprovalRouter.Surface("r", "s"), InlineApprovalRouter.surfaceFor(true, true, false, "r", "s"))
        assertNull(InlineApprovalRouter.surfaceFor(false, true, false, "r", "s"))
        assertNull(InlineApprovalRouter.surfaceFor(true, false, false, "r", "s"))
        assertNull(InlineApprovalRouter.surfaceFor(true, true, true, "r", "s"))
    }

    @Test fun withdrawDropsCardsAndHeldCopies() {
        val s = Screen(); s.openBotChat("rt-a", "st-a")
        s.request(approval("r1", "rt-a"))
        assertTrue(s.router.withdraw("r1"))
        s.closeActiveChat(); s.router.beginOpen(); assertEquals(Route.HOLD, s.request(approval("r2", "rt-a")))
        assertTrue(!s.router.withdraw("r2")) // held, not a card: dropped silently
        s.reconcile()
        assertTrue(s.dialog.isEmpty())
    }
}
