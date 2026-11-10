package com.nousresearch.hermesmobile.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Counts who wants the screen kept on. The screen stays on while at least one hold is out, so one recorder ending
 * can't switch it off under another, and a release without a hold is harmless. UI thread only.
 */
class ScreenAwakeCounter {
    private var holds = 0
    val held: Boolean get() = holds > 0

    /** Takes a hold; returns whether the screen should be kept on now (always true). */
    fun acquire(): Boolean { holds++; return held }

    /** Gives a hold back; returns whether the screen should still be kept on. */
    fun release(): Boolean { if (holds > 0) holds--; return held }
}

internal val ScreenAwake = ScreenAwakeCounter()

/**
 * Keeps the screen from dimming or locking while [active] (a voice note is recording, held or locked). The flag is
 * dropped as soon as [active] turns false (sent, cancelled, failed, 10-minute cap) or the caller leaves the screen.
 */
@Composable
fun KeepScreenOnWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        if (active) view.keepScreenOn = ScreenAwake.acquire()
        onDispose { if (active) view.keepScreenOn = ScreenAwake.release() }
    }
}
