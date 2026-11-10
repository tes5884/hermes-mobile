package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ChatMedia
import com.nousresearch.hermesmobile.ui.components.RoomArtifact
import com.nousresearch.hermesmobile.ui.components.posterOpen
import com.nousresearch.hermesmobile.ui.components.roomOpenAs
import com.nousresearch.hermesmobile.ui.components.tapLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** vc76: a file link tapped in a room reaches the viewer (it used to hit an empty open callback). */
class RoomFileLinkTest {
    private val post = "Here is the build:\nMEDIA:/home/tzvi/staff/outbox-files/hermes-vc75-32e36db4.apk"

    /** The link the room renders for a MEDIA file line, as MarkdownLite gets it. */
    private fun renderedLink(text: String): String =
        Regex("""\]\(<([^>]+)>\)""").find(ChatMedia.renderCaptioned(text))!!.groupValues[1]

    /** Same wiring as HermesMobileApp's room owner + GroupChatScreen's per-poster owner. */
    private fun roomTap(poster: String?, selected: String, link: String): Pair<RoomArtifact?, String?> {
        var shown: RoomArtifact? = null
        var browser: String? = null
        val show = roomOpenAs(roomFallbackProfile(selected)) { shown = it }
        val roomOpen: (String) -> Unit = { path -> show("", path) }
        val open = poster?.takeIf { it.isNotBlank() }?.let { posterOpen(it, show, roomOpen) } ?: roomOpen
        tapLink(link, open) { browser = it }
        return shown to browser
    }

    @Test
    fun fileLinkInRoomOpensViewerWithPostersProfile() {
        val link = renderedLink(post)
        assertEquals("/home/tzvi/staff/outbox-files/hermes-vc75-32e36db4.apk", link)
        val (shown, browser) = roomTap("ezra", "all", link)
        assertEquals(RoomArtifact("ezra", link), shown)
        assertNull(browser)
    }

    @Test
    fun unknownPosterFallsBackToSelectedOrDefaultProfile() {
        val link = renderedLink(post)
        assertEquals(RoomArtifact("mason", link), roomTap(null, "mason", link).first)
        assertEquals(RoomArtifact("default", link), roomTap("", "all", link).first)
        assertEquals(RoomArtifact("default", link), roomTap(" ", "", link).first)
    }

    @Test
    fun sameOpenPathAsOneToOne() {
        // 1:1 has no openAs: the poster wrapper keeps the chat's own open callback.
        var opened: String? = null
        val oneToOne: (String) -> Unit = { opened = it }
        tapLink("/tmp/report.pdf", posterOpen("della", null, oneToOne)) { error("browser") }
        assertEquals("/tmp/report.pdf", opened)
    }

    @Test
    fun webLinksStillGoToTheBrowser() {
        val (shown, browser) = roomTap("ezra", "all", "https://example.com/a.apk")
        assertNull(shown)
        assertEquals("https://example.com/a.apk", browser)
        var b2: String? = null
        tapLink("/tmp/a.apk", null) { b2 = it }
        assertEquals("/tmp/a.apk", b2)
    }
}
