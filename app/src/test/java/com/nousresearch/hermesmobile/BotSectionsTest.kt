package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.RoutedBot
import com.nousresearch.hermesmobile.ui.screens.botSections
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BotSectionsTest {
    private fun bot(name: String, section: String? = null) = RoutedBot(
        "c", "C",
        profile = HermesProfile(name = name, uiMeta = JSONObject().put("hermes-bots", JSONObject().apply { if (section != null) put("section", section) }))
    )

    @Test fun sectionsAlphabeticalOtherLastBotsAlphabetical() {
        val out = botSections(listOf(bot("zed", "Personal"), bot("m1", "Engineering"), bot("w1", "Engineering"), bot("custom"), bot("e1", "Office"), bot("amy", "Alpha"), bot("bob", "Alpha")))
        assertEquals(listOf("Alpha", "Engineering", "Office", "Personal", "Other"), out.map { it.first })
        assertEquals(listOf("amy", "bob"), out[0].second.map { it.profile.name })
        assertEquals(listOf("custom"), out.last().second.map { it.profile.name })
    }

    private fun pinnedBot(name: String, section: String? = null) = RoutedBot(
        "c", "C",
        profile = HermesProfile(name = name, uiMeta = JSONObject().put("hermes-bots", JSONObject().put("pinned", true).apply { if (section != null) put("section", section) }))
    )

    @Test fun pinnedSectionFirstAndNotRepeated() {
        val out = botSections(listOf(bot("m1", "Engineering"), pinnedBot("w1", "Engineering"), pinnedBot("d1", "Office"), bot("e1", "Office")))
        assertEquals(listOf("Pinned", "Engineering", "Office"), out.map { it.first })
        assertEquals(listOf("d1", "w1"), out[0].second.map { it.profile.name })
        assertEquals(listOf("m1"), out[1].second.map { it.profile.name })
        assertEquals(listOf("e1"), out[2].second.map { it.profile.name })
    }

    @Test fun noPinnedNoSection() {
        assertEquals(listOf("Engineering"), botSections(listOf(bot("m1", "Engineering"))).map { it.first })
    }
}
