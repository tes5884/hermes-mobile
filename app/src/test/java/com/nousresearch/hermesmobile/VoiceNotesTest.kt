package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.*
import org.junit.Assert.*
import org.junit.Test

class VoiceNotesTest {
    @Test fun nameCarriesDuration() {
        val n = VoiceNotes.fileName(12_400, 1794092400000L)
        assertEquals("voice-note-12s-1794092400000.m4a", n)
        assertEquals(12, VoiceNotes.durationSeconds(n))
        assertNull(VoiceNotes.durationSeconds("song.m4a"))
        assertEquals("voice-note-1s-5.m4a", VoiceNotes.fileName(100, 5))
    }

    @Test fun transcriptOrFallback() {
        assertEquals("hello @ezra", VoiceNotes.messageText("  hello @ezra "))
        assertEquals(VoiceNotes.FAILED_TEXT, VoiceNotes.messageText(""))
        assertEquals(VoiceNotes.FAILED_TEXT, VoiceNotes.messageText(null))
    }

    @Test fun recognisesAudio() {
        assertTrue(VoiceNotes.isVoiceNote(GroupAttachment("a", "file", "x.m4a", "audio/mp4")))
        assertFalse(VoiceNotes.isVoiceNote(GroupAttachment("a", "file", "x.zip", "application/zip")))
        assertEquals("1:05", VoiceNotes.clock(65))
    }

    @Test fun audioUploadsAsPlainFile() {
        val (kind, mime) = GroupAttachments.classify("voice-note-3s-1.m4a", "audio/mp4", ByteArray(10))
        assertEquals("file", kind); assertEquals("audio/mp4", mime)
    }
}

class VoiceHoldTest {
    @Test fun shortHoldsAreIgnored() {
        assertTrue(VoiceNotes.tooShort(300)); assertTrue(VoiceNotes.tooShort(999)); assertFalse(VoiceNotes.tooShort(1000))
    }

    @Test fun limitsAndWarning() {
        assertEquals(10L * 60 * 1000, VoiceNotes.MAX_MS)
        assertFalse(VoiceNotes.nearLimit(9 * 60_000 + 29_999)); assertTrue(VoiceNotes.nearLimit(9 * 60_000 + 30_000))
        // 10 min at 32 kbps stays far under the 8 MB cap
        assertTrue(VoiceNotes.BITRATE / 8L * 600 < VoiceNotes.MAX_BYTES)
    }

    @Test fun slideLeftCancelsPastAThird() {
        assertFalse(VoiceNotes.isCancelDrag(-100f, 900f)); assertTrue(VoiceNotes.isCancelDrag(-301f, 900f))
        assertFalse(VoiceNotes.isCancelDrag(500f, 900f)); assertFalse(VoiceNotes.isCancelDrag(-500f, 0f))
    }

    @Test fun slideUpLocks() {
        assertFalse(VoiceNotes.isLockDrag(-30f, 100f)); assertTrue(VoiceNotes.isLockDrag(-101f, 100f)); assertFalse(VoiceNotes.isLockDrag(80f, 100f))
    }

    @Test fun waveformLevels() {
        assertEquals(0f, VoiceNotes.level(0), 0f); assertEquals(0f, VoiceNotes.level(100), 0f)
        assertEquals(1f, VoiceNotes.level(32767), 0.001f)
        assertTrue(VoiceNotes.level(1000) > 0.3f)            // quiet speech still shows
        assertTrue(VoiceNotes.level(8000) > VoiceNotes.level(1000))
        val w = (1..50).fold(emptyList<Float>()) { acc, i -> VoiceNotes.pushLevel(acc, i.toFloat()) }
        assertEquals(40, w.size); assertEquals(50f, w.last(), 0f); assertEquals(11f, w.first(), 0f)
    }
}
