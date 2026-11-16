package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.VoiceCallLoop
import com.nousresearch.hermesmobile.data.VoiceCallLoop.Phase
import org.junit.Assert.*
import org.junit.Test

/** The live call's loop (desktop use-voice-conversation.ts semantics), driven event by event. */
class VoiceCallLoopTest {
    private class Fx : VoiceCallLoop.Effects {
        val log = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, Boolean>>()
        var lastToken = 0L
        var ended: VoiceCallLoop.EndReason? = null
        override fun armMic() { log += "arm" }
        override fun pauseMic() { log += "pause" }
        override fun stopSpeech() { log += "stopSpeech" }
        override fun speak(sentence: String) { spoken += sentence }
        override fun interruptTurn() { log += "interrupt" }
        override fun transcribe(wav: ByteArray, token: Long) { lastToken = token; log += "transcribe" }
        override fun submit(text: String, interrupted: Boolean) { submitted += text to interrupted }
        override fun phaseChanged(phase: Phase) {}
        override fun notice(notice: VoiceCallLoop.Notice) { log += "notice:$notice" }
        override fun ended(reason: VoiceCallLoop.EndReason) { ended = reason }
    }

    private var clock = 0L
    private val fx = Fx()
    private val loop = VoiceCallLoop(fx) { clock }
    private val wav = ByteArray(4)

    /** The user says [text] (detector trip, capture, STT). */
    private fun say(text: String?, overPlayback: Boolean = false) {
        loop.onSpeechStart(overPlayback)
        loop.onUtterance(wav)
        loop.onTranscript(fx.lastToken, text)
    }

    /** The bot answers [reply] in a full turn, and its speech plays out. */
    private fun botAnswers(reply: String) {
        loop.onTurnStarted()
        loop.onTurnDelta(reply)
        loop.onPlaybackStarted()
        loop.onTurnEnded()
        loop.onPlaybackIdle()
    }

    @Test fun aSpokenTurnGoesToTheChatAndTheReplyIsSpokenSentenceBySentence() {
        loop.start(turnAlreadyRunning = false)
        assertEquals(Phase.LISTENING, loop.phase)
        say("what's on my calendar today")
        assertEquals(listOf("what's on my calendar today" to false), fx.submitted)
        assertEquals(Phase.THINKING, loop.phase)
        loop.onTurnStarted()
        loop.onTurnDelta("You have two meetings today. The first ")
        assertEquals(listOf("You have two meetings today."), fx.spoken)
        loop.onPlaybackStarted()
        assertEquals(Phase.SPEAKING, loop.phase)
        loop.onTurnDelta("is at ten with Yanky.")
        loop.onTurnEnded()
        assertEquals(listOf("You have two meetings today.", "The first is at ten with Yanky."), fx.spoken)
        loop.onPlaybackIdle()
        assertEquals(Phase.LISTENING, loop.phase)
    }

    @Test fun talkingOverPlaybackCutsItAndSubmitsWithTheInterruptedNote() {
        loop.start(false)
        say("tell me about the deploy")
        loop.onTurnStarted()
        loop.onTurnDelta("The deploy finished at nine and everything is green. ")
        loop.onPlaybackStarted()
        loop.onTurnEnded()
        // Still speaking: the user cuts in.
        loop.onSpeechStart(duringPlayback = true)
        assertTrue("stopSpeech" in fx.log)
        assertEquals(Phase.CAPTURING, loop.phase)
        loop.onUtterance(wav)
        loop.onTranscript(fx.lastToken, "wait, which server?")
        assertEquals("wait, which server?" to true, fx.submitted.last())
    }

    @Test fun speakingDuringGenerationInterruptsTheTurnAndWaitsForItToSettle() {
        loop.start(false)
        say("summarise my inbox")
        loop.onTurnStarted()
        fx.log.clear()
        loop.onSpeechStart(duringPlayback = false)
        assertTrue("interrupt" in fx.log)
        loop.onUtterance(wav)
        loop.onTranscript(fx.lastToken, "actually only the urgent ones")
        // The interrupted turn hasn't ended yet: held, not sent behind it.
        assertEquals(1, fx.submitted.size)
        // Late deltas of the stopped turn are not spoken.
        loop.onTurnDelta("Here is your whole inbox summary in detail. ")
        assertTrue(fx.spoken.isEmpty())
        loop.onTurnEnded()
        assertEquals("actually only the urgent ones" to false, fx.submitted.last())
    }

    @Test fun aHeldInterjectionGoesAnywayAfterTheSettleTime() {
        loop.start(false)
        say("start the backup")
        loop.onTurnStarted()
        say("and email me when done")
        assertEquals(1, fx.submitted.size)
        clock += VoiceCallLoop.INTERRUPT_SETTLE_MS
        loop.onTick()
        assertEquals("and email me when done", fx.submitted.last().first)
    }

    @Test fun twoInterjectionsWhileTheTurnUnwindsBecomeOneTurn() {
        loop.start(false)
        say("start the backup")
        loop.onTurnStarted()
        say("no wait")
        say("do the database first")
        loop.onTurnEnded()
        assertEquals("no wait do the database first", fx.submitted.last().first)
        assertEquals(2, fx.submitted.size)
    }

    @Test fun stopEndsTheCallInEveryPhase() {
        loop.start(false)
        say("stop")
        assertEquals(VoiceCallLoop.EndReason.STOP_WORD, fx.ended)
        assertEquals(Phase.ENDED, loop.phase)
        assertTrue(fx.submitted.isEmpty())

        val fx2 = Fx(); val l2 = VoiceCallLoop(fx2) { 0 }
        l2.start(false)
        l2.onSpeechStart(false); l2.onUtterance(wav); l2.onTranscript(fx2.lastToken, "check the logs")
        l2.onTurnStarted(); l2.onTurnDelta("Checking the logs for you right now. "); l2.onPlaybackStarted()
        l2.onSpeechStart(true); l2.onUtterance(wav); l2.onTranscript(fx2.lastToken, "Stop.")
        assertEquals(VoiceCallLoop.EndReason.STOP_WORD, fx2.ended)
        assertEquals(1, fx2.submitted.size)
    }

    @Test fun theBotHearingItselfIsDroppedNotSent() {
        loop.start(false)
        say("read me the report")
        loop.onTurnStarted()
        loop.onTurnDelta("Two tests failed in the billing module this morning. ")
        loop.onPlaybackStarted()
        // Speaker bleed trips the detector and STT returns the bot's own words.
        say("two tests failed in the billing module", overPlayback = true)
        assertEquals(1, fx.submitted.size)
        assertNotEquals(Phase.ENDED, loop.phase)
        // The next real turn carries no stale "interrupted" note.
        loop.onTurnEnded()
        say("thanks, open a card")
        assertEquals("thanks, open a card" to false, fx.submitted.last())
    }

    @Test fun silenceAndSttFailuresJustListenAgain() {
        loop.start(false)
        say("")
        assertEquals(Phase.LISTENING, loop.phase)
        assertTrue("notice:DIDNT_CATCH" in fx.log)
        say(null)
        assertEquals(Phase.LISTENING, loop.phase)
        assertTrue("notice:TRANSCRIBE_FAILED" in fx.log)
        assertTrue(fx.submitted.isEmpty())
    }

    @Test fun aStaleTranscriptIsIgnored() {
        loop.start(false)
        loop.onSpeechStart(false); loop.onUtterance(wav)
        val old = fx.lastToken
        loop.hangUp()
        loop.onTranscript(old, "send the invoice")
        assertTrue(fx.submitted.isEmpty())
    }

    @Test fun approvalsAreAnnouncedAndOnHoldAnnouncedOnResume() {
        loop.start(false)
        say("delete the scratch folder")
        loop.onTurnStarted()
        loop.onApprovalWaiting("Ezra")
        assertEquals("Ezra needs your OK, check the screen.", fx.spoken.last())
        // A phone call rings: hold. The ask that comes meanwhile is said once the call resumes.
        loop.onAudioFocusLost(permanent = false)
        assertEquals(Phase.HOLD, loop.phase)
        val before = fx.spoken.size
        loop.onApprovalWaiting("Ezra")
        assertEquals(before, fx.spoken.size)
        loop.onAudioFocusGained()
        assertEquals("Ezra needs your OK, check the screen.", fx.spoken.last())
        assertNotEquals(Phase.HOLD, loop.phase)
    }

    @Test fun anApprovalCanNeverBeAnsweredByVoice() {
        loop.start(false)
        say("restart the gateway")
        loop.onTurnStarted()
        loop.onApprovalWaiting("Ezra")
        loop.onTurnEnded()
        say("yes approve it")
        // "approve" is just words to the bot: no effect can answer an approval (the loop has none), it is a turn.
        assertEquals("yes approve it", fx.submitted.last().first)
    }

    @Test fun holdStopsTalkingAndListeningAndPermanentLossEnds() {
        loop.start(false)
        botAnswers("Fine. Here is something long enough to be spoken. ")
        fx.log.clear()
        loop.onAudioFocusLost(permanent = false)
        assertTrue("stopSpeech" in fx.log && "pause" in fx.log)
        loop.onSpeechStart(false) // ignored on hold
        assertEquals(Phase.HOLD, loop.phase)
        loop.onAudioFocusGained()
        assertEquals(Phase.LISTENING, loop.phase)
        loop.onAudioFocusLost(permanent = true)
        assertEquals(VoiceCallLoop.EndReason.AUDIO_LOST, fx.ended)
    }

    @Test fun muteStopsListeningButNotTheBot() {
        loop.start(false)
        loop.setMuted(true)
        assertEquals("pause", fx.log.last())
        loop.onSpeechStart(false)
        assertEquals(Phase.LISTENING, loop.phase)
        botAnswers("This reply is still spoken while muted, of course. ")
        assertTrue(fx.spoken.isNotEmpty())
        loop.setMuted(false)
        assertEquals("arm", fx.log.last())
    }

    @Test fun aReplyAlreadyRunningWhenTheCallStartsIsNotReadOut() {
        loop.start(turnAlreadyRunning = true)
        assertEquals(Phase.THINKING, loop.phase)
        loop.onTurnDelta("half of an old reply that started before. ")
        loop.onTurnEnded()
        assertTrue(fx.spoken.isEmpty())
        assertEquals(Phase.LISTENING, loop.phase)
        say("hi")
        loop.onTurnStarted()
        loop.onTurnDelta("Hello Tzvi, what can I do for you? ")
        assertEquals(listOf("Hello Tzvi, what can I do for you?"), fx.spoken)
    }

    @Test fun leavingTheChatEndsTheCallWithoutStoppingTheBot() {
        loop.start(false)
        say("write the summary")
        loop.onTurnStarted()
        fx.log.clear()
        loop.chatClosed()
        assertEquals(VoiceCallLoop.EndReason.CHAT_CLOSED, fx.ended)
        assertFalse("interrupt" in fx.log) // the reply still lands in the chat
        loop.onTurnDelta("More text. And more text after the call ended. ")
        assertTrue(fx.spoken.isEmpty())
    }
}
