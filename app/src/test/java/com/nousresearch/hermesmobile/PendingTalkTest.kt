package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.PendingTalkSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Shomer P1 review: a "Talk to …" note is sent only by the open it started; anything else discards it. */
class PendingTalkTest {
    private val discarded = mutableListOf<String>()
    private val slot = PendingTalkSlot<String> { discarded += it }

    @Test fun theOpenItStartedSendsIt() {
        slot.hold("ezra", generation = 5, connection = "c1", item = "note")
        assertEquals("note", slot.settle(5, "ezra", "c1", current = true))
        assertFalse(slot.waiting)
        assertEquals(emptyList<String>(), discarded)
    }

    @Test fun delayedOpenAfterCompetingNewChatDiscardsAndLaterOpenSendsNothing() {
        // Record → open #5 starts; New chat (#6) wins before #5 returns.
        slot.hold("ezra", 5, "c1", "note")
        assertNull(slot.settle(5, "ezra", "c1", current = false)) // #5 lands stale
        assertEquals(listOf("note"), discarded)
        // Later tap on Ezra: open #7 finds nothing to send.
        assertNull(slot.settle(7, "ezra", "c1", current = true))
        assertEquals(listOf("note"), discarded)
    }

    @Test fun competingNavigationDropsBeforeTheOpenReturns() {
        slot.hold("ezra", 5, "c1", "note")
        slot.drop() // e.g. New chat, section change, disconnect
        assertEquals(listOf("note"), discarded)
        assertNull(slot.settle(5, "ezra", "c1", current = true))
    }

    @Test fun laterOpenNeverConsumesAnEarlierOpensNote() {
        slot.hold("ezra", 5, "c1", "note")
        assertNull(slot.settle(6, "ezra", "c1", current = true))
        assertEquals(true, slot.waiting) // left for open #5 to settle (and discard if stale)
        assertNull(slot.settle(5, "ezra", "c1", current = false))
        assertEquals(listOf("note"), discarded)
    }

    @Test fun failedOrOtherBotOrOtherConnectionDiscards() {
        slot.hold("ezra", 1, "c1", "a"); assertNull(slot.settle(1, "ezra", "c1", current = false))
        slot.hold("ezra", 2, "c1", "b"); assertNull(slot.settle(2, "otto", "c1", current = true))
        slot.hold("ezra", 3, "c1", "c"); assertNull(slot.settle(3, "ezra", "c2", current = true))
        assertEquals(listOf("a", "b", "c"), discarded)
    }

    @Test fun newRecordingReplacesAWaitingOne() {
        slot.hold("ezra", 1, "c1", "old")
        slot.hold("ezra", 2, "c1", "new")
        assertEquals(listOf("old"), discarded)
        assertNull(slot.settle(1, "ezra", "c1", current = true))
        assertEquals("new", slot.settle(2, "ezra", "c1", current = true))
    }
}
