package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.*
import org.junit.Assert.*
import org.junit.Test

/** P3: voice notes in a bot chat drawn to Nova's mocks (2-chat-voice, 2b-recording). */
class VoiceBubblesTest {
    private val name = "voice-note-14s-1794092400000.m4a"

    @Test fun liveTurnWithOneNoteIsAVoiceBubble() {
        val note = VoiceBubbles.of(ChatMessage(role = "user", content = "Show me the mocks", attachments = listOf(name)))
        assertNotNull(note)
        assertEquals(14, note!!.seconds)
        assertEquals("Show me the mocks", note.transcript)
        assertNull("a live bubble has no gateway path yet", note.path)
        assertTrue(note.transcribed)
    }

    @Test fun storedTurnKnowsWhereTheAudioLives() {
        val path = "/home/hermes/.hermes/uploads/s1/$name"
        val note = VoiceBubbles.of(ChatMessage(role = "user", content = "hi", attachments = listOf(name), refs = listOf("@file:$path")))
        assertEquals(path, note?.path)
        // Quoted refs (paths with spaces) resolve the same way.
        val spaced = "/tmp/my uploads/$name"
        assertEquals(spaced, VoiceBubbles.of(ChatMessage(role = "user", content = "hi", attachments = listOf(name), refs = listOf("@file:\"$spaced\"")))?.path)
    }

    @Test fun stagedNameThatKeepsTheNoteStillCounts() {
        val staged = "/x/upload_ab12_$name"
        val note = VoiceBubbles.of(ChatMessage(role = "user", content = "hi", attachments = listOf(staged.substringAfterLast('/')), refs = listOf("@file:$staged")))
        assertEquals(14, note?.seconds)
        assertEquals(14, VoiceBubbles.seconds("upload_ab12_$name"))
        assertNull(VoiceBubbles.seconds("voice-note-14s-1.m4a.txt"))
    }

    @Test fun otherTurnsKeepTheirChips() {
        assertNull("bot turns are never voice bubbles", VoiceBubbles.of(ChatMessage(role = "assistant", content = "x", attachments = listOf(name))))
        assertNull("a note sent with another file keeps both chips", VoiceBubbles.of(ChatMessage(role = "user", content = "x", attachments = listOf(name, "report.pdf"))))
        assertNull("a song is not a voice note", VoiceBubbles.of(ChatMessage(role = "user", content = "x", attachments = listOf("song.m4a"))))
        assertNull(VoiceBubbles.of(ChatMessage(role = "user", content = "plain text")))
    }

    @Test fun failedTranscriptionShowsNoWords() {
        val note = VoiceBubbles.of(ChatMessage(role = "user", content = VoiceNotes.FAILED_TEXT, attachments = listOf(name)))!!
        assertFalse(note.transcribed)
    }

    @Test fun barsAreStableAndInRange() {
        val a = VoiceBubbles.bars(name)
        assertEquals(a, VoiceBubbles.bars(name))
        assertEquals(28, a.size)
        assertTrue(a.all { it in .25f..1f })
        assertNotEquals(a, VoiceBubbles.bars("voice-note-3s-5.m4a"))
        assertEquals(10, VoiceBubbles.bars("", 10).size)
    }

    @Test fun playbackFillsTheBars() {
        assertEquals(0f, VoiceBubbles.progress(500, 0))
        assertEquals(.5f, VoiceBubbles.progress(7000, 14000), 1e-6f)
        assertEquals(1f, VoiceBubbles.progress(20000, 14000))
        assertFalse(VoiceBubbles.played(0, 28, 0f))
        assertTrue(VoiceBubbles.played(0, 28, .5f))
        assertTrue(VoiceBubbles.played(13, 28, .5f))
        assertFalse(VoiceBubbles.played(14, 28, .5f))
        assertTrue(VoiceBubbles.played(27, 28, 1f))
    }

    @Test fun composerShowsOneStateAtATime() {
        assertEquals(VoiceBubbles.Composer.IDLE, VoiceBubbles.composer(recording = false, locked = false, hasMessage = false))
        assertEquals(VoiceBubbles.Composer.TYPING, VoiceBubbles.composer(recording = false, locked = false, hasMessage = true))
        // A recording wins over a draft: the pill replaces the field until it is sent or cancelled.
        assertEquals(VoiceBubbles.Composer.RECORDING, VoiceBubbles.composer(recording = true, locked = false, hasMessage = true))
        assertEquals(VoiceBubbles.Composer.LOCKED, VoiceBubbles.composer(recording = true, locked = true, hasMessage = false))
        // A stale lock flag without a recording is idle.
        assertEquals(VoiceBubbles.Composer.IDLE, VoiceBubbles.composer(recording = false, locked = true, hasMessage = false))
    }

    @Test fun headerSaysListeningWhileRecording() {
        assertEquals(VoiceBubbles.Header.LISTENING, VoiceBubbles.header(listening = true, needsOk = true, working = true))
        assertEquals(VoiceBubbles.Header.NEEDS_OK, VoiceBubbles.header(listening = false, needsOk = true, working = true))
        assertEquals(VoiceBubbles.Header.WORKING, VoiceBubbles.header(listening = false, needsOk = false, working = true))
        assertEquals(VoiceBubbles.Header.ROLE, VoiceBubbles.header(listening = false, needsOk = false, working = false))
    }
}
