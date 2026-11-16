package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.PushGrouping
import org.junit.Assert.*
import org.junit.Test

class PushGroupingTest {
    @Test fun groupAndSummary() {
        assertEquals("hermes_bot_home|della", PushGrouping.groupKey("home", "della"))
        assertEquals("summary:home|della", PushGrouping.summaryTag("home", "della"))
        assertEquals("Della · 2", PushGrouping.summaryText("della", 2))
    }
    @Test fun sameBotNameOnTwoServersNeverSharesAGroup() {
        // Two servers both have "ezra": separate groups, summaries and merge slots, so a summary opens its own server.
        assertNotEquals(PushGrouping.groupKey("home", "ezra"), PushGrouping.groupKey("bilbo", "ezra"))
        assertNotEquals(PushGrouping.summaryTag("home", "ezra"), PushGrouping.summaryTag("bilbo", "ezra"))
        assertNotEquals(PushGrouping.conversationKey("home", "ezra"), PushGrouping.conversationKey("bilbo", "ezra"))
        // A file from bilbo's ezra never folds into home's ezra text, even inside the window.
        assertFalse(PushGrouping.shouldMerge(PushGrouping.conversationKey("home", "ezra"), 1000,
            PushGrouping.conversationKey("bilbo", "ezra"), 2000))
        assertTrue(PushGrouping.shouldMerge(PushGrouping.conversationKey("home", "ezra"), 1000,
            PushGrouping.conversationKey("home", "ezra"), 2000))
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
