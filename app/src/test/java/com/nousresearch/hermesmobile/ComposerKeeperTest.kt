package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ChatComposerStore
import com.nousresearch.hermesmobile.data.ComposerKeeper
import com.nousresearch.hermesmobile.data.ComposerSaves
import com.nousresearch.hermesmobile.data.OwnerRefusal
import com.nousresearch.hermesmobile.data.QueuedPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
 * Shomer t_8d113995 (Medium 1): a send refused after you left its chat (SESSION_NOT_OWNED, any refusal) was dropped.
 * Run on the real [ComposerKeeper] with the real [OwnerRefusal.keep] rule, the saved composers in memory: whatever the
 * refusal, the message is kept in the chat it was written in, whether that chat is on screen, left, or opening again.
 */
class ComposerKeeperTest {
    private class Saves : ComposerSaves {
        val rows = ConcurrentHashMap<String, ChatComposerStore.State>()
        /** When set, the next load waits here (a chat opening slowly) after reading, having completed [entered]. */
        @Volatile var gate: CountDownLatch? = null
        val entered = CompletableDeferred<Unit>()
        override fun load(key: String): ChatComposerStore.State {
            val read = rows[key] ?: ChatComposerStore.State()
            gate?.let { latch -> gate = null; entered.complete(Unit); latch.await(5, TimeUnit.SECONDS) }
            return read
        }
        override fun save(key: String, state: ChatComposerStore.State) { rows[key] = state }
    }

    /** The composer on screen, as the ViewModel's: a chat (or none) and its draft/queue; persist saves it. */
    private class Screen(private val saves: Saves) : ComposerKeeper.Screen {
        @Volatile override var sessionId: String? = null
        @Volatile override var key: String? = null
        @Volatile override var state = ChatComposerStore.State()
        var persisted = 0
        fun show(session: String?, composerKey: String?, composer: ChatComposerStore.State = ChatComposerStore.State()) {
            sessionId = session; key = composerKey; state = composer
        }
        override fun persist() { persisted++; key?.let { saves.save(it, state) } }
    }

    private val saves = Saves()
    private val screen = Screen(saves)
    private val keeper = ComposerKeeper(saves, Dispatchers.IO)

    private fun refused(hold: String, edit: Boolean = false, text: String = "run checks first"): (ChatComposerStore.State) -> ChatComposerStore.State =
        { OwnerRefusal.keep(it, "r", text, emptyList(), hold, edit) }

    private suspend fun open(session: String, key: String) {
        screen.show(session, key)
        keeper.beginLoad()
        keeper.load(key, session) { saved -> screen.state = saved; true }
    }

    @Test fun refusedAfterYouLeftItsChatItIsKeptThereAndShownWhenItOpens() = runBlocking {
        for (hold in listOf(QueuedPrompt.HOLD_NOT_SENT, QueuedPrompt.HOLD_UNCONFIRMED, QueuedPrompt.HOLD_BUSY)) {
            saves.rows.clear()
            open("ezra-chat", "gw:ezra:ezra-chat")
            // You sent, then opened another bot's chat before prompt.submit answered.
            open("nova-chat", "gw:nova:nova-chat")
            screen.state = ChatComposerStore.State(draft = "for nova")
            // SESSION_NOT_OWNED (or anything) comes back now.
            val onScreen = keeper.keep("gw:ezra:ezra-chat", "ezra-chat", screen, transform = refused(hold))
            assertFalse(onScreen)
            // Nova's composer is untouched.
            assertEquals("for nova", screen.state.draft)
            assertTrue(screen.state.queue.isEmpty())
            // Ezra's chat has it.
            val ezra = saves.rows.getValue("gw:ezra:ezra-chat")
            assertEquals(listOf("run checks first"), (listOf(ezra.draft) + ezra.queue.map { it.text }).filter { it.isNotBlank() })
            // Back in Ezra's chat: on screen.
            open("ezra-chat", "gw:ezra:ezra-chat")
            assertEquals(listOf("run checks first"), (listOf(screen.state.draft) + screen.state.queue.map { it.text }).filter { it.isNotBlank() })
        }
    }

    @Test fun aRefusedEditAfterLeavingIsKeptToo() = runBlocking {
        saves.rows["gw:ezra:c"] = ChatComposerStore.State(draft = "typed later")
        open("other", "gw:x:other")
        keeper.keep("gw:ezra:c", "c", screen, transform = refused(QueuedPrompt.HOLD_NOT_SENT, edit = true, text = "fixed wording"))
        val kept = saves.rows.getValue("gw:ezra:c")
        assertEquals("typed later", kept.draft)
        assertEquals("fixed wording", kept.queue.single().text)
        assertEquals(QueuedPrompt.HOLD_EDIT, kept.queue.single().hold)
    }

    @Test fun refusedWhileItsChatIsOpenItGoesOnScreenAheadOfTheQueue() = runBlocking {
        saves.rows["gw:ezra:c"] = ChatComposerStore.State(queue = listOf(QueuedPrompt("2", "deploy now")))
        open("c", "gw:ezra:c")
        assertTrue(keeper.isLoaded("c"))
        val onScreen = keeper.keep("gw:ezra:c", "c", screen, transform = refused(QueuedPrompt.HOLD_UNCONFIRMED))
        assertTrue(onScreen)
        assertEquals(listOf("run checks first", "deploy now"), screen.state.queue.map { it.text })
        assertEquals(1, screen.persisted)
        // ...and it fences the later instruction.
        assertNull(OwnerRefusal.nextToSend(screen.state.queue))
        assertEquals(screen.state, saves.rows["gw:ezra:c"])
    }

    @Test fun refusedWhileItsChatIsOpeningAgainItIsNeverLost() = runBlocking {
        saves.rows["gw:ezra:c"] = ChatComposerStore.State(queue = listOf(QueuedPrompt("2", "deploy now")))
        // The chat is on screen again but its composer is still loading (slowly).
        screen.show("c", "gw:ezra:c")
        keeper.beginLoad()
        assertFalse("its queue must not drain before it has loaded", keeper.isLoaded("c"))
        val latch = CountDownLatch(1)
        saves.gate = latch
        val loading = launch { keeper.load("gw:ezra:c", "c") { saved -> screen.state = saved; true } }
        saves.entered.await()
        // The refusal arrives in the middle of that load.
        val keeping = launch { keeper.keep("gw:ezra:c", "c", screen, transform = refused(QueuedPrompt.HOLD_NOT_SENT)) }
        delay(100)
        latch.countDown()
        joinAll(loading, keeping)
        assertEquals(listOf("run checks first", "deploy now"), screen.state.queue.map { it.text })
        assertEquals(QueuedPrompt.HOLD_NOT_SENT, screen.state.queue.first().hold)
        assertTrue(keeper.isLoaded("c"))
    }

    @Test fun anotherConnectionsComposerOrAnUnfinishedOpenIsNeverWrittenOnScreen() = runBlocking {
        // Same chat id, another connection's key: the saved copy of the one it was sent on.
        open("c", "gw2:ezra:c")
        assertFalse(keeper.keep("gw:ezra:c", "c", screen, transform = refused(QueuedPrompt.HOLD_NOT_SENT)))
        assertTrue(screen.state.queue.isEmpty() && screen.state.draft.isEmpty())
        assertEquals("run checks first", saves.rows.getValue("gw:ezra:c").draft)
        // An open that the screen moved past shows nothing and is not "loaded".
        screen.show("d", "gw:ezra:d")
        keeper.beginLoad()
        assertNull(keeper.load("gw:ezra:d", "d") { false })
        assertFalse(keeper.isLoaded("d"))
        assertFalse(keeper.keep("gw:ezra:d", "d", screen, transform = refused(QueuedPrompt.HOLD_UNCONFIRMED)))
        assertEquals("run checks first", saves.rows.getValue("gw:ezra:d").draft)
    }
}
