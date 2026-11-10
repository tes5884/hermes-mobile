package com.nousresearch.hermesmobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * LOCAL (routine-chat): the message a tapped notice points at. The view model sets it when a routine notice is
 * tapped; the chat or room that opens scrolls to it once and clears it. It expires on its own, so a chat that never
 * shows the message (older than the loaded page) is not yanked later.
 */
internal object PushAnchor {
    class Target(val room: Boolean, val text: String, val at: Long)

    var target by mutableStateOf<Target?>(null)
        private set

    fun set(room: Boolean, text: String) {
        target = if (text.isBlank()) null else Target(room, text, System.currentTimeMillis())
    }

    /** The pending target for a chat ([room] false) or a room, or null when none or stale (30 s). */
    fun pending(room: Boolean): Target? =
        target?.takeIf { it.room == room && System.currentTimeMillis() - it.at < 30_000 }

    fun clear() { target = null }
}
