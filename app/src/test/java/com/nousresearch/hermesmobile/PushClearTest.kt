package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.PushClear
import com.nousresearch.hermesmobile.push.PushClear.Shown
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Opening a chat in the app clears that chat's notices from the shade (Tzvi 2026-11-16). */
class PushClearTest {
    private val ezraGroup = "hermes_bot_home|ezra"
    private fun notice(tag: String, session: String, connection: String = "home", profile: String = "ezra",
                       group: String = "hermes_bot_$connection|$profile") = Shown(tag, group, false, session, connection, profile)
    private fun summary(connection: String = "home", profile: String = "ezra") =
        Shown("summary:$connection|$profile", "hermes_bot_$connection|$profile", true, "", "", "")

    @Test fun clearsOnlyTheOpenedChatAndRecountsTheBotSummary() {
        // Ezra's Bot Chat has two notices, one of her routines posted in another session: only the chat's two go.
        val shown = listOf(notice("e1", "s-chat"), notice("e2", "s-chat"), notice("e3", "s-routine"), summary(),
            notice("d1", "s-della", profile = "della"), summary(profile = "della"))
        val plan = PushClear.plan(shown, emptyMap(), "s-chat", "home")
        assertEquals(listOf("e1", "e2"), plan.cancel)
        assertEquals(listOf(PushClear.GroupLeft(ezraGroup, "summary:home|ezra", 1, "home", "ezra")), plan.groups)
    }

    @Test fun lastNoticeOfTheBotDropsItsSummary() {
        val plan = PushClear.plan(listOf(notice("e1", "s-chat"), summary()), emptyMap(), "s-chat", "home")
        assertEquals(listOf("e1"), plan.cancel)
        assertEquals(0, plan.groups.single().left)
        assertEquals("summary:home|ezra", plan.groups.single().summaryTag)
    }

    @Test fun sameSessionIdOnAnotherServerStays() {
        val shown = listOf(notice("h1", "s-1", connection = "home"), notice("b1", "s-1", connection = "bilbo"))
        assertEquals(listOf("h1"), PushClear.plan(shown, emptyMap(), "s-1", "home").cancel)
    }

    @Test fun noticePostedBeforeThisVersionIsFoundThroughTheInbox() {
        // No extras on it (posted by vc89): the push inbox knows its chat.
        val old = Shown("e9", ezraGroup, false, "", "", "")
        val stored = mapOf("e9" to PushClear.Stored("s-chat", "home", "ezra", "turn_completed"),
            "e8" to PushClear.Stored("s-other", "home", "ezra", "turn_completed"))
        val plan = PushClear.plan(listOf(old, Shown("e8", ezraGroup, false, "", "", ""), summary()), stored, "s-chat", "home")
        assertEquals(listOf("e9"), plan.cancel)
        // Shomer Low (t_085ba172): the recount needs the bot and server, taken from the inbox when extras are missing.
        assertEquals(PushClear.GroupLeft(ezraGroup, "summary:home|ezra", 1, "home", "ezra"), plan.groups.single())
    }

    @Test fun approvalStaysUntilTheServerResolvesIt() {
        // Shomer Medium (t_085ba172): back in the app before the gateway replays the card → the approval notice must
        // stay, or nothing alerts him before it times out. approval_resolved withdraws it (PushDelivery.handle).
        val approval = notice("a1", "s-chat").copy(type = "approval_required")
        val answer = notice("e1", "s-chat").copy(type = "turn_completed")
        val plan = PushClear.plan(listOf(approval, answer, summary()), emptyMap(), "s-chat", "home")
        assertEquals(listOf("e1"), plan.cancel)
        assertEquals(1, plan.groups.single().left)
        // The same for a vc89 approval known only through the inbox.
        val oldApproval = Shown("a2", ezraGroup, false, "", "", "")
        val stored = mapOf("a2" to PushClear.Stored("s-chat", "home", "ezra", "approval_required"))
        assertTrue(PushClear.plan(listOf(oldApproval), stored, "s-chat", "home").cancel.isEmpty())
    }

    @Test fun placeholderWithNoKnownChatStays() {
        // The generic "Hermes" notice before content arrives: no session anywhere, so it is not guessed at.
        val generic = Shown("e7", "", false, "", "", "")
        assertTrue(PushClear.plan(listOf(generic), emptyMap(), "s-chat", "home").cancel.isEmpty())
    }

    @Test fun channelNoticesClearWhenTheChannelIsOpened() {
        val shown = listOf(notice("r1", "room:abc", profile = "mason"), notice("r2", "room:xyz", profile = "mason"))
        assertEquals(listOf("r1"), PushClear.plan(shown, emptyMap(), "room:abc", "home").cancel)
    }

    @Test fun noOpenChatClearsNothingAndSummariesAreNeverMatchedDirectly() {
        assertTrue(PushClear.plan(listOf(notice("e1", "s-chat")), emptyMap(), "", "home").cancel.isEmpty())
        val oddSummary = Shown("summary:home|ezra", ezraGroup, true, "s-chat", "home", "ezra")
        assertTrue(PushClear.plan(listOf(oddSummary), emptyMap(), "s-chat", "home").cancel.isEmpty())
    }

    @Test fun wiring() {
        val app = File("src/main/java/com/nousresearch/hermesmobile/HermesMobileApp.kt").readText()
        assertTrue("chat open / app resume must clear that chat's notices",
            Regex("""Lifecycle\.Event\.ON_RESUME[\s\S]{0,300}PushClear\.clear\(appContext, openChat, openConnection\)""").containsMatchIn(app))
        val delivery = File("src/main/java/com/nousresearch/hermesmobile/push/PushDelivery.kt").readText()
        assertTrue("every notice records its chat", delivery.contains("putString(PushClear.EXTRA_SESSION, event?.optString(\"session_id\")"))
        assertTrue("every notice records its type", delivery.contains("putString(PushClear.EXTRA_TYPE, event?.optString(\"event_type\")"))
        assertTrue("a resolved approval is still withdrawn by the server", delivery.contains("\"approval_resolved\""))
    }
}
