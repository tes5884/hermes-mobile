package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ApprovalCards
import com.nousresearch.hermesmobile.data.ApprovalCards.State
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCardsTest {
    private fun params(vararg choices: String, permanent: Boolean = true, smartDenied: Boolean = false) = JSONObject()
        .put("choices", JSONArray(choices.toList())).put("allow_permanent", permanent).put("smart_denied", smartDenied)
        .put("description", "Restart TEQhub so the new ticket page goes live\nTEQhub will be offline for about 20 seconds.")
        .put("command", "docker compose up -d --force-recreate app").put("pattern_key", "restart a running service")

    @Test fun neverOffersSessionAndAlwaysOnlyWhenPermanent() {
        assertEquals(listOf("once", "always", "deny"), ApprovalCards.choices(params("once", "session", "always", "deny")))
        assertEquals(listOf("once", "deny"), ApprovalCards.choices(params("once", "session", "always", "deny", permanent = false)))
        assertEquals(listOf("once", "deny"), ApprovalCards.choices(params("once", "session", "deny")))
        assertEquals(listOf("once", "deny"), ApprovalCards.choices(params("once", "always", "deny", smartDenied = true)))
        assertEquals(listOf("once", "deny"), ApprovalCards.choices(JSONObject()))
    }

    @Test fun cardFromRequestSplitsTitleEffectReason() {
        val c = ApprovalCards.fromRequest("r1", params("once", "always", "deny"), 1000L)
        assertEquals("Restart TEQhub so the new ticket page goes live", c.title)
        assertEquals("TEQhub will be offline for about 20 seconds.", c.effect)
        assertEquals("restart a running service", c.reason)
        assertTrue(c.allowAlways)
        assertEquals(State.PENDING, c.state)
    }

    @Test fun resolveWithdrawAndReplay() {
        val c = ApprovalCards.fromRequest("r1", params("once", "deny"), 1000L)
        var cards = ApprovalCards.upsert(emptyList(), c)
        // A replay of the same open request keeps its first time and stays one card.
        cards = ApprovalCards.upsert(cards, c.copy(createdAtMs = 5000L))
        assertEquals(listOf(1000L), cards.map { it.createdAtMs })
        assertEquals(State.DENIED, ApprovalCards.resolve(cards, "r1", "deny", 2000L).single().state)
        assertEquals(State.ONCE, ApprovalCards.resolve(cards, "r1", "once", 2000L).single().state)
        assertTrue(ApprovalCards.withdraw(cards, "r1").isEmpty())
        // An answered card is not withdrawn by a later cancel.
        val answered = ApprovalCards.resolve(cards, "r1", "always", 2000L)
        assertEquals(1, ApprovalCards.withdraw(answered, "r1").size)
    }

    @Test fun anchorsByTime() {
        val a = ApprovalCards.Card("a", "x", "", createdAtMs = 1_500_000L, state = State.ONCE)
        val b = ApprovalCards.Card("b", "y", "", createdAtMs = 100L, state = State.DENIED)
        val p = ApprovalCards.Card("p", "z", "", createdAtMs = 9_000_000L)
        // Row times in seconds (server) with a day separator (0) between.
        val at = ApprovalCards.anchors(listOf(a, b, p), listOf(1000L, 0L, 1400L, 1600L))
        assertEquals(2, at["a"]) // after the 1400 s row, before the 1600 s one
        assertEquals(-1, at["b"]) // older than every loaded row: not shown
        assertFalse(at.containsKey("p")) // pending cards are not anchored
    }

    @Test fun storeRoundTripKeepsAnswers() {
        val cards = listOf(
            ApprovalCards.Card("a", "Delete snapshots", "rm -rf /x", "recursive delete", true, 10L, State.DENIED, 20L),
            ApprovalCards.Card("b", "Restart", "", createdAtMs = 30L, state = State.ALWAYS, resolvedAtMs = 40L)
        )
        assertEquals(cards, ApprovalCards.decode(ApprovalCards.encode(cards)))
        assertTrue(ApprovalCards.decode("not json").isEmpty())
        assertTrue(ApprovalCards.decode(null).isEmpty())
    }
}
