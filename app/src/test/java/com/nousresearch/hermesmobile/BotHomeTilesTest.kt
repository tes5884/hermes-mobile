package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.BotHome
import com.nousresearch.hermesmobile.data.BotHome.Kind
import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.RoutedBot
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Bots page v2 (Nova, Tzvi 2026-11-16): list/tiles switch and the "Needs you" strip. */
class BotHomeTilesTest {
    private fun bot(name: String, at: Long = 0, active: Boolean = true, hidden: Boolean = false, pinned: Boolean = false) = RoutedBot(
        "c", "C", activeConnection = active,
        profile = HermesProfile(name = name, activityAt = at, uiMeta = JSONObject().put("hermes-bots", JSONObject().apply {
            if (hidden) put("hidden", true); if (pinned) put("pinned", true)
        }))
    )
    private fun st(kind: Kind) = BotHome.Status(kind, "")

    @Test fun stripIsNeedsOkFirstThenUnreadEachNewestFirst() {
        val bots = listOf(bot("della", 10), bot("otto", 5), bot("mason", 30), bot("iris", 20), bot("lexi", 40), bot("ezra", 50, pinned = true))
        val kinds = mapOf("della" to Kind.WAITING, "otto" to Kind.NEEDS_OK, "mason" to Kind.NEEDS_OK,
            "iris" to Kind.WAITING, "lexi" to Kind.WORKING, "ezra" to Kind.WAITING)
        val statuses = bots.associate { it.routeKey to st(kinds.getValue(it.profile.name)) }
        // Working and idle bots are not in it; the pinned bot (Ezra) is.
        assertEquals(listOf("mason", "otto", "ezra", "iris", "della"), BotHome.needsYou(bots, statuses).map { it.profile.name })
    }

    @Test fun stripLeavesOutHiddenAndOtherServerBotsAndIsEmptyWhenNothingWaits() {
        val bots = listOf(bot("otto", hidden = true), bot("nova", active = false), bot("ward"))
        val statuses = bots.associate { it.routeKey to st(Kind.NEEDS_OK) }
        assertEquals(listOf("ward"), BotHome.needsYou(bots, statuses).map { it.profile.name })
        assertTrue(BotHome.needsYou(listOf(bot("ward")), mapOf(bot("ward").routeKey to st(Kind.IDLE))).isEmpty())
    }

    @Test fun viewPreferenceDefaultsToListAndRoundTrips() {
        assertEquals(BotHome.View.LIST, BotHome.view(null))
        assertEquals(BotHome.View.LIST, BotHome.view("grid?"))
        BotHome.View.values().forEach { assertEquals(it, BotHome.view(BotHome.viewValue(it))) }
    }

    @Test fun wiring() {
        val ui = File("src/main/java/com/nousresearch/hermesmobile/ui/screens/BotHome.kt").readText()
        assertTrue("strip sits above the pinned card", ui.indexOf("NeedsYouStrip(strip") in 1 until ui.indexOf("PinnedBotCard(bot, statuses"))
        assertTrue("strip tap opens the bot's chat", ui.contains("NeedsYouStrip(strip, statuses, avatarAssets) { actions.onOpen(it.profile) }"))
        assertTrue("search results are always a list", ui.contains("view == BotHome.View.TILES && query.isBlank()"))
        assertTrue("choice is remembered", ui.contains("putString(BotHome.VIEW_PREF"))
        // Shomer t_28984693 (Medium): a tile's state (its open long-press menu) belongs to its bot, not its slot in the row.
        assertTrue("bot tile composition keyed by bot", Regex("""key\(bot\.routeKey\) \{\s+[^}]*\}\s+BotTile\(bot,""").containsMatchIn(ui))
        assertTrue("channel tile keyed by room", ui.contains("key(room.roomId) { ChannelTile(room"))
        assertEquals("tiles are only built inside a keyed block", 1, Regex("""BotTile\(bot,""").findAll(ui).count())
        // The open menu's identity lives in the parent, by routeKey; a tile keeps no menu state of its own.
        val tile = ui.substringAfter("private fun BotTile(").substringBefore("\n}\n")
        assertFalse("tile holds no remembered menu flag", tile.contains("remember"))
        assertTrue("menu opens for this bot", ui.contains("onMenu = { tileMenuKey = bot.routeKey }"))
        assertTrue("menu shown only on the tile of the bot it was opened on", ui.contains("menuOpen = tileMenuBot?.routeKey == bot.routeKey"))
        assertTrue("open menu resolved by routeKey", ui.contains("BotHome.menuTarget(tileMenuKey, shown)"))
    }

    /** Lays the roster out three across, as the tiles do, and says which tile (row, column) shows the open menu. */
    private fun openTile(openKey: String?, roster: List<RoutedBot>): Pair<Int, Int>? {
        val target = BotHome.menuTarget(openKey, roster) ?: return null
        roster.chunked(3).forEachIndexed { r, row -> row.forEachIndexed { c, b -> if (b.routeKey == target.routeKey) return r to c } }
        return null
    }

    @Test fun openTileMenuStaysWithItsBotWhenTheRosterChangesUnderIt() {
        val (alpha, beta, charlie, baker) = listOf("alpha", "beta", "charlie", "baker").map { bot(it) }
        // Long-press Beta: the second tile of the first row.
        val open = beta.routeKey
        assertEquals(0 to 1, openTile(open, listOf(alpha, beta, charlie)))
        // A refresh inserts Baker between Alpha and Beta in the same row: Baker takes Beta's old slot, the menu does not.
        val inserted = listOf(alpha, baker, beta, charlie)
        assertEquals("beta", BotHome.menuTarget(open, inserted)?.profile?.name)
        assertEquals(0 to 2, openTile(open, inserted))
        // A reorder within the row.
        assertEquals(0 to 0, openTile(open, listOf(beta, charlie, alpha)))
        // Beta gone from the roster: no tile shows the menu (it closes), even though its slot is filled.
        assertNull(BotHome.menuTarget(open, listOf(alpha, baker, charlie)))
        assertNull(openTile(open, listOf(alpha, baker, charlie)))
        // Same name on another server is a different bot.
        val otherBeta = RoutedBot("d", "D", activeConnection = false, profile = beta.profile)
        assertNull(BotHome.menuTarget(open, listOf(alpha, otherBeta)))
        assertNull(BotHome.menuTarget(null, inserted))
    }
}
