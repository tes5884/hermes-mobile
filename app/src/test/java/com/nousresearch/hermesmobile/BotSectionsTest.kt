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
        val out = botSections(listOf(bot("zed", "Personal"), bot("mason"), bot("ward"), bot("custom"), bot("ezra"), bot("amy", "Alpha"), bot("bob", "Alpha")))
        assertEquals(listOf("Alpha", "Engineering", "Front office", "Personal", "Other"), out.map { it.first })
        assertEquals(listOf("amy", "bob"), out[0].second.map { it.profile.name })
        assertEquals(listOf("custom"), out.last().second.map { it.profile.name })
    }
}
