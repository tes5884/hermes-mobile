package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.BargeDetector
import com.nousresearch.hermesmobile.data.BargePhase
import com.nousresearch.hermesmobile.data.CallRouteKind
import com.nousresearch.hermesmobile.data.CallRoutes
import com.nousresearch.hermesmobile.data.ChatView
import com.nousresearch.hermesmobile.data.SpeechSentences
import com.nousresearch.hermesmobile.data.TtsEcho
import com.nousresearch.hermesmobile.data.UtteranceEndpointer
import com.nousresearch.hermesmobile.data.VoiceCallBinding
import com.nousresearch.hermesmobile.data.VoiceSpeech
import com.nousresearch.hermesmobile.data.VoiceStop
import org.junit.Assert.*
import org.junit.Test

class VoiceCallRulesTest {
    // ---- stop phrases (desktop voice-stop-word.ts)
    @Test fun stopOnlyWhenTheWholeUtteranceIsAStopPhrase() {
        for (said in listOf("stop", "Stop.", "STOP!", "ok stop", "Hermes, stop", "hey hermes stop", "goodbye", "never mind", "That's all."))
            assertTrue(said, VoiceStop.isStop(said))
        for (said in listOf("stop the docker container", "how do I stop a process", "hermes", "", "   ", "don't stop"))
            assertFalse(said, VoiceStop.isStop(said))
        assertFalse(VoiceStop.isStop("stop", phrases = emptyList()))
        assertTrue(VoiceStop.isStop("отбой", phrases = listOf("отбой")))
    }

    // ---- echo guard (tools/voice_mode_transcript.is_tts_echo)
    @Test fun sequenceMatcherMatchesCPython() {
        fun r(a: String, b: String) = TtsEcho.ratio(a.codePoints().toArray().toList(), b.codePoints().toArray().toList())
        // difflib.SequenceMatcher(None, a, b).ratio() values computed with CPython 3.
        assertEquals(0.75, r("abcd", "bcde"), 1e-9)
        assertEquals(1.0, r("", ""), 1e-9)
        assertEquals(0.0, r("abc", "xyz"), 1e-9)
        assertEquals(0.8, r("hello", "hallo"), 1e-9)
    }

    @Test fun ownSpeechHeardBackIsEchoARealInterjectionIsNot() {
        val spoken = "The deploy finished at nine. Two tests failed in the billing module, and I opened a card for Mason."
        assertTrue(TtsEcho.isEcho("two tests failed in the billing module", spoken))
        assertTrue(TtsEcho.isEcho("The deploy finished at nine.", spoken))
        assertFalse(TtsEcho.isEcho("wait, which tests?", spoken))
        assertFalse(TtsEcho.isEcho("no, tell Ward instead", spoken))
        // A short real answer is never matched against a same-size window.
        assertFalse(TtsEcho.isEcho("yes", spoken))
        assertFalse(TtsEcho.isEcho("anything", ""))
    }

    // ---- barge detector (tools/voice_mode._BargeDetector)
    private fun BargeDetector.feedN(n: Int, rms: Double, playing: Boolean): BargePhase? {
        var out: BargePhase? = null
        repeat(n) { feed(rms, playing)?.let { out = it } }
        return out
    }

    @Test fun speechInAQuietRoomTripsAsGeneration() {
        val d = BargeDetector()
        assertNull(d.feedN(15, 150.0, playing = false)) // calibration on the quiet room
        assertTrue(d.floorLocked)
        assertEquals(BargeDetector.SILENCE_RMS, d.quietFloor, 1e-9) // floor never below the silence threshold
        assertEquals(BargePhase.GENERATION, d.feedN(10, 3000.0, playing = false))
    }

    @Test fun speakerBleedNeverTripsButSpeechOverPlaybackDoes() {
        val d = BargeDetector()
        d.feedN(15, 150.0, playing = false)
        // Bleed from the bot's own voice (~1000-1400 RMS) stays under the playback clamp.
        assertNull(d.feedN(60, 1300.0, playing = true))
        assertEquals(1500.0, d.trigger(playing = true), 1e-9)
        assertEquals(BargePhase.PLAYBACK, d.feedN(12, 3500.0, playing = true))
    }

    @Test fun graceAfterPlaybackStartsSwallowsTheOnsetOnly() {
        val d = BargeDetector()
        d.feedN(15, 150.0, playing = false)
        // Loud for exactly the 16 grace blocks: suppressed.
        assertNull(d.feedN(16, 5000.0, playing = true))
        // Still talking after grace: trips once the 80 % window fills.
        assertEquals(BargePhase.PLAYBACK, d.feedN(10, 5000.0, playing = true))
    }

    @Test fun briefNoiseDoesNotTripAndANoisyRoomStaysReachable() {
        val d = BargeDetector()
        d.feedN(15, 150.0, playing = false)
        assertNull(d.feedN(5, 3000.0, playing = false))   // a cough: 150 ms
        assertNull(d.feedN(20, 150.0, playing = false))
        val noisy = BargeDetector()
        noisy.feedN(15, 2500.0, playing = false)
        assertEquals(BargeDetector.TRIGGER_CEILING, noisy.trigger(playing = false), 1e-9) // capped: speech still trips
        assertEquals(BargePhase.GENERATION, noisy.feedN(10, 4500.0, playing = false))
    }

    @Test fun calibrationIsNeverTakenFromPlayback() {
        val d = BargeDetector()
        // Playback starts before the room was sampled: the floor locks at the silence threshold, not at the bleed.
        d.feed(1300.0, playing = true)
        assertTrue(d.floorLocked)
        assertEquals(BargeDetector.SILENCE_RMS, d.quietFloor, 1e-9)
    }

    @Test fun utteranceEndsAfterQuietOrAtTheCap() {
        val e = UtteranceEndpointer(quietFloor = 200.0, endpointMs = 300)
        repeat(20) { assertFalse(e.feed(3000.0)) }
        repeat(9) { assertFalse(e.feed(100.0)) }
        assertTrue(e.feed(100.0))
        val capped = UtteranceEndpointer(quietFloor = 200.0, endpointMs = 1250, maxMs = 300)
        repeat(9) { assertFalse(capped.feed(3000.0)) }
        assertTrue(capped.feed(3000.0))
        // A noisy room: "quiet" is relative to its floor.
        val noisy = UtteranceEndpointer(quietFloor = 1000.0, endpointMs = 60)
        assertFalse(noisy.feed(1400.0)); assertTrue(noisy.feed(1400.0))
    }

    // ---- per-sentence speech
    @Test fun sentencesAreCutAsTheReplyStreams() {
        val s = SpeechSentences()
        assertEquals(emptyList<String>(), s.feed("Sure. The build "))
        assertEquals(listOf("Sure. The build passed on the first try."), s.feed("passed on the first try. Next"))
        assertEquals(emptyList<String>(), s.feed(" I will"))
        assertEquals(listOf("Next I will check logs."), s.feed(" check logs.") + s.flush())
    }

    @Test fun codeLinksAndMarkdownAreNotReadAloud() {
        val s = SpeechSentences()
        val out = s.feed("Here is the **fix** for [the bug](https://x.y/1):\n```kotlin\nval a = 1. b\n```\nIt's in `Main.kt`, see https://github.com/x for more. ") + s.flush()
        val joined = out.joinToString(" ")
        assertFalse(joined, joined.contains("val a"))
        assertFalse(joined, joined.contains("**"))
        assertFalse(joined, joined.contains("https"))
        assertTrue(joined, joined.contains("the bug"))
        assertTrue(joined, joined.contains("Main.kt"))
        assertEquals("", VoiceSpeech.clean("MEDIA:/home/x/report.pdf"))
        assertEquals("Done.", VoiceSpeech.clean("<think>plan</think>Done."))
        assertEquals("e.g. this stays one sentence ok", s.feed("e.g. this stays one sentence ok").let { it + s.flush() }.single())
    }

    @Test fun approvalIsAnnouncedNotAnswerable() {
        assertEquals("Ezra needs your OK, check the screen.", VoiceSpeech.approvalLine("Ezra"))
    }

    // ---- the call belongs to one chat
    private fun view(
        epoch: Long = 1, connection: String? = "home", chatPage: Boolean = true, stored: String? = "s1", runtime: String? = "r1",
        profile: String? = "ezra", botChat: Boolean = true, readOnly: Boolean = false
    ) = ChatView(epoch, connection, "CHAT", chatPage, stored, runtime, profile, botChat, readOnly, null, false, false)

    @Test fun callEndsWhenItsChatLeavesTheScreen() {
        val b = VoiceCallBinding(1, "home", "ezra", "s1", "r1")
        assertTrue(b.stillOn(view()))
        assertTrue(b.stillOn(view(stored = "s1-rotated")))   // compression rotated the stored id
        assertTrue(b.stillOn(view(runtime = "r2")))
        assertFalse(b.stillOn(view(stored = "s2", runtime = "r2"))) // another chat
        assertFalse(b.stillOn(view(profile = "mason", stored = "s2", runtime = "r2")))
        assertFalse(b.stillOn(view(chatPage = false)))        // another page, Search or Bot details over it
        assertFalse(b.stillOn(view(epoch = 2)))               // the connection ended
        assertFalse(b.stillOn(view(connection = "bilbo")))
        assertFalse(b.stillOn(view(botChat = false)))
        assertFalse(b.stillOn(view(readOnly = true)))
        assertFalse(b.stillOn(view(stored = null, runtime = null, profile = null, botChat = false)))
    }

    // ---- audio route
    @Test fun headsetFirstAndAUserPickIsKept() {
        val phone = listOf(CallRouteKind.EARPIECE, CallRouteKind.SPEAKER)
        val withBt = phone + CallRouteKind.BLUETOOTH
        assertEquals(CallRouteKind.SPEAKER, CallRoutes.preferred(phone))
        assertEquals(CallRouteKind.BLUETOOTH, CallRoutes.preferred(withBt))
        // Earbuds connect mid-call: they take over.
        assertEquals(CallRouteKind.BLUETOOTH, CallRoutes.next(CallRouteKind.SPEAKER, phone, withBt))
        // The user picked the earpiece: a refresh keeps it.
        assertEquals(CallRouteKind.EARPIECE, CallRoutes.next(CallRouteKind.EARPIECE, withBt, withBt))
        // Earbuds gone: back to the speaker.
        assertEquals(CallRouteKind.SPEAKER, CallRoutes.next(CallRouteKind.BLUETOOTH, withBt, phone))
    }
}
