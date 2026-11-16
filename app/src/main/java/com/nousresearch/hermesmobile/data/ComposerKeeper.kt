package com.nousresearch.hermesmobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Where chats' composers are saved (draft, attachments, queue), by composer key. */
interface ComposerSaves {
    fun load(key: String): ChatComposerStore.State
    fun save(key: String, state: ChatComposerStore.State)
}

/**
 * Keeps a send the gateway did not take in the chat it was written in, whether that chat is still on screen, was left,
 * or is opening again right now (Shomer t_8d113995: a refusal that came back after you left the chat was dropped).
 *
 * A chat's composer lives in one place at a time: on screen once [load] has shown it, otherwise in its saved copy.
 * [keep] writes to whichever that is; both run under one lock, so a refusal arriving while the chat opens lands in the
 * saved copy before the load reads it, or on screen after the load has shown it, never in between (lost).
 */
class ComposerKeeper(
    private val saves: ComposerSaves,
    private val io: CoroutineContext = Dispatchers.IO
) {
    /** The composer on screen: which chat it is (stored session id + composer key) and its state. */
    interface Screen {
        /** The stored session of the writable chat on screen (null: none). */
        val sessionId: String?
        /** That chat's composer key (null: none). */
        val key: String?
        var state: ChatComposerStore.State
        /** Save what is on screen now (the screen's own save; it is the newest). */
        fun persist()
    }

    private val lock = Mutex()
    @Volatile private var loadedSession: String? = null

    /** A chat starts opening: until [load] shows its composer, what is on screen is not that chat's. */
    fun beginLoad() {
        loadedSession = null
    }

    /** Whether the composer on screen is this chat's own, loaded (its queue may drain). */
    fun isLoaded(sessionId: String?): Boolean = sessionId != null && loadedSession == sessionId

    /**
     * Reads [key]'s saved composer and has [show] put it on screen; [show] returns false when the screen has moved on
     * (nothing shown, nothing marked loaded). Returns what was shown.
     */
    suspend fun load(key: String, sessionId: String, show: (ChatComposerStore.State) -> Boolean): ChatComposerStore.State? =
        lock.withLock {
            val saved = withContext(io) { saves.load(key) }
            if (!show(saved)) return@withLock null
            loadedSession = sessionId
            saved
        }

    /**
     * Applies [transform] to the composer of the chat [sessionId] (saved under [key]): on screen when that chat's own
     * composer is there, else its saved copy. [beforeSave] runs first on the saved path (a save of it still under way).
     * Returns true when it went on screen.
     */
    suspend fun keep(
        key: String,
        sessionId: String,
        screen: Screen,
        beforeSave: suspend () -> Unit = {},
        transform: (ChatComposerStore.State) -> ChatComposerStore.State
    ): Boolean = lock.withLock {
        if (screen.sessionId == sessionId && screen.key == key && loadedSession == sessionId) {
            screen.state = transform(screen.state)
            screen.persist()
            true
        } else {
            beforeSave()
            withContext(io) { saves.save(key, transform(saves.load(key))) }
            false
        }
    }
}
