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

    @Test fun waitingApprovalSitsUnderTheAskingBotsNewestReplyElseAtTheBottom() {
        val events = listOf(bot(1, "mason"), bot(2, "nova"), bot(3, "mason"), user(4))
        val p = ChannelView.place(events, listOf(approval("mason", "r1"), approval("shomer", "r2")), emptyList()) { 0L }
        assertEquals(listOf("r1"), p.under[3L]?.map { it.key })
        assertEquals(listOf("r2"), p.bottom.map { it.key })
        val card = (p.under.getValue(3L).single() as ChannelView.Item.Pending).card
        assertFalse("rooms never offer Always", card.allowAlways)
        assertEquals("Push the draft-saving branch to GitHub", card.title)
        assertEquals("Opens a pull request. Nothing goes live.", card.effect)
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
        assertTrue(p.under.getValue(1L).any { it is ChannelView.Item.Pending })
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
