package com.nousresearch.hermesmobile.data

/**
 * A voice note recorded on the bots page ("Talk to …") waiting for the ONE chat open that recording started.
 *
 * It is bound to that open's navigation generation and connection: only that open, still current, on the same
 * connection, for the same bot, may send it. Any other outcome of that open (stale because something else was
 * opened meanwhile, failed, another bot, another connection) discards it; a later open never consumes it. Shomer
 * P1 review: a note must never be sent without the send gesture that recorded it leading straight to its chat.
 */
class PendingTalkSlot<T>(private val discard: (T) -> Unit) {
    private class Held<T>(val profile: String, val generation: Long, val connection: String, val item: T)

    private var held: Held<T>? = null

    val waiting: Boolean get() = held != null

    /** Holds [item] for the open with [generation]; an earlier held note is discarded. */
    fun hold(profile: String, generation: Long, connection: String, item: T) {
        drop()
        held = Held(profile, generation, connection, item)
    }

    /**
     * The open with [generation] finished. [current] = it is still the newest navigation and its chat is now on
     * screen for [profile] on [connection]. Returns the note to send, or null. A note held for another open is left alone.
     */
    fun settle(generation: Long, profile: String, connection: String, current: Boolean): T? {
        val h = held ?: return null
        if (h.generation != generation) return null
        held = null
        if (current && h.profile == profile && h.connection == connection) return h.item
        discard(h.item)
        return null
    }

    /** Competing navigation, disconnect, connection switch, teardown: the note is discarded. */
    fun drop() {
        val h = held ?: return
        held = null
        discard(h.item)
    }
}
