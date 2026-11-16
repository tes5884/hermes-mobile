package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ApprovalCards
import com.nousresearch.hermesmobile.data.ChannelView
import com.nousresearch.hermesmobile.data.GroupActor
import com.nousresearch.hermesmobile.data.GroupDriverStatus
import com.nousresearch.hermesmobile.data.GroupEvent
import com.nousresearch.hermesmobile.data.GroupMember
import com.nousresearch.hermesmobile.data.GroupMemberActivity
import com.nousresearch.hermesmobile.data.GroupPendingAction
import com.nousresearch.hermesmobile.data.RoomRow
import com.nousresearch.hermesmobile.data.RoomRows
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelViewTest {
    private val t0 = 1_794_780_000.0 // epoch seconds

    private fun bot(seq: Long, member: String, at: Double = t0 + seq * 10, text: String = "hi $seq") = GroupEvent(
        roomId = "r", seq = seq, eventId = "e$seq", kind = "message.member",
        actor = GroupActor("member", member), payload = JSONObject().put("member_id", member).put("text", text), createdAt = at
    )

    private fun user(seq: Long, at: Double = t0 + seq * 10, text: String = "you $seq") = GroupEvent(
        roomId = "r", seq = seq, eventId = "e$seq", kind = "message.user",
        actor = GroupActor("user", "tzvi"), payload = JSONObject().put("text", text), createdAt = at
    )

    private fun approval(member: String, request: String, command: String = "git push origin drafts") = GroupPendingAction.fromJson(JSONObject(
        """{"kind":"approval","member_id":"$member","task_id":"t-$member","execution_generation":1,"request_id":"$request",
           "approval":{"request_id":"$request","description":"Push the draft-saving branch to GitHub\nOpens a pull request. Nothing goes live.",
           "command":"$command","choices":["once","deny"]}}"""
    ))

    @Test fun headerNamesFirstThreeAndCountsTheRest() {
        val members = listOf("Mason", "Nova", "Shomer", "Kit", "Ezra").map { GroupMember(it.lowercase(), it.lowercase(), it.lowercase(), displayName = it) }
        assertEquals(listOf("Mason", "Nova", "Shomer") to 2, ChannelView.headerNames(members))
        assertEquals(listOf("Mason", "Nova") to 0, ChannelView.headerNames(members.take(2)))
    }

    @Test fun quickFollowUpBySameBotDropsTheHeaderButNotAcrossOthersOrGaps() {
        val events = listOf(
            bot(1, "mason"),
            bot(2, "mason"),                     // 10 s later: same bot, header dropped
            bot(3, "nova"),                      // another bot: header
            bot(4, "mason"),                     // back to mason after nova: header
            bot(5, "mason", at = t0 + 40 + 600), // 10 min later: header
            user(6),
            bot(7, "mason")                      // after his message: header
        )
        assertEquals(setOf(2L), ChannelView.hiddenAuthors(RoomRows.build(events)))
    }

    @Test fun newMessagesDividerOrUnknownTimeKeepsTheHeader() {
        val events = listOf(bot(1, "mason"), bot(2, "mason"), bot(3, "mason", at = 0.0))
        assertEquals(emptySet<Long>(), ChannelView.hiddenAuthors(RoomRows.build(events, newMessagesBeforeSeq = 2)))
        assertFalse(3L in ChannelView.hiddenAuthors(RoomRows.build(events)))
    }

    @Test fun waitingApprovalAlwaysSitsAtTheLiveBottom() {
        val events = listOf(bot(1, "mason"), bot(2, "nova"), bot(3, "mason"), user(4))
        val p = ChannelView.place(events, listOf(approval("mason", "r1"), approval("shomer", "r2")), emptyList()) { 0L }
        assertTrue("nothing waiting is tucked under an old reply", p.under.isEmpty())
        assertEquals(listOf("r1", "r2"), p.bottom.map { it.key })
        val card = (p.bottom.first() as ChannelView.Item.Pending).card
        assertFalse("rooms never offer Always", card.allowAlways)
        assertEquals("Push the draft-saving branch to GitHub", card.title)
        assertEquals("Opens a pull request. Nothing goes live.", card.effect)
    }

    /**
     * Shomer t_86879164: Mason replied near the start, then Nova and he traded 100 messages, then Mason asks for an
     * approval. The waiting card must be what the screen shows at the live bottom (index 0 side of the reverse
     * list), not under Mason's reply 100 rows up; scrolled away, a banner names Mason and jumps to it; on screen
     * (or answered) the banner goes. An answered card from earlier keeps its place in history.
     */
    @Test fun longHistoryOldAuthorReplyApprovalIsAtTheLiveBottomWithABannerWhenOffScreen() {
        val history = mutableListOf(bot(1, "mason"))
        for (s in 2L..101L) history.add(if (s % 2 == 0L) user(s) else bot(s, "nova"))
        // An approval he answered back near Mason's reply stays there.
        val earlierAt = ((t0 + 15) * 1000).toLong()
        val answered = ApprovalCards.resolve(listOf(ChannelView.card(approval("mason", "old"), earlierAt)), "old", "once", earlierAt + 500)
        val rows = RoomRows.build(history)
        val p = ChannelView.place(history, listOf(approval("mason", "r-live")), answered) { 0L }
        val entries = ChannelView.liveEntries(typing = true, noteIds = listOf("n1"), placement = p, rows = rows)

        // What the list draws, newest first: typing, his pending note, the waiting card, then message 101.
        assertEquals(listOf("typing", "p:n1", "a:r-live", "e:101"), entries.take(4).map { it.key })
        assertTrue("the waiting card isn't also under Mason's old reply", p.under[1L].orEmpty().none { it is ChannelView.Item.Pending })
        assertEquals("the answered one stays in history", listOf("old"), p.under[1L]?.map { it.key })

        // At the live bottom (the room opens there), the card is on screen: no banner needed.
        val bottomWindow = entries.take(6).map { it.key }.toSet()
        assertEquals(null, ChannelView.approvalBanner(entries, bottomWindow))

        // Scrolled up into history (or opened at the unread divider): banner for Mason, jump lands on the card.
        val historyWindow = entries.subList(40, 52).map { it.key }.toSet()
        val banner = ChannelView.approvalBanner(entries, historyWindow)!!
        assertEquals(listOf("mason"), banner.memberIds)
        assertEquals(1, banner.count)
        assertEquals("a:r-live", entries[banner.jumpIndex].key)

        // Once he answers, the server drops the request and the banner goes even while he is up in history.
        val after = ChannelView.liveEntries(true, emptyList(), ChannelView.place(history, emptyList(), answered) { 0L }, rows)
        assertEquals(null, ChannelView.approvalBanner(after, historyWindow))
    }

    @Test fun bannerCountsEveryWaitingBotAndJumpsToTheNewestCard() {
        val events = listOf(bot(1, "mason"), user(2))
        val p = ChannelView.place(events, listOf(approval("mason", "r1"), approval("shomer", "r2")), emptyList()) { 0L }
        val entries = ChannelView.liveEntries(false, emptyList(), p, RoomRows.build(events))
        val b = ChannelView.approvalBanner(entries, setOf("e:1"))!!
        assertEquals(listOf("shomer", "mason"), b.memberIds)
        assertEquals(2, b.count)
        assertEquals(0, b.jumpIndex)
        assertEquals(null, ChannelView.approvalBanner(entries, setOf("a:r1")))
    }

    @Test fun aSliverAtTheEdgeIsNotOnScreen() {
        val laid = listOf(
            ChannelView.Laid("a:r1", offset = 980, size = 400), // 20 px peeking in at the far edge
            ChannelView.Laid("e:9", offset = 500, size = 400),  // fully inside
            ChannelView.Laid("e:8", offset = -300, size = 400), // 100 px of 400 inside, >= minPx
            ChannelView.Laid(7, offset = 0, size = 10)           // non-string keys are ignored
        )
        assertEquals(setOf("e:9", "e:8"), ChannelView.onScreenKeys(laid, viewportStart = 0, viewportEnd = 1000, minPx = 96))
    }

    @Test fun answeredCardIsPlacedByTimeAndHidesItsWaitingTwin() {
        val events = listOf(bot(1, "mason"), bot(2, "nova"), bot(3, "mason"))
        val answeredAt = ((t0 + 25) * 1000).toLong() // between seq 2 (t0+20) and seq 3 (t0+30)
        val answered = ApprovalCards.resolve(
            listOf(ChannelView.card(approval("mason", "r1"), answeredAt)), "r1", "once", answeredAt + 1000
        )
        val p = ChannelView.place(events, listOf(approval("mason", "r1")), answered) { 0L }
        assertEquals(listOf("r1"), p.under[2L]?.map { it.key })
        assertTrue(p.under[2L]!!.single() is ChannelView.Item.Answered)
        assertTrue("the waiting request is not shown twice", p.under[3L].isNullOrEmpty() && p.bottom.isEmpty())
    }

    @Test fun answeredCardOlderThanLoadedHistoryIsNotShown() {
        val events = listOf(bot(5, "mason"))
        val old = ApprovalCards.resolve(listOf(ChannelView.card(approval("mason", "r9"), 1_000L)), "r9", "deny", 2_000L)
        val p = ChannelView.place(events, emptyList(), old) { 0L }
        assertTrue(p.under.isEmpty() && p.bottom.isEmpty())
    }

    @Test fun approvalKeyFallsBackToTheAttempt() {
        val a = GroupPendingAction(kind = "approval", memberId = "m", taskId = "t7", executionGeneration = 3)
        assertEquals("t7#3", ChannelView.approvalKey(a))
        assertEquals("r1", ChannelView.approvalKey(approval("m", "r1")))
    }

    @Test fun requestWithoutIdIsNeverHiddenByAnEarlierAnswerInTheSameAttempt() {
        val events = listOf(bot(1, "mason"))
        val ask = GroupPendingAction.fromJson(JSONObject("""{"kind":"approval","member_id":"mason","task_id":"t1","execution_generation":1,
            "approval":{"description":"recursive delete","command":"rm -rf build","choices":["once","deny"]}}"""))
        val earlier = ApprovalCards.resolve(listOf(ChannelView.card(ask, 1_000L)), "t1#1", "once", 2_000L)
        val p = ChannelView.place(events, listOf(ask), earlier) { 0L }
        assertTrue(p.bottom.any { it is ChannelView.Item.Pending })
    }

    @Test fun typingLinesAreRunningBotsOnceEachWithTheirTool() {
        val rows = listOf(
            GroupMemberActivity("mason", "running"),
            GroupMemberActivity("nova", "queued"),
            GroupMemberActivity("mason", "running", tool = "terminal"),
            GroupMemberActivity("shomer", "stopping"),
            GroupMemberActivity("kit", "running")
        )
        assertEquals(listOf(ChannelView.Typing("mason", "terminal"), ChannelView.Typing("kit", "")), ChannelView.typing(rows))
    }

    @Test fun driverStatusReadsMemberActivityAndToleratesOldGateways() {
        val d = GroupDriverStatus.fromJson(JSONObject(
            """{"running":true,"working":true,"member_activity":[{"member_id":"mason","status":"running","started_at":1794780000.5,"tool":"terminal"},
               {"member_id":"nova","status":"running","tool":null},{"status":"running"}]}"""
        ))
        assertEquals(listOf(GroupMemberActivity("mason", "running", "terminal", 1794780000.5), GroupMemberActivity("nova", "running", "", 0.0)), d.memberActivity)
        assertEquals(emptyList<GroupMemberActivity>(), GroupDriverStatus.fromJson(JSONObject("""{"running":true}""")).memberActivity)
    }

    @Test fun mentionRangesColourOnlyTheChannelsMembers() {
        val text = "@Mason @Nova can the reply box keep my draft? cc @someone, mail a@b.com"
        val ranges = ChannelView.mentionRanges(text, listOf("mason", "nova"))
        assertEquals(listOf("@Mason", "@Nova"), ranges.map { text.substring(it.first, it.last + 1) })
        assertEquals(listOf("@nova"), ChannelView.mentionRanges("thanks @nova.", listOf("nova")).map { "thanks @nova.".substring(it.first, it.last + 1) })
        assertTrue(ChannelView.mentionRanges("no mentions", listOf("nova")).isEmpty())
    }

    @Test fun rowsHelperIgnoresNonEventRows() {
        val rows = listOf(RoomRow.NewMessages, RoomRow.Event(bot(1, "a")), RoomRow.Event(bot(2, "a")))
        assertEquals(setOf(2L), ChannelView.hiddenAuthors(rows))
    }
}
