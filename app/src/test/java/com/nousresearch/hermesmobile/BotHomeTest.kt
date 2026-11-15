package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.BotHome
import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.RoutedBot
import com.nousresearch.hermesmobile.data.SessionDot
import com.nousresearch.hermesmobile.ui.screens.botChatProfile
import com.nousresearch.hermesmobile.ui.screens.botSection
import com.nousresearch.hermesmobile.data.ActiveChat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BotHomeTest {
    private fun bot(
        name: String, section: String? = null, pinned: Boolean = false, hidden: Boolean = false,
        active: Boolean = true, title: String = "", description: String = "", canonical: String? = null
    ) = RoutedBot(
        "c", "C", activeConnection = active,
        profile = HermesProfile(
            name = name, botTitle = title, description = description, canonicalSession = canonical,
            uiMeta = JSONObject().put("hermes-bots", JSONObject().apply {
                if (section != null) put("section", section)
                if (pinned) put("pinned", true)
                if (hidden) put("hidden", true)
            })
        )
    )

    @Test fun chiefIsFirstPinnedElseEzra() {
        assertEquals("ezra", BotHome.chief(listOf(bot("mason"), bot("ezra"), bot("della")))?.profile?.name)
        assertEquals("della", BotHome.chief(listOf(bot("ezra"), bot("della", pinned = true), bot("zed", pinned = true)))?.profile?.name)
        assertNull(BotHome.chief(listOf(bot("mason"))))
        // A bot on another gateway can't be talked to from here.
        assertNull(BotHome.chief(listOf(bot("ezra", active = false))))
    }

    @Test fun pinnedCardsFallBackToEzraAndAreNotRepeated() {
        val bots = listOf(bot("ezra"), bot("mason"), bot("della"))
        assertEquals(listOf("ezra"), BotHome.pinned(bots).map { it.profile.name })
        val sections = BotHome.sections(bots, ::botSection)
        assertEquals(listOf("Front office", "Engineering"), sections.map { it.first })
        assertEquals(listOf("della"), sections[0].second.map { it.profile.name })
    }

    @Test fun sectionsInFixedOrderThenCustomThenOther() {
        val bots = listOf(
            bot("chaim"), bot("zz", section = "Zeta"), bot("aa", section = "Alpha"), bot("mason"), bot("della"),
            bot("custom"), bot("ezra", pinned = true), bot("ghost", hidden = true)
        )
        val out = BotHome.sections(bots, ::botSection)
        assertEquals(listOf("Front office", "Engineering", "Personal", "Alpha", "Zeta", "Other"), out.map { it.first })
        assertEquals(false, out.flatMap { it.second }.any { it.profile.name == "ghost" || it.profile.name == "ezra" })
    }

    @Test fun rowsNeverMoveOnActivity() {
        // Order inside a section is by name only, never by recency.
        val out = BotHome.sections(listOf(bot("penny"), bot("della"), bot("lexi")), ::botSection)
        assertEquals(listOf("della", "lexi", "penny"), out.single().second.map { it.profile.name })
    }

    @Test fun statusLoudestFirst() {
        assertEquals(BotHome.Kind.NEEDS_OK, BotHome.status(SessionDot.NEEDS_INPUT, unread = true, preview = "x").kind)
        assertEquals(BotHome.Kind.WORKING, BotHome.status(SessionDot.WORKING, unread = true, preview = "x").kind)
        assertEquals(BotHome.Kind.WAITING, BotHome.status(SessionDot.NONE, unread = true, preview = "x").kind)
        assertEquals(BotHome.Kind.WAITING, BotHome.status(SessionDot.UNREAD, unread = false, preview = "x").kind)
        assertEquals(BotHome.Kind.IDLE, BotHome.status(SessionDot.NONE, unread = false, preview = "").kind)
        assertEquals("pick a mock", BotHome.status(SessionDot.NONE, false, "**pick**\na mock").text)
    }

    @Test fun summaryCounts() {
        val s = BotHome.summary(listOf(
            BotHome.Status(BotHome.Kind.NEEDS_OK, ""), BotHome.Status(BotHome.Kind.WAITING, ""),
            BotHome.Status(BotHome.Kind.WORKING, ""), BotHome.Status(BotHome.Kind.IDLE, ""), BotHome.Status(BotHome.Kind.IDLE, "")
        ))
        assertEquals(BotHome.Summary(2, 1, 2), s)
    }

    @Test fun roleIsShortFirstPhrase() {
        assertEquals("Chief of staff", BotHome.role(HermesProfile(name = "ezra", description = "Chief of staff. Routes work to the team.")))
        assertEquals("Finance", BotHome.role(HermesProfile(name = "p", description = "Penny|Finance")))
        assertEquals("", BotHome.role(HermesProfile(name = "p")))
    }

    @Test fun timeLabels() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 10, 0, 0) }.timeInMillis
        val morning = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 9, 38, 0) }.timeInMillis / 1000
        val afternoon = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 0, 5, 0) }.timeInMillis / 1000
        assertEquals("9:38", BotHome.timeLabel(morning, now))
        assertEquals("12:05", BotHome.timeLabel(afternoon, now))
        assertEquals("0:05", BotHome.timeLabel(afternoon, now, use24h = true))
        assertEquals("", BotHome.timeLabel(0, now))
    }

    @Test fun botChatIsCanonicalOrOpenedAsBot() {
        val profiles = listOf(bot("ezra", canonical = "s1").profile, HermesProfile(name = "plain"))
        val chat = ActiveChat(storedSessionId = "s1", runtimeSessionId = "r", profile = "ezra", title = "")
        assertEquals("ezra", botChatProfile(chat, profiles)?.name)
        assertNull(botChatProfile(chat.copy(storedSessionId = "side"), profiles))
        assertEquals("ezra", botChatProfile(chat.copy(storedSessionId = "side", botChat = true), profiles)?.name)
        // A profile without Bot Mode metadata never gets the bot header.
        assertNull(botChatProfile(chat.copy(profile = "plain", botChat = true), profiles))
    }
}
