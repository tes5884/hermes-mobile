package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.push.PushAvatars
import com.nousresearch.hermesmobile.push.PushConversation
import org.junit.Assert.*
import org.junit.Test

class PushConversationTest {
    @Test fun oneShortcutPerBotPerServer() {
        assertEquals("bot:home|ezra", PushConversation.shortcutId("home", "ezra"))
        assertNotEquals(PushConversation.shortcutId("home", "ezra"), PushConversation.shortcutId("bilbo", "ezra"))
        assertNotEquals(PushConversation.shortcutId("home", "ezra"), PushConversation.shortcutId("home", "mason"))
    }

    @Test fun botNameIsTheBotListName() {
        assertEquals("Ezra (CoS)", PushConversation.botName("ezra", "Ezra (CoS)"))
        assertEquals("Ezra", PushConversation.botName("ezra", null))
        assertEquals("Ezra", PushConversation.botName("ezra", "  "))
    }

    @Test fun headingOnlyWhenItSaysSomething() {
        assertEquals("Morning briefing", PushConversation.heading("Morning briefing", "Della", "della"))
        assertEquals("Front Office", PushConversation.heading("Front Office", "Mason", "mason"))
        assertEquals("Approval needed · Bot Chat", PushConversation.heading("Approval needed · Bot Chat", "Ezra", "ezra"))
        // The sender's own name, the generic title and the canonical chat's title add nothing.
        assertNull(PushConversation.heading("Ezra", "Ezra", "ezra"))
        assertNull(PushConversation.heading("ezra", "Ezra (CoS)", "ezra"))
        assertNull(PushConversation.heading("Hermes", "Ezra", "ezra"))
        assertNull(PushConversation.heading("Bot Chat", "Ezra", "ezra"))
        assertNull(PushConversation.heading("  ", "Ezra", "ezra"))
    }

    @Test fun shortcutTapOpensThatBotsChat() {
        assertTrue(PushConversation.opensBot("hermes.bot", "home", "ezra"))
        assertFalse(PushConversation.opensBot("hermes.notification", "home", "ezra"))
        assertFalse(PushConversation.opensBot("hermes.bot", "", "ezra"))
        assertFalse(PushConversation.opensBot("hermes.bot", "home", null))
        // Same shape as a cron notice without a chat: openNotification opens the bot's canonical chat for it.
        val event = PushConversation.openEvent("home", "ezra")
        assertEquals("home", event.optString("connection"))
        assertEquals("ezra", event.optString("profile"))
        assertFalse(event.has("session_id"))
        assertFalse(event.has("body"))
    }

    @Test fun avatarFetchedOnlyWhenMissingOrRevisionMoved() {
        // Has a photo: fetch when nothing saved, the file is gone, or the hermes-bots revision changed.
        assertTrue(PushAvatars.stale(saved = null, current = 3, hasPhotoFile = false, hasAvatar = true))
        assertTrue(PushAvatars.stale(saved = 3, current = 3, hasPhotoFile = false, hasAvatar = true))
        assertTrue(PushAvatars.stale(saved = 2, current = 3, hasPhotoFile = true, hasAvatar = true))
        assertTrue(PushAvatars.stale(saved = -1, current = 3, hasPhotoFile = true, hasAvatar = true))
        assertFalse(PushAvatars.stale(saved = 3, current = 3, hasPhotoFile = true, hasAvatar = true))
        // No revision on the roster (no hermes-bots meta): a saved photo is kept.
        assertFalse(PushAvatars.stale(saved = -1, current = null, hasPhotoFile = true, hasAvatar = true))
        // No photo any more: remove a saved one; record "none" once.
        assertTrue(PushAvatars.stale(saved = 3, current = 4, hasPhotoFile = true, hasAvatar = false))
        assertTrue(PushAvatars.stale(saved = null, current = 4, hasPhotoFile = false, hasAvatar = false))
        assertFalse(PushAvatars.stale(saved = 4, current = 4, hasPhotoFile = false, hasAvatar = false))
    }
}
