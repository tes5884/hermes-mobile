package com.nousresearch.hermesmobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Where chats' drafts are saved, by composer key. Writes are durable or throw. */
interface ComposerSaves {
    fun load(key: String): ChatComposerStore.State
    fun save(key: String, state: ChatComposerStore.State)
    /** Removes the queue an older version saved under [key] (the draft stays). */
    fun dropLegacyQueue(key: String)
}

/** What moving an older version's queue into the outbox did. */
enum class LegacyMove {
    /** No old queue there. */
    NONE,
    /** In the outbox on disk, and gone from the composer. */
    MOVED,
    /** In the outbox on disk; the composer's copy could not be removed yet (harmless: never imported twice; tried again next open). */
    MOVED_COPY_LEFT,
    /** The outbox could not write it: nothing moved, the composer keeps it. The caller says so. */
    KEPT
}

/**
 * Which chat's draft is on screen (Shomer t_478c28f5, Medium 1). A chat that is opening shows the previous chat's (or
 * an empty) composer until its own saved draft has loaded; saving the screen to that chat's key before then would write
 * the stale screen over its saved draft. So the screen is saved as a chat's draft only with a [token] taken while that
 * chat's own draft was on screen, and only while the same load is still the one shown.
 *
 * (Messages you sent never pass through here: they are the [Outbox]'s from the moment you press send.)
 */
class ComposerKeeper(
    private val saves: ComposerSaves,
    private val io: CoroutineContext = Dispatchers.IO
) {
    private val lock = Mutex()
    /** Bumped by every [beginLoad]: a token from before it is dead. */
    @Volatile private var generation = 0L
    /** The composer key whose own draft is on screen (null: none loaded). */
    @Volatile private var loadedKey: String? = null
    @Volatile private var loadedSession: String? = null

    /** A chat starts opening (or the chat closes): what is on screen belongs to no chat until [load] shows one. */
    fun beginLoad() {
        generation += 1
        loadedKey = null
        loadedSession = null
    }

    /** Whether the composer on screen is this chat's own, loaded. */
    fun isLoaded(sessionId: String?): Boolean = sessionId != null && loadedSession == sessionId

    /** A token to save the screen as [key]'s draft: null unless that chat's own draft is the one on screen now. */
    fun token(key: String): Long? = generation.takeIf { loadedKey == key }

    private fun valid(key: String, token: Long?): Boolean = token != null && token == generation && loadedKey == key

    /** The chat on screen was re-keyed (compression rotated its stored session id); its draft stays on screen. */
    fun rekey(oldKey: String, key: String, sessionId: String) {
        if (loadedKey == oldKey) {
            loadedKey = key
            loadedSession = sessionId
        }
    }

    /**
     * Reads [key]'s saved draft and has [show] put it on screen; [show] returns false when the screen has moved on
     * (nothing shown, nothing marked loaded). Returns what was shown.
     */
    suspend fun load(key: String, sessionId: String, show: (ChatComposerStore.State) -> Boolean): ChatComposerStore.State? {
        val mine = generation
        return lock.withLock {
            val saved = withContext(io) { saves.load(key) }
            if (mine != generation || !show(saved)) return@withLock null
            loadedKey = key
            loadedSession = sessionId
            saved
        }
    }

    /** Saves [state] as [key]'s draft now, if [token] is still the live one for it. False: refused or not saved. */
    fun persistNow(key: String, token: Long?, state: ChatComposerStore.State): Boolean {
        if (!valid(key, token)) return false
        return try {
            saves.save(key, state)
            true
        } catch (t: Throwable) {
            onSaveFailed?.invoke(t)
            false
        }
    }

    /** Like [persistNow], off the main thread and after any load under way. */
    suspend fun persist(key: String, token: Long?, state: ChatComposerStore.State): Boolean = lock.withLock {
        if (!valid(key, token)) return@withLock false
        try {
            withContext(io) { saves.save(key, state) }
            true
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            onSaveFailed?.invoke(t)
            false
        }
    }

    /** Called when the disk refused a draft (on the caller's thread): the caller says so. */
    @Volatile var onSaveFailed: ((Throwable) -> Unit)? = null

    /**
     * Moves the queue an older version saved in [key]'s composer into the outbox ([import] writes it there durably,
     * true once it is on disk). The composer lets go of it only after that; when [import] fails it keeps it, untouched,
     * and the next open tries again. Under the same lock as loads and saves, reading the queue fresh from the disk.
     */
    suspend fun migrateLegacy(key: String, import: (List<QueuedPrompt>) -> Boolean): LegacyMove = lock.withLock {
        val queue = withContext(io) { saves.load(key).queue }
        if (queue.isEmpty()) return@withLock LegacyMove.NONE
        if (!import(queue)) return@withLock LegacyMove.KEPT
        try {
            withContext(io) { saves.dropLegacyQueue(key) }
            LegacyMove.MOVED
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (_: Throwable) {
            LegacyMove.MOVED_COPY_LEFT
        }
    }
}
