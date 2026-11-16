package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ChatComposerStore
import com.nousresearch.hermesmobile.data.ComposerKeeper
import com.nousresearch.hermesmobile.data.ComposerSaves
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shomer t_478c28f5 (Medium 1): reopening a chat and closing it before its draft had loaded saved the stale screen (the
 * previous chat's composer) over its saved draft. A chat's draft is saved only with a token from its own load.
 */
class ComposerKeeperTest {
    private class Saves : ComposerSaves {
        val rows = ConcurrentHashMap<String, ChatComposerStore.State>()
        @Volatile var gate: CountDownLatch? = null
        val entered = CompletableDeferred<Unit>()
        override fun load(key: String): ChatComposerStore.State {
            val read = rows[key] ?: ChatComposerStore.State()
            gate?.let { latch -> gate = null; entered.complete(Unit); latch.await(5, TimeUnit.SECONDS) }
            return read
        }
        override fun save(key: String, state: ChatComposerStore.State) { rows[key] = state }
        override fun dropLegacyQueue(key: String) { rows[key]?.let { rows[key] = it.copy(queue = emptyList()) } }
    }

    private val saves = Saves()
    private val keeper = ComposerKeeper(saves, Dispatchers.IO)
    private val ezra = "gw:ezra:ezra-chat"
    private val nova = "gw:nova:nova-chat"
    private fun draft(text: String) = ChatComposerStore.State(draft = text)

    @Test fun closingAChatBeforeItsDraftLoadedNeverOverwritesIt() = runBlocking {
        saves.rows[ezra] = draft("ezra's saved draft")
        keeper.beginLoad()
        keeper.load(nova, "nova-chat") { true }
        // Reopen Ezra: its load is slow; the screen still shows Nova's composer.
        val latch = CountDownLatch(1)
        saves.gate = latch
        keeper.beginLoad()
        val opening = launch(Dispatchers.Default) { keeper.load(ezra, "ezra-chat") { true } }
        saves.entered.await()
        assertNull(keeper.token(ezra))
        // closeActiveChat() → persistCurrentComposerNow() with the stale screen, then beginLoad().
        assertFalse(keeper.persistNow(ezra, keeper.token(ezra), draft("nova's text")))
        keeper.beginLoad()
        latch.countDown()
        opening.join()
        assertEquals("ezra's saved draft", saves.rows[ezra]?.draft)
        // The abandoned load marked nothing loaded.
        assertNull(keeper.token(ezra))
        assertFalse(keeper.isLoaded("ezra-chat"))
    }

    @Test fun aChatsOwnDraftIsSavedOnceLoaded() = runBlocking {
        keeper.beginLoad()
        keeper.load(ezra, "ezra-chat") { true }
        val token = keeper.token(ezra)
        assertTrue(keeper.persistNow(ezra, token, draft("typed")))
        assertEquals("typed", saves.rows[ezra]?.draft)
        assertTrue(keeper.isLoaded("ezra-chat"))
        // Never under another chat's key.
        assertNull(keeper.token(nova))
        assertFalse(keeper.persistNow(nova, token, draft("x")))
    }

    @Test fun aSaveScheduledBeforeLeavingIsDeadAfterwards() = runBlocking {
        keeper.beginLoad()
        keeper.load(ezra, "ezra-chat") { true }
        val token = keeper.token(ezra)
        // The debounced save of Ezra's screen runs only after you left and came back.
        keeper.beginLoad()
        saves.rows[ezra] = draft("saved by the close")
        keeper.load(ezra, "ezra-chat") { true }
        assertFalse(keeper.persist(ezra, token, draft("older screen")))
        assertEquals("saved by the close", saves.rows[ezra]?.draft)
    }

    @Test fun aLoadTheScreenMovedOnFromMarksNothing() = runBlocking {
        keeper.beginLoad()
        assertNull(keeper.load(ezra, "ezra-chat") { false })
        assertNull(keeper.token(ezra))
    }

    @Test fun aRekeyedChatKeepsSavingItsDraft() = runBlocking {
        keeper.beginLoad()
        keeper.load(ezra, "ezra-chat") { true }
        keeper.rekey(ezra, "gw:ezra:compressed", "compressed")
        assertTrue(keeper.persistNow("gw:ezra:compressed", keeper.token("gw:ezra:compressed"), draft("kept")))
        assertTrue(keeper.isLoaded("compressed"))
    }
}
