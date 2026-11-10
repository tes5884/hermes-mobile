package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.features.ScreenAwakeCounter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakeTest {
    @Test fun offUntilARecordingStarts() {
        assertFalse(ScreenAwakeCounter().held)
    }

    @Test fun onWhileRecordingAndOffWhenItEnds() {
        val c = ScreenAwakeCounter()
        assertTrue(c.acquire())      // recording started: flag set
        assertTrue(c.held)
        assertFalse(c.release())     // sent / cancelled / failed / left the screen: flag cleared
        assertFalse(c.held)
    }

    @Test fun staysOnUntilTheLastHolderLetsGo() {
        val c = ScreenAwakeCounter()
        c.acquire(); c.acquire()
        assertTrue(c.release())
        assertFalse(c.release())
    }

    @Test fun extraReleaseDoesNotLeakIntoTheNextRecording() {
        val c = ScreenAwakeCounter()
        assertFalse(c.release())     // nothing was held
        assertTrue(c.acquire())
        assertFalse(c.release())     // one start, one end: off again
    }
}
