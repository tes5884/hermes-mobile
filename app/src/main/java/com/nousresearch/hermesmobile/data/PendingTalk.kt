package com.nousresearch.hermesmobile.data

/**
 * A voice note recorded on the bots page ("Talk to …") waiting for the ONE chat open that recording started.
 *
 * It is bound to that open's navigation generation, its connection, and the screen it was recorded on ([V]). Only
 * that open, still current, on the same connection, for the same bot, may send it. [reconcile] runs on every change
 * of screen or navigation (see [ChatChange]) and discards it as soon as anything else happens: a newer navigation,
 * or the screen moving anywhere other than that bot's chat. Shomer P1 review: a note must never be sent without the
 * send gesture that recorded it leading straight to its chat.
 */
class PendingTalkSlot<T, V>(private val discard: (T) -> Unit) {
    private class Held<T, V>(val profile: String, val generation: Long, val connection: String, val origin: V, val item: T)

    private var held: Held<T, V>? = null

    val waiting: Boolean get() = held != null

    /** Holds [item] for the open with [generation], recorded while [origin] was on screen; an earlier note is discarded. */
    fun hold(profile: String, generation: Long, connection: String, origin: V, item: T) {
        drop()
        held = Held(profile, generation, connection, origin, item)
    }

    /**
     * The screen or the navigation changed. The note stays only while its open is the newest navigation
     * ([generation]) and [view] is still the screen it was recorded on or [isTarget] (that bot's chat on its connection).
     */
    fun reconcile(generation: Long, view: V, isTarget: (profile: String, connection: String) -> Boolean) {
        val h = held ?: return
        if (h.generation == generation && (view == h.origin || isTarget(h.profile, h.connection))) return
        drop()
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

    /** Teardown (the view model is cleared): the note is discarded. Navigation goes through [reconcile]. */
    fun drop() {
        val h = held ?: return
        held = null
        discard(h.item)
    }
}
