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
    }
}
