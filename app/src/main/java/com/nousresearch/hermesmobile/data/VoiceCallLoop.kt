package com.nousresearch.hermesmobile.data

/**
 * The live call's conversation loop, as a pure state machine (driven on the main thread, unit-tested). It follows
 * the desktop's `use-voice-conversation.ts` and the Hermes docs ("Barge-in"):
 *  - the mic stays live through the whole bot turn: talking while the bot is still generating interrupts the turn,
 *    talking over its speech cuts the speech; either way the captured words become the next turn;
 *  - "stop" (the whole utterance) ends the call, in every phase;
 *  - a transcript that matches what the bot was saying when the user "barged" is the phone hearing itself: dropped;
 *  - an approval request is announced ("Ezra needs your OK, check the screen") and answered by tap only.
 * The audio side (mic, player, network) lives in the engine, which implements [Effects] and feeds events back.
 */
class VoiceCallLoop(private val fx: Effects, private val now: () -> Long = System::currentTimeMillis) {
    interface Effects {
        /** Listen (a fresh barge detector) / stop listening. */
        fun armMic()
        fun pauseMic()
        /** Cut playback now and drop every queued sentence. */
        fun stopSpeech()
        /** Queue one sentence to be spoken after the ones already queued. */
        fun speak(sentence: String)
        /** Stop the bot's running turn (the chat's Stop). */
        fun interruptTurn()
        fun transcribe(wav: ByteArray, token: Long)
        /** Send [text] as the user's next turn in the bot chat (`voice_turn`, plus `interrupted` after a barge). */
        fun submit(text: String, interrupted: Boolean)
        fun phaseChanged(phase: Phase)
        fun notice(notice: Notice)
        fun ended(reason: EndReason)
    }

    enum class Phase { LISTENING, CAPTURING, TRANSCRIBING, THINKING, SPEAKING, HOLD, ENDED }
    enum class Notice { DIDNT_CATCH, TRANSCRIBE_FAILED }
    enum class EndReason { HUNG_UP, STOP_WORD, AUDIO_LOST, CHAT_CLOSED, FAILED }

    companion object {
        /** How long a spoken interjection waits for the interrupted turn to settle before it is sent anyway. */
        const val INTERRUPT_SETTLE_MS = 5_000L
    }

    var phase = Phase.LISTENING
        private set
    var muted = false
        private set
    var turnRunning = false
        private set
    /** True while sentences are queued or playing. */
    var speaking = false
        private set

    private val sentences = SpeechSentences()
    private val spoken = StringBuilder()
    /** The reply on its way is not ours to speak (it was running before the call, or the user cut it off). */
    private var dropReply = false
    private var bargeEcho = ""
    private var interrupted = false
    private var token = 0L
    private var pendingText: String? = null
    private var pendingDeadline = 0L

    private fun go(next: Phase) {
        if (phase == next) return
        phase = next
        fx.phaseChanged(next)
    }

    private val live get() = phase != Phase.ENDED

    /** The call opens. A turn already running keeps going, but its reply (started before the call) is not read out. */
    fun start(turnAlreadyRunning: Boolean) {
        turnRunning = turnAlreadyRunning
        dropReply = turnAlreadyRunning
        phase = if (turnAlreadyRunning) Phase.THINKING else Phase.LISTENING
        fx.phaseChanged(phase)
        fx.armMic()
    }

    /** The detector heard the user start talking ([duringPlayback] = over the bot's speech). */
    fun onSpeechStart(duringPlayback: Boolean) {
        if (!live || muted || phase == Phase.HOLD) return
        if (speaking || duringPlayback) {
            // Talking over the bot: cut it at once; what it was saying is the echo reference.
            bargeEcho = if (duringPlayback) spoken.toString() else ""
            interrupted = interrupted || spoken.isNotEmpty()
            fx.stopSpeech()
            speaking = false
            dropReply = true
        }
        if (turnRunning) {
            // Talking while it still works: stop the turn so the interjection is next, not queued behind it.
            fx.interruptTurn()
            dropReply = true
        }
        go(Phase.CAPTURING)
    }

    /** The captured utterance ([wav] null = capture failed or was too short to send). */
    fun onUtterance(wav: ByteArray?) {
        if (!live || phase != Phase.CAPTURING) return
        if (wav == null) { resume(); return }
        go(Phase.TRANSCRIBING)
        fx.pauseMic()
        fx.transcribe(wav, ++token)
    }

    /** STT answered for [forToken]: the words, "" for no speech, null when it failed. */
    fun onTranscript(forToken: Long, text: String?) {
        if (!live || forToken != token || phase != Phase.TRANSCRIBING) return
        val echo = bargeEcho
        bargeEcho = ""
        when {
            text == null -> { fx.notice(Notice.TRANSCRIBE_FAILED); resume() }
            text.isBlank() -> { fx.notice(Notice.DIDNT_CATCH); resume() }
            VoiceStop.isStop(text) -> end(EndReason.STOP_WORD)
            echo.isNotEmpty() && TtsEcho.isEcho(text, echo) -> { interrupted = false; resume() }
            else -> {
                // Words still waiting from an earlier interjection go first, in one turn.
                val words = listOfNotNull(pendingText, text.trim()).joinToString(" ")
                if (turnRunning) {
                    // The interrupted turn is still unwinding: send once it settles (or after a short wait anyway).
                    pendingText = words
                    pendingDeadline = now() + INTERRUPT_SETTLE_MS
                    go(Phase.THINKING)
                    if (!muted) fx.armMic()
                } else send(words)
            }
        }
    }

    /** A waiting interjection may go now: never while the user is mid-utterance (it joins that one instead). */
    private fun canSendPending() = phase != Phase.CAPTURING && phase != Phase.TRANSCRIBING && phase != Phase.HOLD

    private fun send(text: String) {
        pendingText = null
        fx.submit(text, interrupted)
        interrupted = false
        newReply(drop = false)
        go(Phase.THINKING)
        if (!muted) fx.armMic()
    }

    private fun newReply(drop: Boolean) {
        sentences.reset()
        spoken.setLength(0)
        dropReply = drop
    }

    /** The bot's turn started in this chat (ours, or one queued/started elsewhere: it is read out too). */
    fun onTurnStarted() {
        if (!live) return
        val wasRunning = turnRunning
        turnRunning = true
        if (!wasRunning && pendingText == null) newReply(drop = phase == Phase.CAPTURING || phase == Phase.TRANSCRIBING)
        if (phase == Phase.LISTENING) go(Phase.THINKING)
    }

    fun onTurnDelta(text: String) {
        if (!live || dropReply || pendingText != null) return
        sentences.feed(text).forEach(::say)
    }

    /** The turn finished (or was stopped). A waiting interjection is sent now. */
    fun onTurnEnded() {
        if (!live) return
        turnRunning = false
        if (!dropReply && pendingText == null) sentences.flush().forEach(::say)
        val waiting = pendingText
        if (waiting != null) { if (canSendPending()) send(waiting); return }
        dropReply = false
        if (!speaking && (phase == Phase.THINKING || phase == Phase.SPEAKING)) go(Phase.LISTENING)
    }

    private fun say(sentence: String) {
        spoken.append(sentence).append(' ')
        speaking = true
        fx.speak(sentence)
    }

    fun onPlaybackStarted() {
        if (!live) return
        if (phase == Phase.THINKING || phase == Phase.LISTENING) go(Phase.SPEAKING)
    }

    /** Every queued sentence has been played. */
    fun onPlaybackIdle() {
        if (!live) return
        speaking = false
        if (phase == Phase.SPEAKING) go(if (turnRunning) Phase.THINKING else Phase.LISTENING)
    }

    private var unannounced: String? = null

    /**
     * An approval waits in this chat: the card is on the call screen; the voice points at it. Never approvable by
     * voice. While the call is on hold (a phone call) it is said once the call resumes.
     */
    fun onApprovalWaiting(botName: String) {
        if (!live) return
        if (phase == Phase.HOLD) { unannounced = botName; return }
        say(VoiceSpeech.approvalLine(botName))
    }

    /** Called every ~250 ms by the engine: sends a waiting interjection once its settle time is over. */
    fun onTick() {
        if (!live) return
        val text = pendingText ?: return
        if (now() >= pendingDeadline && canSendPending()) send(text)
    }

    /** Another app took the audio (a phone call ringing): hold; [permanent] = it is not coming back. */
    fun onAudioFocusLost(permanent: Boolean) {
        if (!live) return
        if (permanent) { end(EndReason.AUDIO_LOST); return }
        fx.stopSpeech()
        speaking = false
        dropReply = true
        fx.pauseMic()
        token++
        go(Phase.HOLD)
    }

    fun onAudioFocusGained() {
        if (!live || phase != Phase.HOLD) return
        resume()
        unannounced?.let { unannounced = null; say(VoiceSpeech.approvalLine(it)) }
    }

    fun setMuted(mute: Boolean) {
        if (!live || muted == mute) return
        muted = mute
        if (mute) {
            fx.pauseMic()
            if (phase == Phase.CAPTURING) resume()
        } else if (phase != Phase.HOLD && phase != Phase.TRANSCRIBING) fx.armMic()
    }

    fun hangUp() = end(EndReason.HUNG_UP)

    /** The chat the call belongs to left the screen (navigation, push tap, disconnect). */
    fun chatClosed() = end(EndReason.CHAT_CLOSED)

    fun fail() = end(EndReason.FAILED)

    private fun resume() {
        go(when {
            turnRunning || pendingText != null -> Phase.THINKING
            speaking -> Phase.SPEAKING
            else -> Phase.LISTENING
        })
        if (!muted) fx.armMic()
    }

    private fun end(reason: EndReason) {
        if (!live) return
        fx.stopSpeech()
        fx.pauseMic()
        speaking = false
        pendingText = null
        token++
        go(Phase.ENDED)
        fx.ended(reason)
    }
}
