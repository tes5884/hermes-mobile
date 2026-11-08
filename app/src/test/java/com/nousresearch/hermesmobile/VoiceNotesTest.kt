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
