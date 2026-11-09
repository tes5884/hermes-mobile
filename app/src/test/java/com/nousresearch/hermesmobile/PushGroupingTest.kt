package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.PushGrouping
import org.junit.Assert.*
import org.junit.Test

class PushGroupingTest {
    @Test fun groupAndSummary() {
        assertEquals("hermes_bot_della", PushGrouping.groupKey("della"))
        assertEquals("Della · 2", PushGrouping.summaryText("della", 2))
    }
    @Test fun mergeWindow() {
        assertTrue(PushGrouping.shouldMerge("della", 1000, "della", 6000))
        assertTrue(PushGrouping.shouldMerge("della", 1000, "della", 11000))
        assertFalse(PushGrouping.shouldMerge("della", 1000, "della", 11001))
        assertFalse(PushGrouping.shouldMerge("della", 1000, "chaim", 2000))
        assertFalse(PushGrouping.shouldMerge(null, 1000, "della", 2000))
        assertFalse(PushGrouping.shouldMerge("", 1000, "", 2000))
        assertFalse(PushGrouping.shouldMerge("della", 5000, "della", 1000))
    }
    @Test fun fileLabels() {
        assertEquals("Open PDF", PushGrouping.fileLabel("/x/Ledger.PDF"))
        assertEquals("Install", PushGrouping.fileLabel("hermes-vc71.apk"))
        assertEquals("Open file", PushGrouping.fileLabel("notes.txt"))
    }
}
