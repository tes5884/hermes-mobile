package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.PushMedia
import org.junit.Assert.assertEquals
import org.junit.Test

class PushMediaTest {
    @Test fun findsPathsAndHidesTheTag() {
        val body = "Morning Ledger\nMEDIA:/home/tzvi/staff/della/news/MorningLedger-2026-11-08.pdf"
        assertEquals(listOf("/home/tzvi/staff/della/news/MorningLedger-2026-11-08.pdf"), PushMedia.paths(body))
        assertEquals("Morning Ledger", PushMedia.text(body))
        assertEquals("MorningLedger-2026-11-08.pdf", PushMedia.name(PushMedia.paths(body).single()))
    }
    @Test fun plainTextAndRelativePathsAreUntouched() {
        assertEquals(emptyList<String>(), PushMedia.paths("Your response is ready."))
        assertEquals(emptyList<String>(), PushMedia.paths("MEDIA:relative/x.pdf"))
        assertEquals("Your response is ready.", PushMedia.text("Your response is ready."))
    }
    @Test fun repeatsCollapseAndQuotesAreTrimmed() {
        assertEquals(listOf("/a b/c.pdf"), PushMedia.paths("MEDIA:\"/a b/c.pdf\"\nMEDIA:/a b/c.pdf"))
    }
}
